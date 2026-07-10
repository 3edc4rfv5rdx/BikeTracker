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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import xx.biketracker.GeoPoint
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.haversineMeters
import xx.biketracker.MainActivity
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.TrackPoint
import xx.biketracker.data.Trip
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

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
    private var distanceMeters = 0.0
    private var movingTimeMillis = 0L
    private var maxSpeedMps = 0.0
    private var lastPoint: TrackPoint? = null
    private val points = mutableListOf<TrackPoint>()
    private val route = mutableListOf<GeoPoint>()

    private var pausedAutomatically = false
    private var lowSpeedSince = 0L
    private var autoSaveJob: Job? = null

    private var currentSpeedMps = 0.0
    private var altitudeMeters: Double? = null
    private var gpsAccuracyMeters: Float? = null

    // Guards stopAndSave so a manual Stop racing with the pause auto-save (on
    // different threads) can't persist the same ride twice.
    private val stopping = AtomicBoolean(false)

    // Incremental persistence: a draft Trip row is created at start, and recorded points plus
    // running aggregates are flushed into it in batches, so a process death loses at most the
    // last unflushed batch (finalizeAbandonedTrips rescues the draft at next launch). Flush jobs
    // are chained through lastFlushJob so writes hit the database in recording order.
    private var draftTrip: Deferred<Long>? = null
    private var flushedCount = 0
    private var lastFlushJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A command other than START can reach a freshly created instance (e.g. the user taps
        // Resume just as the auto-save finished the previous service). Such an instance must
        // stop immediately: it was started via startForegroundService, and neither calling
        // startForeground nor stopSelf would crash with a foreground-timeout exception.
        if (status == TrackingStatus.IDLE && intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_PAUSE -> pauseTracking(automatic = false)
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> stopAndSave()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (status != TrackingStatus.IDLE) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()

        // In the rare case this instance is reused after a stop, allow the new ride to save.
        stopping.set(false)
        status = TrackingStatus.RECORDING
        startTime = System.currentTimeMillis()
        draftTrip = scope.async {
            AppDatabase.get(applicationContext).tripDao().insertTrip(
                Trip(
                    startTime = startTime,
                    endTime = startTime,
                    distanceMeters = 0.0,
                    movingTimeMillis = 0L,
                    maxSpeedMps = 0.0,
                    finished = false,
                )
            )
        }
        requestUpdates()
        publish()
    }

    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun onLocation(location: Location) {
        // A fix already queued on the main looper can arrive after stopAndSave; processing it
        // would re-post the removed notification and overwrite the TrackingState reset.
        if (stopping.get()) return
        currentSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else currentSpeedMps
        altitudeMeters = if (location.hasAltitude()) location.altitude else altitudeMeters
        gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null

        when (status) {
            TrackingStatus.RECORDING -> recordLocation(location)
            TrackingStatus.PAUSED -> maybeAutoResume()
            TrackingStatus.IDLE -> return
        }
        publish()
    }

    private fun recordLocation(location: Location) {
        // Reject fixes that are too imprecise to trust.
        if (location.hasAccuracy() && location.accuracy > ACCURACY_THRESHOLD_M) return

        val prev = lastPoint
        val now = location.time
        if (prev != null) {
            val dt = now - prev.time
            if (dt <= 0) return
            val segment = haversineSegment(prev, location)
            val segmentSpeed = segment / (dt / 1000.0)
            if (segmentSpeed > MAX_PLAUSIBLE_SPEED_MPS) return // GPS jump, skip

            distanceMeters += segment
            movingTimeMillis += dt
        }

        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        if (speed <= MAX_PLAUSIBLE_SPEED_MPS) maxSpeedMps = max(maxSpeedMps, speed)

        val point = TrackPoint(
            tripId = 0,
            lat = location.latitude,
            lon = location.longitude,
            time = now,
            speedMps = location.speed,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
        )
        points += point
        route += GeoPoint(location.latitude, location.longitude)
        lastPoint = point
        if (points.size - flushedCount >= DRAFT_FLUSH_EVERY_POINTS) flushDraft()

        updateNotification()
        evaluateAutoPause(speed, now)
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
    private fun maybeAutoResume() {
        if (pausedAutomatically && currentSpeedMps >= AUTO_RESUME_SPEED_MPS) {
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

    /** Persist the points recorded since the last flush plus the running aggregates.
     *  Runs on the main thread; the write itself is chained onto the previous flush. */
    private fun flushDraft() {
        val draft = draftTrip ?: return
        if (points.size == flushedCount) return
        val slice = points.subList(flushedCount, points.size).toList()
        flushedCount = points.size
        val tripStart = startTime
        val tripEnd = slice.last().time
        val tripDistance = distanceMeters
        val tripMovingTime = movingTimeMillis
        val tripMaxSpeed = maxSpeedMps
        val prev = lastFlushJob
        lastFlushJob = scope.launch {
            prev?.join()
            val id = draft.await()
            val db = AppDatabase.get(applicationContext)
            db.withTransaction {
                db.tripDao().insertPoints(slice.map { it.copy(tripId = id) })
                db.tripDao().updateTrip(
                    Trip(
                        id = id,
                        startTime = tripStart,
                        endTime = tripEnd,
                        distanceMeters = tripDistance,
                        movingTimeMillis = tripMovingTime,
                        maxSpeedMps = tripMaxSpeed,
                        finished = false,
                    )
                )
            }
        }
    }

    private fun stopAndSave() {
        if (!stopping.compareAndSet(false, true)) return
        cancelAutoSave()
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        val recorded = points.toList()
        val unflushed = recorded.subList(flushedCount, recorded.size)
        flushedCount = recorded.size
        val tripStart = startTime
        val tripEnd = recorded.lastOrNull()?.time ?: System.currentTimeMillis()
        val tripDistance = distanceMeters
        val tripMovingTime = movingTimeMillis
        val tripMaxSpeed = maxSpeedMps
        // Point reductions computed once here, mirroring maxSpeed, so the detail never reloads points.
        val tripAvgGps = recorded.map { it.speedMps.toDouble() }.average()
        val altitudes = recorded.map { it.altitudeMeters }
        val tripElevationGain = if (altitudes.any { it != null }) elevationGainMeters(altitudes) else null

        val draft = draftTrip
        val prevFlush = lastFlushJob
        // Finalize under NonCancellable and finish only after the commit, so the stopSelf()
        // below (which triggers onDestroy → scope.cancel()) cannot abort the write mid-flight.
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val id = draft?.await()
                    if (id != null) {
                        prevFlush?.join() // pending flushes must not overtake the final write
                        val db = AppDatabase.get(applicationContext)
                        db.withTransaction {
                            if (recorded.size >= 2 && tripDistance > 0) {
                                db.tripDao().insertPoints(unflushed.map { it.copy(tripId = id) })
                                db.tripDao().updateTrip(
                                    Trip(
                                        id = id,
                                        startTime = tripStart,
                                        endTime = tripEnd,
                                        distanceMeters = tripDistance,
                                        movingTimeMillis = tripMovingTime,
                                        maxSpeedMps = tripMaxSpeed,
                                        avgGpsSpeedMps = tripAvgGps,
                                        elevationGainMeters = tripElevationGain,
                                        finished = true,
                                    )
                                )
                            } else {
                                db.tripDao().deleteTripById(id) // cascade removes any flushed points
                            }
                        }
                    }
                } finally {
                    // Finish on the main thread: status and TrackingState belong to it, so the
                    // reset serializes with onLocation instead of racing it from Default.
                    withContext(Dispatchers.Main) { finishService() }
                }
            }
        }
    }

    private fun finishService() {
        TrackingState.reset()
        status = TrackingStatus.IDLE
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
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
                startTime = startTime,
                updatedAtWall = System.currentTimeMillis(),
                route = route.toList(),
            )
        )
    }

    private fun haversineSegment(prev: TrackPoint, cur: Location): Double =
        haversineMeters(prev.lat, prev.lon, cur.latitude, cur.longitude)

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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = when (status) {
            TrackingStatus.PAUSED -> getString(R.string.notif_paused)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
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
        super.onDestroy()
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

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stopAndSave(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
