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
import xx.biketracker.AUTO_PAUSE_SPEED_MPS
import xx.biketracker.DRAFT_FLUSH_EVERY_POINTS
import xx.biketracker.elevationGainBySegment
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GPS_MIN_INTERVAL_MS
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.STANDBY_GPS_INTERVAL_MS
import xx.biketracker.STANDBY_GPS_MIN_INTERVAL_MS
import xx.biketracker.STANDBY_RESUME_HOLD_MS
import xx.biketracker.STANDBY_TIMEOUT_MS
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
import xx.biketracker.map.MapSelection
import xx.biketracker.settings.resumeSpeedMps
import xx.biketracker.settings.AppSettings
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
 * long pause). A manual pause is sticky: only the Resume button ends it. A manual stop persists
 * the ride and its points, then stops itself. The long-pause auto-save instead saves the ride and
 * drops into standby: the service stays alive at a lighter GPS cadence and auto-starts a fresh ride
 * when the rider sets off again, shutting down only after a long, motionless standby.
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
    // Set when a pause breaks the segment; the next recorded fix marks the boundary explicitly so
    // a short pause still splits at display time regardless of the wall-clock gap.
    private var pendingSegmentStart = false
    private var lastTrustedFix: ValidatedLocationFix? = null
    private var lastTrustedFixElapsedRealtime = 0L
    private val points = mutableListOf<TrackPoint>()
    private val route = mutableListOf<GeoPoint>()

    private val kalman = GpsKalmanFilter()
    private var pausedAutomatically = false
    private var lowSpeedSince = 0L
    private var autoSaveJob: Job? = null
    // Standby (post-auto-save) bookkeeping: when the rider started moving again, and the
    // watchdog that shuts the service down after a long, motionless standby.
    private var movingSince = 0L
    private var standbyJob: Job? = null

    private var currentSpeedMps = 0.0
    private var altitudeMeters: Double? = null
    private var gpsAccuracyMeters: Float? = null
    private var bearingDegrees: Float? = null

    // Guards stopAndSave so a manual Stop racing with the pause auto-save (on
    // different threads) can't persist the same ride twice.
    private val stopping = AtomicBoolean(false)
    // Whether the in-flight stop ends in standby; main-thread only. A manual Stop arriving
    // while the auto-save is still persisting downgrades it to a plain stop.
    private var pendingStandby = false
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
    // True while the pending startup was launched from standby, so a failure returns to a working
    // standby request instead of tearing the service down.
    private var startupFromStandby = false
    // Bumped on every startup attempt; the draft callback checks it so a superseded attempt's late
    // completion can't advance a newer ride.
    private var startupGeneration = 0
    // Bumped on every location-update registration; the one-shot ack callbacks check it so a stale
    // request (e.g. a previous standby's late failure) can't tear down the request that replaced it.
    private var gpsRequestGeneration = 0
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
            // In standby there is no active ride: Start opens a fresh one, and Stop/Discard just
            // end standby (there is nothing to save or throw away).
            ACTION_START ->
                if (status == TrackingStatus.STANDBY) startRideFromStandby(automatic = false)
                else startTracking(startId)
            ACTION_PAUSE -> pauseTracking(automatic = false)
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> if (status == TrackingStatus.STANDBY) finishService() else stopAndSave()
            ACTION_DISCARD -> if (status == TrackingStatus.STANDBY) finishService() else stopAndDiscard()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTracking(startId: Int) {
        if (status != TrackingStatus.IDLE || startupPending) return
        AppSettings.load(this) // pick up the latest auto-pause settings, even in a fresh process
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
        startupFromStandby = false
        startupGeneration++
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
        resetRideState()
        createDraft()
        requestUpdates(
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs = GPS_INTERVAL_MS,
            minIntervalMs = GPS_MIN_INTERVAL_MS,
            onSuccess = {
                registrationReady = true
                completeStartupIfReady()
            },
            onFailure = { abortStartup() },
        )
    }

    /** Clear every per-ride accumulator so the same service instance can record a fresh ride. */
    private fun resetRideState() {
        stopping.set(false)
        kalman.reset()
        flushJobs.clear()
        scheduledFlushCount = 0
        persistenceFailed = false
        lastTrustedFix = null
        lastTrustedFixElapsedRealtime = 0L
        lastPoint = null
        lastPointElapsedRealtimeNanos = null
        pendingSegmentStart = false
        points.clear()
        route.clear()
        distanceMeters = 0.0
        movingTimeMillis = 0L
        maxSpeedMps = 0.0
        currentSpeedMps = 0.0
        altitudeMeters = null
        gpsAccuracyMeters = null
        bearingDegrees = null
        pausedAutomatically = false
        lowSpeedSince = 0L
        movingSince = 0L
        draftPersistence = null
        draftStartJob = null
        startTime = System.currentTimeMillis()
        startElapsedRealtime = SystemClock.elapsedRealtime()
    }

    /** Open a fresh draft trip for the ride now starting; [resetRideState] must run first. */
    private fun createDraft() {
        val generation = startupGeneration
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
                when {
                    // A newer startup (or a teardown) has superseded this attempt; its state is
                    // no longer ours to touch.
                    generation != startupGeneration -> {}
                    result.isSuccess -> {
                        draftReady = true
                        completeStartupIfReady()
                    }
                    startupPending -> abortStartup()
                    else -> {}
                }
            }
        }
    }

    private fun requestUpdates(
        priority: Int,
        intervalMs: Long,
        minIntervalMs: Long,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        // A newer registration supersedes this one's async ack: only the latest request's callback
        // may act, so a stale success/failure can't drive state for the request that replaced it.
        val generation = ++gpsRequestGeneration
        val ackSuccess = { if (generation == gpsRequestGeneration) onSuccess() }
        val ackFailure = { if (generation == gpsRequestGeneration) onFailure() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ackFailure()
            return
        }
        // Re-requesting with the same callback just replaces the previous request's cadence.
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnSuccessListener { ackSuccess() }
                .addOnFailureListener { ackFailure() }
        } catch (_: SecurityException) {
            ackFailure()
        } catch (_: RuntimeException) {
            ackFailure()
        }
    }

    private fun completeStartupIfReady() {
        // A manual Stop mid-startup sets stopping before the acks land; never advertise RECORDING
        // once the service is tearing down.
        if (stopping.get()) return
        if (!startupPending || !registrationReady || !draftReady) return
        startupPending = false
        status = TrackingStatus.RECORDING
        // A ride opened from History would otherwise keep hiding the live track on the Map tab.
        MapSelection.clear()
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

    /** Route a failed prerequisite to the right recovery: a cold start tears down, a standby start
     *  falls back to a working standby request. */
    private fun abortStartup() {
        if (startupFromStandby) failStandbyStartup() else failStartup()
    }

    /** A ride started from standby failed to register GPS or open its draft; the session is still
     *  valid, so discard the aborted draft and return to a working standby request rather than
     *  advertising a Recording state that has no fix stream. */
    private fun failStandbyStartup() {
        if (!startupPending) return
        startupPending = false
        startupGeneration++ // invalidate this attempt's late callbacks
        val persistence = draftPersistence
        scope.launch { withContext(NonCancellable) { persistence?.discard() } }
        enterStandby() // resets ride state and re-arms the lighter standby GPS request
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
            TrackingStatus.STANDBY -> maybeStandbyStart(fix)
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
        // This fix opens a new recording segment if a pause broke the track or an outage gapped it.
        val segmentStart = pendingSegmentStart || gapped
        pendingSegmentStart = false
        // Monotonic time since ride start; wall-clock-safe basis for the chart's time axis.
        val elapsedSinceStart = (nowElapsedMillis - startElapsedRealtime).coerceAtLeast(0L)

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
            segmentStart = segmentStart,
            elapsedMillis = elapsedSinceStart,
        )
        points += point
        // The segment flag lets the map and chart split at pause/outage boundaries; the elapsed
        // time drives the chart's monotonic time axis; the speed feeds the live speed chart.
        route += smoothed.copy(
            timeMillis = fix.wallTimeMillis,
            speedMps = fix.speedMps?.toFloat() ?: 0f,
            segmentStart = segmentStart,
            elapsedMillis = elapsedSinceStart,
        )
        lastPoint = point
        lastPointElapsedRealtimeNanos = nowElapsedNanos
        if (points.size - scheduledFlushCount >= DRAFT_FLUSH_EVERY_POINTS) flushDraft()

        updateNotification()
        fix.speedMps?.let { evaluateAutoPause(it, nowElapsedMillis) }
    }

    private fun evaluateAutoPause(speed: Double, now: Long) {
        if (!AppSettings.autoPauseEnabled.value) {
            lowSpeedSince = 0L
            return
        }
        val thresholdMps = AppSettings.autoPauseSpeedKmh.value / MPS_TO_KMH
        val holdMillis = AppSettings.autoPauseHoldSec.value * 1000L
        if (speed < thresholdMps) {
            if (lowSpeedSince == 0L) {
                lowSpeedSince = now
            } else if (now - lowSpeedSince >= holdMillis) {
                pauseTracking(automatic = true)
            }
        } else {
            lowSpeedSince = 0L
        }
    }

    // Only an automatic pause resumes by itself; a manual one waits for the button.
    private fun maybeAutoResume(speedMps: Double) {
        val resumeMps = resumeSpeedMps(AppSettings.autoPauseSpeedKmh.value)
        if (pausedAutomatically && speedMps >= resumeMps) {
            resumeTracking(automatic = true)
        }
    }

    private fun pauseTracking(automatic: Boolean) {
        if (status != TrackingStatus.RECORDING) return
        status = TrackingStatus.PAUSED
        pausedAutomatically = automatic
        lowSpeedSince = 0L
        // Break the segment so the paused gap adds neither distance nor time, and mark the next
        // recorded fix as a new segment's start.
        lastPoint = null
        lastPointElapsedRealtimeNanos = null
        pendingSegmentStart = true
        flushDraft() // checkpoint the ride at every pause
        scheduleAutoSave()
        updateNotification()
        publish()
        // The rider chose a manual pause; only the unasked-for one warrants a buzz.
        if (automatic) vibrateAutoPause()
    }

    private fun resumeTracking(automatic: Boolean = false) {
        if (status != TrackingStatus.PAUSED) return
        status = TrackingStatus.RECORDING
        pausedAutomatically = false
        cancelAutoSave()
        updateNotification()
        publish()
        // The rider triggered a manual resume on-screen; only the unasked-for one warrants a buzz.
        if (automatic) vibrateAutoResume()
    }

    // Standby: after the long-pause auto-save the ride is already stored, so movement here opens
    // a brand-new ride rather than resuming the finished one.
    private fun maybeStandbyStart(fix: ValidatedLocationFix) {
        val speed = fix.speedMps ?: return
        val startMps = resumeSpeedMps(AppSettings.autoPauseSpeedKmh.value)
        val now = fix.elapsedRealtimeNanos / 1_000_000L
        if (speed >= startMps) {
            if (movingSince == 0L) movingSince = now
            else if (now - movingSince >= STANDBY_RESUME_HOLD_MS) startRideFromStandby(automatic = true)
        } else {
            movingSince = 0L
        }
    }

    /** Keep the service alive after the auto-save, listening at a lighter GPS cadence for the
     *  rider to set off again; a long, motionless standby then shuts everything down. */
    private fun enterStandby() {
        resetRideState()
        status = TrackingStatus.STANDBY
        scheduleStandbyTimeout()
        updateNotification()
        publish()
        // Last, so a synchronous failure here (finishService) has the final say on state.
        switchToStandbyGps()
    }

    private fun startRideFromStandby(automatic: Boolean) {
        // startupPending guards against a fix arriving mid-startup and re-triggering this.
        if (status != TrackingStatus.STANDBY || startupPending) return
        cancelStandbyTimeout()
        resetRideState()
        // Gate on the same two prerequisites as a cold start: never advertise RECORDING until the
        // draft is open and the high-accuracy request is registered. The rider stays in standby
        // (its telemetry still flowing) until then; a failure falls back to a working standby.
        startupFromStandby = true
        val generation = ++startupGeneration
        startupPending = true
        registrationReady = false
        draftReady = false
        createDraft()
        requestUpdates(
            priority = Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs = GPS_INTERVAL_MS,
            minIntervalMs = GPS_MIN_INTERVAL_MS,
            onSuccess = {
                if (generation == startupGeneration) {
                    registrationReady = true
                    completeStartupIfReady()
                }
            },
            onFailure = { if (generation == startupGeneration) abortStartup() },
        )
        // Auto-start warrants the same single buzz as auto-resume; a button press does not.
        if (automatic) vibrateAutoResume()
    }

    // High accuracy even in standby: a lower priority may yield network fixes that carry no
    // speed and fail the accuracy filter, so movement would never be detected. The battery
    // saving comes from the longer interval alone.
    private fun switchToStandbyGps() = requestUpdates(
        priority = Priority.PRIORITY_HIGH_ACCURACY,
        intervalMs = STANDBY_GPS_INTERVAL_MS,
        minIntervalMs = STANDBY_GPS_MIN_INTERVAL_MS,
        onSuccess = {},
        // Standby with no location updates could never catch movement, so there is no point
        // staying up. The failure callback is async: by the time it fires a new ride may have
        // started, and that ride must not be torn down.
        onFailure = { if (status == TrackingStatus.STANDBY) finishService() },
    )

    private fun scheduleStandbyTimeout() {
        cancelStandbyTimeout()
        standbyJob = scope.launch {
            delay(STANDBY_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                if (status == TrackingStatus.STANDBY) finishService()
            }
        }
    }

    private fun cancelStandbyTimeout() {
        standbyJob?.cancel()
        standbyJob = null
    }

    /** Double buzz on auto-pause — noticeable in a pocket, unlike any on-screen hint. */
    private fun vibrateAutoPause() {
        vibrate(longArrayOf(0, 200, 150, 200))
    }

    /** Single buzz on auto-resume, so it reads as clearly different from the double auto-pause. */
    private fun vibrateAutoResume() {
        vibrate(longArrayOf(0, 200))
    }

    private fun vibrate(timings: LongArray) {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private fun scheduleAutoSave() {
        cancelAutoSave()
        autoSaveJob = scope.launch {
            delay(AppSettings.autoSaveMin.value * 60_000L)
            // Decide and stop on the main thread so this can't race with a resume or
            // recordLocation mutating status/points; a resume's cancelAutoSave() then
            // aborts here before the check.
            withContext(Dispatchers.Main) {
                if (status == TrackingStatus.PAUSED) saveAndEnterStandby()
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

    private fun stopAndSave() = stop(save = true, thenStandby = false)
    private fun stopAndDiscard() = stop(save = false, thenStandby = false)
    private fun saveAndEnterStandby() = stop(save = true, thenStandby = true)

    private fun stop(save: Boolean, thenStandby: Boolean) {
        if (!stopping.compareAndSet(false, true)) {
            // The ride is already being persisted (long-pause auto-save); honor the rider's
            // explicit Stop by finishing outright instead of dropping into standby.
            if (!thenStandby) pendingStandby = false
            return
        }
        pendingStandby = thenStandby
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
                    when {
                        result.isFailure -> handleSaveFailure()
                        pendingStandby -> enterStandby()
                        else -> finishService()
                    }
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
                    elevationGainBySegment(recorded)
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
        // A Stop during a standby-start gate ends here; drop the pending startup so a late GPS or
        // draft ack can't flip a torn-down service back to RECORDING.
        startupPending = false
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
            status == TrackingStatus.STANDBY -> getString(R.string.notif_standby)
            status == TrackingStatus.PAUSED -> getString(R.string.notif_paused)
            else -> getString(R.string.notif_recording)
        }
        val text = if (status == TrackingStatus.STANDBY) {
            getString(R.string.track_standby)
        } else {
            "${formatKm(distanceMeters)} ${getString(R.string.unit_km)} · " +
                formatDuration(movingTimeMillis)
        }

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

        // Pause/Resume toggles with state; in standby it becomes Start; Stop is always offered.
        when (status) {
            TrackingStatus.STANDBY -> builder.addAction(
                R.drawable.ic_notif_resume,
                getString(R.string.btn_start),
                servicePendingIntent(ACTION_START),
            )
            TrackingStatus.PAUSED -> builder.addAction(
                R.drawable.ic_notif_resume,
                getString(R.string.btn_resume),
                servicePendingIntent(ACTION_RESUME),
            )
            else -> builder.addAction(
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
        cancelStandbyTimeout()
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
