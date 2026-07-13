package xx.biketracker.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xx.biketracker.ACCURACY_THRESHOLD_M
import xx.biketracker.AUTO_PAUSE_DEBOUNCE_MS
import xx.biketracker.AUTO_PAUSE_SPEED_MPS
import xx.biketracker.AUTO_RESUME_SPEED_MPS
import xx.biketracker.DEFAULT_AUTO_SAVE_MS
import xx.biketracker.DRAFT_FLUSH_EVERY_POINTS
import xx.biketracker.elevationGainMeters
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GPS_MIN_INTERVAL_MS
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.haversineMeters
import xx.biketracker.MainActivity
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.DatabaseMaintenance
import xx.biketracker.data.DatabaseRestoreCoordinator
import xx.biketracker.data.RestoreOperationState
import xx.biketracker.data.TrackPoint
import xx.biketracker.data.Trip
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Consume an already chronological provider batch until service shutdown begins. */
internal fun <T> processOrderedBatch(
    items: List<T>,
    shouldStop: () -> Boolean,
    process: (T) -> Unit,
): Int {
    var processed = 0
    for (item in items) {
        if (shouldStop()) break
        process(item)
        processed++
    }
    return processed
}

/** Positive elapsed time between fixes, or null for duplicate/non-monotonic provider data. */
internal fun elapsedMillisBetween(previousNanos: Long, currentNanos: Long): Long? {
    val deltaNanos = currentNanos - previousNanos
    if (deltaNanos <= 0) return null
    val deltaMillis = deltaNanos / 1_000_000L
    return deltaMillis.takeIf { it > 0 }
}

internal data class LocationFixCandidate(
    val lat: Double,
    val lon: Double,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float?,
    val speedMps: Double?,
    val altitudeMeters: Double?,
    val bearingDegrees: Float?,
)

internal data class ValidatedLocationFix(
    val lat: Double,
    val lon: Double,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float,
    val speedMps: Double?,
    val altitudeMeters: Double?,
    val bearingDegrees: Float?,
)

/** Validate a fix completely before it can mutate tracking state. */
internal fun validateLocationFix(
    candidate: LocationFixCandidate,
    previous: ValidatedLocationFix?,
): ValidatedLocationFix? {
    if (!candidate.lat.isFinite() || candidate.lat !in -90.0..90.0) return null
    if (!candidate.lon.isFinite() || candidate.lon !in -180.0..180.0) return null
    if (candidate.wallTimeMillis <= 0 || candidate.elapsedRealtimeNanos <= 0) return null

    val accuracy = candidate.accuracyMeters ?: return null
    if (!accuracy.isFinite() || accuracy < 0f || accuracy > ACCURACY_THRESHOLD_M) return null

    val speed = candidate.speedMps
    if (speed != null && (!speed.isFinite() || speed < 0.0 || speed > MAX_PLAUSIBLE_SPEED_MPS)) return null
    if (candidate.altitudeMeters?.isFinite() == false) return null
    if (candidate.bearingDegrees?.let { !it.isFinite() || it < 0f || it >= 360f } == true) return null

    if (previous != null) {
        val dtMillis = elapsedMillisBetween(
            previousNanos = previous.elapsedRealtimeNanos,
            currentNanos = candidate.elapsedRealtimeNanos,
        ) ?: return null
        val coordinateSpeed = haversineMeters(
            previous.lat,
            previous.lon,
            candidate.lat,
            candidate.lon,
        ) / (dtMillis / 1000.0)
        if (!coordinateSpeed.isFinite() || coordinateSpeed > MAX_PLAUSIBLE_SPEED_MPS) return null
    }

    return ValidatedLocationFix(
        lat = candidate.lat,
        lon = candidate.lon,
        wallTimeMillis = candidate.wallTimeMillis,
        elapsedRealtimeNanos = candidate.elapsedRealtimeNanos,
        accuracyMeters = accuracy,
        speedMps = speed,
        altitudeMeters = candidate.altitudeMeters,
        bearingDegrees = candidate.bearingDegrees,
    )
}

/**
 * Foreground service that records a ride: it pulls GPS fixes from the fused
 * location provider, filters noise, accumulates distance / moving time / peak
 * speed, and drives the start → pause → resume → stop state machine (including
 * auto-pause on standstill with auto-resume on movement, and auto-save after a
 * long pause). A manual pause is sticky: only the Resume button ends it. On stop it persists
 * the ride and its points, then stops itself.
 *
 * The UI never binds here; it sends commands via the companion helpers and reads
 * live state from [TrackingState].
 */
class TrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var status = TrackingStatus.IDLE
    private var startTime = 0L
    private var startElapsedRealtime = 0L
    private var distanceMeters = 0.0
    private var movingTimeMillis = 0L
    private var maxSpeedMps = 0.0
    private var lastPoint: TrackPoint? = null
    private var lastPointElapsedRealtimeNanos: Long? = null
    private var lastTrustedFix: ValidatedLocationFix? = null
    private var lastTrustedFixElapsedRealtime = 0L
    private val points = mutableListOf<TrackPoint>()
    private val route = mutableListOf<GeoPoint>()

    private val kalman = GpsKalmanFilter()
    private var pausedAutomatically = false
    private var lowSpeedSince = 0L
    private var autoSaveJob: Job? = null

    private var currentSpeedMps = 0.0
    private var altitudeMeters: Double? = null
    private var gpsAccuracyMeters: Float? = null
    private var bearingDegrees: Float? = null

    // Guards stopAndSave so a manual Stop racing with the pause auto-save (on
    // different threads) can't persist the same ride twice.
    private val stopping = AtomicBoolean(false)
    private var ownsRideReservation = false

    // Every checkpoint is reconciled against the database point count. Failed or ambiguously
    // committed batches can therefore be retried without skipping or duplicating points.
    private var draftPersistence: DraftPersistence? = null
    private var draftStartJob: Job? = null
    private val flushJobs = mutableListOf<Job>()
    private var scheduledFlushCount = 0
    private var persistenceFailed = false
    private var startupPending = false
    private var registrationReady = false
    private var draftReady = false
    private var foregroundStarted = false
    private var activeStartId = 0

    // Fused Location can deliver several fixes at once. Their state transitions must all run, but
    // Compose and NotificationManager only need the final result of the batch.
    private var deferOutputs = false
    private var publishPending = false
    private var notificationPending = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            deferOutputs = true
            try {
                processOrderedBatch(
                    items = result.locations,
                    shouldStop = stopping::get,
                    process = ::onLocation,
                )
            } finally {
                deferOutputs = false
                if (notificationPending) {
                    notificationPending = false
                    updateNotification()
                }
                if (publishPending) {
                    publishPending = false
                    publish()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A command other than START can reach a freshly created instance (e.g. the user taps
        // Resume just as the auto-save finished the previous service). Such an instance must
        // stop immediately: it was started via startForegroundService, and neither calling
        // startForeground nor stopSelf would crash with a foreground-timeout exception.
        if (startupPending && intent?.action != ACTION_START) {
            failStartup()
            return START_NOT_STICKY
        }
        if (status == TrackingStatus.IDLE && intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startTracking(startId)
            ACTION_PAUSE -> pauseTracking(automatic = false)
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> stopAndSave()
            ACTION_DISCARD -> stopAndDiscard()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTracking(startId: Int) {
        if (status != TrackingStatus.IDLE || startupPending) return
        if (DatabaseRestoreCoordinator.state.value == RestoreOperationState.Running) {
            stopSelf()
            return
        }
        if (!DatabaseMaintenance.reserveRide()) {
            stopSelf()
            return
        }
        ownsRideReservation = true
        if (!hasLocationPermission()) {
            releaseRideReservation()
            stopSelf()
            return
        }
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        activeStartId = startId
        startupPending = true
        registrationReady = false
        draftReady = false
        TrackingState.publish(TrackingSnapshot())
        try {
            startForegroundNotification()
            foregroundStarted = true
        } catch (_: RuntimeException) {
            failStartup()
            return
        }

        // In the rare case this instance is reused after a stop, allow the new ride to save.
        stopping.set(false)
        kalman.reset()
        flushJobs.clear()
        scheduledFlushCount = 0
        persistenceFailed = false
        lastTrustedFix = null
        lastTrustedFixElapsedRealtime = 0L
        startTime = System.currentTimeMillis()
        startElapsedRealtime = SystemClock.elapsedRealtime()
        val initialTrip = Trip(
            startTime = startTime,
            endTime = startTime,
            distanceMeters = 0.0,
            movingTimeMillis = 0L,
            maxSpeedMps = 0.0,
            finished = false,
        )
        val persistence = DraftPersistence(initialTrip, roomDraftGateway())
        draftPersistence = persistence
        draftStartJob = scope.launch {
            val result = persistence.ensureDraft()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    draftReady = true
                    completeStartupIfReady()
                } else {
                    failStartup()
                }
            }
        }
        requestUpdates()
    }

    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnSuccessListener {
                    registrationReady = true
                    completeStartupIfReady()
                }
                .addOnFailureListener { failStartup() }
        } catch (_: RuntimeException) {
            failStartup()
        }
    }

    private fun completeStartupIfReady() {
        if (!startupPending || !registrationReady || !draftReady) return
        startupPending = false
        status = TrackingStatus.RECORDING
        publish()
        updateNotification()
    }

    private fun failStartup() {
        if (!startupPending) return
        startupPending = false
        stopping.set(true)
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        val persistence = draftPersistence
        scope.launch {
            withContext(NonCancellable) {
                persistence?.discard()
                withContext(Dispatchers.Main) {
                    TrackingState.publish(TrackingSnapshot(startupFailed = true))
                    status = TrackingStatus.IDLE
                    releaseRideReservation()
                    if (foregroundStarted) {
                        foregroundStarted = false
                        ServiceCompat.stopForeground(
                            this@TrackingService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                    }
                    stopSelfResult(activeStartId)
                }
            }
        }
    }

    private fun onLocation(location: Location) {
        // A fix already queued on the main looper can arrive after stopAndSave; processing it
        // would re-post the removed notification and overwrite the TrackingState reset.
        if (stopping.get()) return
        if (status == TrackingStatus.IDLE) return
        val candidate = LocationFixCandidate(
            lat = location.latitude,
            lon = location.longitude,
            wallTimeMillis = location.time,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            speedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
        // Rejected fixes do not refresh GPS freshness or overwrite the last trusted telemetry.
        val fix = validateLocationFix(candidate, lastTrustedFix) ?: return
        lastTrustedFix = fix
        lastTrustedFixElapsedRealtime = fix.elapsedRealtimeNanos / 1_000_000L

        fix.speedMps?.let { currentSpeedMps = it }
        fix.altitudeMeters?.let { altitudeMeters = it }
        gpsAccuracyMeters = fix.accuracyMeters
        // Bearing is only trustworthy while moving; keep the last heading when the fix omits it,
        // so the puck doesn't spin to north at a standstill.
        if (fix.bearingDegrees != null && fix.speedMps != null && fix.speedMps >= AUTO_PAUSE_SPEED_MPS) {
            bearingDegrees = fix.bearingDegrees
        }

        when (status) {
            TrackingStatus.RECORDING -> recordLocation(fix)
            TrackingStatus.PAUSED -> fix.speedMps?.let(::maybeAutoResume)
            TrackingStatus.IDLE -> return
        }
        publish()
    }

    private fun recordLocation(fix: ValidatedLocationFix) {
        val prev = lastPoint
        val nowElapsedNanos = fix.elapsedRealtimeNanos
        val nowElapsedMillis = nowElapsedNanos / 1_000_000L
        var dt = 0L
        if (prev != null) {
            val prevElapsed = lastPointElapsedRealtimeNanos ?: return
            dt = elapsedMillisBetween(prevElapsed, nowElapsedNanos) ?: return
        }
        // A long outage (tunnel, indoors) produces no fixes, so auto-pause can't trigger; without
        // this break the first fix after the gap would add the whole outage to the moving time.
        val gapped = prev != null && dt > GPS_STALE_MS

        // Kalman-smooth the fix; the track and the distance both build on filtered points,
        // so standstill jitter neither paints zigzags nor inflates the total.
        val smoothed = kalman.filter(
            rawLat = fix.lat,
            rawLon = fix.lon,
            accuracyM = fix.accuracyMeters,
            timeMs = nowElapsedMillis,
            speedMps = fix.speedMps ?: 0.0,
        )
        if (prev != null && !gapped) {
            distanceMeters += haversineMeters(prev.lat, prev.lon, smoothed.lat, smoothed.lon)
            movingTimeMillis += dt
        }

        fix.speedMps?.let { maxSpeedMps = max(maxSpeedMps, it) }

        val point = TrackPoint(
            tripId = 0,
            lat = smoothed.lat,
            lon = smoothed.lon,
            time = fix.wallTimeMillis,
            speedMps = fix.speedMps?.toFloat() ?: 0f,
            altitudeMeters = fix.altitudeMeters,
        )
        points += point
        route += smoothed
        lastPoint = point
        lastPointElapsedRealtimeNanos = nowElapsedNanos
        if (points.size - scheduledFlushCount >= DRAFT_FLUSH_EVERY_POINTS) flushDraft()

        updateNotification()
        fix.speedMps?.let { evaluateAutoPause(it, nowElapsedMillis) }
    }

    private fun evaluateAutoPause(speed: Double, now: Long) {
        if (speed < AUTO_PAUSE_SPEED_MPS) {
            if (lowSpeedSince == 0L) {
                lowSpeedSince = now
            } else if (now - lowSpeedSince >= AUTO_PAUSE_DEBOUNCE_MS) {
                pauseTracking(automatic = true)
            }
        } else {
            lowSpeedSince = 0L
        }
    }

    // Only an automatic pause resumes by itself; a manual one waits for the button.
    private fun maybeAutoResume(speedMps: Double) {
        if (pausedAutomatically && speedMps >= AUTO_RESUME_SPEED_MPS) {
            resumeTracking()
        }
    }

    private fun pauseTracking(automatic: Boolean) {
        if (status != TrackingStatus.RECORDING) return
        status = TrackingStatus.PAUSED
        pausedAutomatically = automatic
        lowSpeedSince = 0L
        // Break the segment so the paused gap adds neither distance nor time.
        lastPoint = null
        lastPointElapsedRealtimeNanos = null
        flushDraft() // checkpoint the ride at every pause
        scheduleAutoSave()
        updateNotification()
        publish()
        // The rider chose a manual pause; only the unasked-for one warrants a buzz.
        if (automatic) vibrateAutoPause()
    }

    private fun resumeTracking() {
        if (status != TrackingStatus.PAUSED) return
        status = TrackingStatus.RECORDING
        pausedAutomatically = false
        cancelAutoSave()
        updateNotification()
        publish()
    }

    /** Double buzz on auto-pause — noticeable in a pocket, unlike any on-screen hint. */
    private fun vibrateAutoPause() {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200), -1))
    }

    private fun scheduleAutoSave() {
        cancelAutoSave()
        autoSaveJob = scope.launch {
            delay(DEFAULT_AUTO_SAVE_MS)
            // Decide and stop on the main thread so this can't race with a resume or
            // recordLocation mutating status/points; a resume's cancelAutoSave() then
            // aborts here before the check.
            withContext(Dispatchers.Main) {
                if (status == TrackingStatus.PAUSED) stopAndSave()
            }
        }
    }

    private fun cancelAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    /** Schedule a full checkpoint; the writer reconciles it with durable rows on retry. */
    private fun flushDraft() {
        val persistence = draftPersistence ?: return
        if (points.size == scheduledFlushCount) return
        val checkpoint = checkpoint(finished = false)
        scheduledFlushCount = checkpoint.points.size
        flushJobs += scope.launch {
            reportPersistenceResult(persistence.persist(checkpoint))
        }
    }

    private fun stopAndSave() = stop(save = true)
    private fun stopAndDiscard() = stop(save = false)

    private fun stop(save: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        cancelAutoSave()
        if (persistenceFailed) {
            persistenceFailed = false
            publish()
        }
        val persistence = draftPersistence ?: return handleSaveFailure()
        val pendingJobs = buildList {
            draftStartJob?.let(::add)
            addAll(flushJobs)
        }
        val finalCheckpoint = checkpoint(finished = true)
        scope.launch {
            withContext(NonCancellable) {
                pendingJobs.joinAll()
                val result = if (
                    !save ||
                    finalCheckpoint.points.size < 2 ||
                    finalCheckpoint.trip.distanceMeters <= 0
                ) {
                    persistence.discard()
                } else {
                    persistence.persist(finalCheckpoint)
                }
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) finishService() else handleSaveFailure()
                }
            }
        }
    }

    private fun checkpoint(finished: Boolean): DraftCheckpoint {
        val recorded = points.toList()
        val altitudes = recorded.map { it.altitudeMeters }
        return DraftCheckpoint(
            points = recorded,
            trip = Trip(
                startTime = startTime,
                endTime = recorded.lastOrNull()?.time ?: startTime,
                distanceMeters = distanceMeters,
                movingTimeMillis = movingTimeMillis,
                maxSpeedMps = maxSpeedMps,
                avgGpsSpeedMps = if (finished && recorded.isNotEmpty()) {
                    recorded.map { it.speedMps.toDouble() }.average()
                } else {
                    null
                },
                elevationGainMeters = if (finished && altitudes.any { it != null }) {
                    elevationGainMeters(altitudes)
                } else {
                    null
                },
                finished = finished,
            ),
        )
    }

    private suspend fun reportPersistenceResult(result: Result<*>) {
        withContext(Dispatchers.Main) {
            val failed = result.isFailure
            if (persistenceFailed != failed) {
                persistenceFailed = failed
                publish()
            }
        }
    }

    private fun handleSaveFailure() {
        status = TrackingStatus.PAUSED
        pausedAutomatically = false
        persistenceFailed = true
        stopping.set(false)
        updateNotification()
        publish()
    }

    private fun roomDraftGateway(): DraftPersistenceGateway = object : DraftPersistenceGateway {
        override suspend fun insertDraft(trip: Trip): Long = DatabaseMaintenance.withWrite {
            AppDatabase.get(applicationContext).tripDao().insertTrip(trip)
        }

        override suspend fun pointCount(tripId: Long): Int =
            AppDatabase.get(applicationContext).tripDao().getPointCount(tripId)

        override suspend fun commit(tripId: Long, newPoints: List<TrackPoint>, trip: Trip) {
            DatabaseMaintenance.withWrite {
                val db = AppDatabase.get(applicationContext)
                db.withTransaction {
                    if (newPoints.isNotEmpty()) db.tripDao().insertPoints(newPoints)
                    db.tripDao().updateTrip(trip.copy(id = tripId))
                }
            }
        }

        override suspend fun delete(tripId: Long) {
            DatabaseMaintenance.withWrite {
                AppDatabase.get(applicationContext).tripDao().deleteTripById(tripId)
            }
        }
    }

    private fun finishService() {
        TrackingState.reset()
        status = TrackingStatus.IDLE
        releaseRideReservation()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        if (deferOutputs) {
            publishPending = true
            return
        }
        TrackingState.publish(
            TrackingSnapshot(
                status = status,
                pausedAutomatically = pausedAutomatically,
                distanceMeters = distanceMeters,
                movingTimeMillis = movingTimeMillis,
                currentSpeedMps = if (status == TrackingStatus.RECORDING) currentSpeedMps else 0.0,
                maxSpeedMps = maxSpeedMps,
                altitudeMeters = altitudeMeters,
                gpsAccuracyMeters = gpsAccuracyMeters,
                bearingDegrees = bearingDegrees,
                startTime = startTime,
                startElapsedRealtime = startElapsedRealtime,
                updatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                lastTrustedFixElapsedRealtime = lastTrustedFixElapsedRealtime,
                persistenceFailed = persistenceFailed,
                route = route.toList(),
            )
        )
    }

    // --- Notification ---

    private fun startForegroundNotification() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun updateNotification() {
        if (deferOutputs) {
            notificationPending = true
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = when {
            persistenceFailed -> getString(R.string.notif_save_failed)
            status == TrackingStatus.PAUSED -> getString(R.string.notif_paused)
            else -> getString(R.string.notif_recording)
        }
        val text = "${formatKm(distanceMeters)} ${getString(R.string.unit_km)} · " +
            formatDuration(movingTimeMillis)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)

        // Pause/Resume toggles with state; Stop is always offered.
        if (status == TrackingStatus.PAUSED) {
            builder.addAction(
                R.drawable.ic_notif_resume,
                getString(R.string.btn_resume),
                servicePendingIntent(ACTION_RESUME),
            )
        } else {
            builder.addAction(
                R.drawable.ic_notif_pause,
                getString(R.string.btn_pause),
                servicePendingIntent(ACTION_PAUSE),
            )
        }
        builder.addAction(
            R.drawable.ic_notif_stop,
            getString(R.string.notif_stop),
            servicePendingIntent(ACTION_STOP),
        )

        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        cancelAutoSave()
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        scope.cancel()
        releaseRideReservation()
        super.onDestroy()
    }

    private fun releaseRideReservation() {
        if (ownsRideReservation) {
            ownsRideReservation = false
            DatabaseMaintenance.releaseRide()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "xx.biketracker.action.START"
        const val ACTION_PAUSE = "xx.biketracker.action.PAUSE"
        const val ACTION_RESUME = "xx.biketracker.action.RESUME"
        const val ACTION_STOP = "xx.biketracker.action.STOP"
        const val ACTION_DISCARD = "xx.biketracker.action.DISCARD"

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stopAndSave(context: Context) = send(context, ACTION_STOP)
        fun discard(context: Context) = send(context, ACTION_DISCARD)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
