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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xx.biketracker.ACCURACY_LIMIT_M
import xx.biketracker.AppendOnlyList
import xx.biketracker.AUTO_PAUSE_SPEED_MPS
import xx.biketracker.AUTO_RESUME_HOLD_MS
import xx.biketracker.AUTO_SAVE_GPS_WAIT_MS
import xx.biketracker.DRAFT_FLUSH_EVERY_POINTS
import xx.biketracker.FIX_REANCHOR_MS
import xx.biketracker.elevationGainBySegment
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GPS_MIN_INTERVAL_MS
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import xx.biketracker.isRecordingGap
import xx.biketracker.LEFT_ANCHOR_DISTANCE_M
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.isRideWorthSaving
import xx.biketracker.RECORDING_OUTAGE_MS
import xx.biketracker.MAX_SPEED_WINDOW_MS
import xx.biketracker.STANDBY_DEPARTURE_HOLD_MS
import xx.biketracker.STANDBY_GPS_INTERVAL_MS
import xx.biketracker.STANDBY_GPS_MIN_INTERVAL_MS
import xx.biketracker.STANDBY_RESUME_HOLD_MS
import xx.biketracker.STANDBY_TIMEOUT_MS
import xx.biketracker.haversineMeters
import xx.biketracker.MainActivity
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.averageObservedSpeed
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

/**
 * The fastest the ride ever actually got somewhere.
 *
 * A maximum read off a single step is a maximum read off a single fix's error: the jump filter
 * admits any step under [MAX_PLAUSIBLE_SPEED_MPS], and one fix landing just inside that would leave
 * a bicycle credited with 100 km/h. The Kalman filter damps such a fix hard at a standstill, where
 * its process noise is low — but at riding speed the noise dominates, the gain approaches 1, and the
 * jump passes almost intact. A speed therefore only counts once the ride held it for
 * [MAX_SPEED_WINDOW_MS].
 *
 * It is measured as the ground put between the window's ends, not the track walked between them: an
 * excursion that goes out and comes straight back covers plenty of track and no ground, which is
 * exactly the shape of a noisy fix. Over the seconds of a window the two barely differ for real
 * riding, so a genuine sprint is reported in full.
 *
 * The window is fed the same smoothed positions the distance is built from and knows nothing of the
 * speed the receiver reports, so a ride recorded under a signal jammed hard enough to blank the
 * speed entirely still gets a maximum out of the ground it covered.
 *
 * [reset] wherever the recording breaks: across a pause or an outage the window's two ends are not
 * connected by anything the tracker saw.
 */
internal class SpeedWindow(private val windowMillis: Long = MAX_SPEED_WINDOW_MS) {

    private class Sample(val timeMillis: Long, val lat: Double, val lon: Double)

    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    /**
     * Feed one recorded position, on a monotonic clock. Returns the speed the ride demonstrably
     * held over the window ending here, or null while the recording does not span one yet.
     */
    fun observe(timeMillis: Long, lat: Double, lon: Double): Double? {
        samples.addLast(Sample(timeMillis, lat, lon))
        // Keep the tightest window that still spans the minimum; a wider one only dilutes a peak.
        while (samples.size >= 2 && samples[1].timeMillis <= timeMillis - windowMillis) {
            samples.removeFirst()
        }
        val from = samples.first()
        val spanMillis = timeMillis - from.timeMillis
        if (spanMillis < windowMillis) return null
        return haversineMeters(from.lat, from.lon, lat, lon) / (spanMillis / 1000.0)
    }
}

/**
 * Whether the stretch between two consecutive fixes broke the run of observations a hold timer
 * measures — everything recorded before it then describes a stretch the tracker did not see.
 *
 * [windowMs] is what counts as a break for the hold in question, and the two holds want very
 * different answers. Auto-pause is the strict case: the first fix back after any silence routinely
 * reports a speed of 0, so a low-speed hold measured from before the silence would pause a moving
 * bike, and anything past [GPS_STALE_MS] must drop the streak. The standby holds are the opposite
 * case — they have to remain *reachable*. Where the signal is jammed, fixes tens of seconds apart
 * are the cadence rather than an interruption, so only a silence no sampling interval accounts for
 * ([RECORDING_OUTAGE_MS]) discards what came before it.
 */
internal fun brokeObservationRun(
    previous: ValidatedLocationFix?,
    fix: ValidatedLocationFix,
    windowMs: Long,
): Boolean =
    previous == null || fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos > windowMs * 1_000_000L

/**
 * A condition that must hold across fixes rather than on the strength of one, for the length of
 * [holdMillis]. The streak is judged from the second qualifying observation on: the fix that opens
 * it only records when it began, so a lone fix can never satisfy the hold however long it has been
 * since the last one. That is what keeps a single spoofed jump — the everyday shape of a jammed
 * signal — from opening a ride the rider never started.
 *
 * Time is measured in the caller's monotonic clock; a hold is fed only observations from one
 * unbroken run (see [brokeObservationRun]) and [reset] when that run breaks.
 */
internal class ObservationHold(private val holdMillis: Long) {
    // Null rather than a zero sentinel: the caller's clock is free to pass 0 as a real instant.
    private var since: Long? = null

    fun reset() {
        since = null
    }

    /** Feed one observation; true once the condition has held long enough across at least two. */
    fun observe(nowMillis: Long, qualifies: Boolean): Boolean {
        if (!qualifies) {
            since = null
            return false
        }
        val start = since
        if (start == null) {
            since = nowMillis
            return false
        }
        return nowMillis - start >= holdMillis
    }
}

/**
 * Whether the elapsed auto-save may close the ride now: either a fix has come back to confirm the
 * standstill it is about to record, or the wait for one has run out (see [AUTO_SAVE_GPS_WAIT_MS],
 * which also explains why giving up costs little). [waitedMillis] is measured from the moment the
 * configured auto-save period elapsed, on the monotonic clock, so time asleep counts.
 */
internal fun autoSaveMayClose(hasFreshFix: Boolean, waitedMillis: Long, limitMillis: Long): Boolean =
    hasFreshFix || waitedMillis >= limitMillis

/** What an elapsed-realtime deadline needs its caller to do at [nowMillis]. */
internal sealed interface DeadlineDecision {
    data object Disabled : DeadlineDecision
    data class Wait(val millis: Long) : DeadlineDecision
    data object Expired : DeadlineDecision
}

/** Multiply a user-controlled duration without ever wrapping into a negative delay. */
internal fun saturatingDuration(value: Long, unitMillis: Long): Long = when {
    value <= 0L || unitMillis <= 0L -> 0L
    value > Long.MAX_VALUE / unitMillis -> Long.MAX_VALUE
    else -> value * unitMillis
}

private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
    if (nowMillis >= startMillis) nowMillis - startMillis else 0L

/** Decide the live auto-save deadline from the original pause instant. */
internal fun autoSaveDeadlineDecision(
    pauseStartedMillis: Long,
    nowMillis: Long,
    autoSaveMinutes: Long,
    hasFreshFix: Boolean,
    gpsWaitMillis: Long,
): DeadlineDecision {
    if (autoSaveMinutes <= 0L) return DeadlineDecision.Disabled
    val timeoutMillis = saturatingDuration(autoSaveMinutes, 60_000L)
    val elapsed = elapsedSince(pauseStartedMillis, nowMillis)
    if (elapsed < timeoutMillis) return DeadlineDecision.Wait(timeoutMillis - elapsed)
    val waitedForGps = elapsed - timeoutMillis
    return if (autoSaveMayClose(hasFreshFix, waitedForGps, gpsWaitMillis)) {
        DeadlineDecision.Expired
    } else {
        DeadlineDecision.Wait(gpsWaitMillis - waitedForGps)
    }
}

/** Decide a fixed timeout from its elapsed-realtime start, including time spent asleep. */
internal fun elapsedDeadlineDecision(
    startedMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long,
): DeadlineDecision {
    val boundedTimeout = timeoutMillis.coerceAtLeast(0L)
    val elapsed = elapsedSince(startedMillis, nowMillis)
    return if (elapsed >= boundedTimeout) DeadlineDecision.Expired
    else DeadlineDecision.Wait(boundedTimeout - elapsed)
}

/** Reject a timer result made stale by resume, stop, or a later pause. */
internal fun autoSaveDeadlineIsCurrent(
    expectedPauseStartedMillis: Long,
    activePauseStartedMillis: Long?,
    isPaused: Boolean,
    isStopping: Boolean,
): Boolean =
    isPaused && !isStopping && activePauseStartedMillis == expectedPauseStartedMillis

/** What a command delivered while a ride is still opening does to the service. */
internal enum class PendingStartupOutcome {
    /** Nothing but the startup is holding the service up: tear it down. */
    ABORT,
    /** The rider ended a standby session while it was opening a ride: drop both. */
    FINISH,
    /** The startup came from a live standby session that outlives it: go back to standby. */
    BACK_TO_STANDBY,
    /** Not about the startup at all; let the command be handled normally. */
    HANDLE,
}

/**
 * Route a command that arrives while a startup is still pending. A cold startup is the only reason
 * that service instance exists, so anything but START ends it. A startup opened from standby is
 * different: the service is already running, already in the foreground and already holding the ride
 * reservation, so only the rider ending the session may take it down — everything else falls back
 * to the standby it came from.
 */
internal fun pendingStartupOutcome(action: String?, fromStandby: Boolean): PendingStartupOutcome =
    when {
        action == TrackingService.ACTION_START -> PendingStartupOutcome.HANDLE
        !fromStandby -> PendingStartupOutcome.ABORT
        action == TrackingService.ACTION_STOP || action == TrackingService.ACTION_DISCARD ->
            PendingStartupOutcome.FINISH
        else -> PendingStartupOutcome.BACK_TO_STANDBY
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

internal enum class SpeedObservationSource { RECEIVER, COORDINATES }

/** A speed that is safe to display and feed into state transitions. */
internal data class SpeedObservation(val metersPerSecond: Double, val source: SpeedObservationSource)

/**
 * Prefer receiver speed. When it is absent, derive a conservative speed only between consecutive
 * accepted fixes in one fresh observation run. Removing both fixes' uncertainty radii prevents
 * ordinary stationary jitter from masquerading as movement; the anchor/hold rules independently
 * guard against mistaking an uncertain zero for proof of a stop.
 */
internal fun speedObservation(
    previous: ValidatedLocationFix?,
    fix: ValidatedLocationFix,
    sameObservationRun: Boolean,
): SpeedObservation? {
    fix.speedMps?.let { return SpeedObservation(it, SpeedObservationSource.RECEIVER) }
    if (previous == null || !sameObservationRun) return null
    val dtMillis = elapsedMillisBetween(previous.elapsedRealtimeNanos, fix.elapsedRealtimeNanos)
        ?: return null
    val distance = haversineMeters(previous.lat, previous.lon, fix.lat, fix.lon)
    val uncertainty = previous.accuracyMeters.toDouble() + fix.accuracyMeters
    val speed = (distance - uncertainty).coerceAtLeast(0.0) / (dtMillis / 1000.0)
    return speed.takeIf { it.isFinite() && it <= MAX_PLAUSIBLE_SPEED_MPS }
        ?.let { SpeedObservation(it, SpeedObservationSource.COORDINATES) }
}

/** The verdict on a fix: sound and consistent, unusable, or sound but implausibly far from the
 *  reference it was checked against (see [shouldReanchor]). */
internal sealed interface FixValidation {
    data class Accepted(val fix: ValidatedLocationFix) : FixValidation
    data class Jumped(val fix: ValidatedLocationFix) : FixValidation
    data object Rejected : FixValidation
}

/** Validate a fix completely before it can mutate tracking state. */
internal fun validateLocationFix(
    candidate: LocationFixCandidate,
    previous: ValidatedLocationFix?,
): FixValidation {
    if (!candidate.lat.isFinite() || candidate.lat !in -90.0..90.0) return FixValidation.Rejected
    if (!candidate.lon.isFinite() || candidate.lon !in -180.0..180.0) return FixValidation.Rejected
    if (candidate.wallTimeMillis <= 0 || candidate.elapsedRealtimeNanos <= 0) return FixValidation.Rejected

    val accuracy = candidate.accuracyMeters ?: return FixValidation.Rejected
    if (!accuracy.isFinite() || accuracy < 0f || accuracy > ACCURACY_LIMIT_M) return FixValidation.Rejected

    val speed = candidate.speedMps
    if (speed != null && (!speed.isFinite() || speed < 0.0 || speed > MAX_PLAUSIBLE_SPEED_MPS)) {
        return FixValidation.Rejected
    }
    if (candidate.altitudeMeters?.isFinite() == false) return FixValidation.Rejected
    if (candidate.bearingDegrees?.let { !it.isFinite() || it < 0f || it >= 360f } == true) {
        return FixValidation.Rejected
    }

    val fix = ValidatedLocationFix(
        lat = candidate.lat,
        lon = candidate.lon,
        wallTimeMillis = candidate.wallTimeMillis,
        elapsedRealtimeNanos = candidate.elapsedRealtimeNanos,
        accuracyMeters = accuracy,
        speedMps = speed,
        altitudeMeters = candidate.altitudeMeters,
        bearingDegrees = candidate.bearingDegrees,
    )

    if (previous != null) {
        // Duplicate or out-of-order provider data says nothing about the reference's validity,
        // so it is dropped outright rather than offered as a re-anchor.
        val dtMillis = elapsedMillisBetween(
            previousNanos = previous.elapsedRealtimeNanos,
            currentNanos = candidate.elapsedRealtimeNanos,
        ) ?: return FixValidation.Rejected
        val coordinateSpeed = haversineMeters(
            previous.lat,
            previous.lon,
            candidate.lat,
            candidate.lon,
        ) / (dtMillis / 1000.0)
        if (!coordinateSpeed.isFinite()) return FixValidation.Rejected
        if (coordinateSpeed > MAX_PLAUSIBLE_SPEED_MPS) return FixValidation.Jumped(fix)
    }

    return FixValidation.Accepted(fix)
}

/**
 * Whether [fix] sits far enough from [anchor] — the spot where the rider was last seen standing —
 * that they must have left it, whatever speed the fixes report (see [LEFT_ANCHOR_DISTANCE_M]).
 * Both fixes' error circles are added to the threshold, so a pair of vague positions can't fake
 * the departure between them.
 */
internal fun hasLeftAnchor(anchor: ValidatedLocationFix?, fix: ValidatedLocationFix): Boolean {
    if (anchor == null) return false
    val threshold = max(
        LEFT_ANCHOR_DISTANCE_M,
        (anchor.accuracyMeters + fix.accuracyMeters).toDouble(),
    )
    return haversineMeters(anchor.lat, anchor.lon, fix.lat, fix.lon) > threshold
}

/**
 * Whether a [FixValidation.Jumped] fix should be trusted over the reference it disagrees with.
 * One of the two is wrong, and the reference is the one with nothing to show for itself: it has
 * gone [FIX_REANCHOR_MS] without a single accepted successor. Staying with it means recording
 * nothing at all (see the constant), so the tracker takes the new fix and opens a fresh segment.
 */
internal fun shouldReanchor(previous: ValidatedLocationFix, fix: ValidatedLocationFix): Boolean =
    fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos >= FIX_REANCHOR_MS * 1_000_000L

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
    // Chunked, so publishing the track to the map on every fix costs a chunk and not the ride.
    private val route = AppendOnlyList<GeoPoint>()

    private val kalman = GpsKalmanFilter()
    private val speedWindow = SpeedWindow()
    private var pausedAutomatically = false
    private var lowSpeedSince = 0L
    // Where the low-speed streak began; a streak that covers ground is not a standstill.
    private var lowSpeedAnchor: ValidatedLocationFix? = null
    // Where the rider was last seen standing (the pause / standby position). Movement away from it
    // resumes the ride even when the reported speeds say otherwise; see [hasLeftAnchor].
    private var standstillAnchor: ValidatedLocationFix? = null
    private val resumeMovementHold = ObservationHold(AUTO_RESUME_HOLD_MS)
    private val resumeDepartureHold = ObservationHold(AUTO_RESUME_HOLD_MS)
    private var autoSaveJob: Job? = null
    private var pauseStartedElapsedRealtime: Long? = null
    // Standby (post-auto-save) bookkeeping: the two holds that decide the rider has set off again —
    // by speed, and by having left the anchor — and the watchdog that shuts the service down after
    // a long, motionless standby.
    private val movementHold = ObservationHold(STANDBY_RESUME_HOLD_MS)
    private val departureHold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
    private var standbyJob: Job? = null
    private var standbyStartedElapsedRealtime: Long? = null

    private var currentSpeedMps: Double? = null
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
    // One flush at a time, each waiting on the one before it: the writer reconciles a checkpoint
    // against the durable row it finds, so two of them arriving out of order would have the older
    // one report a save failure for a ride that is in fact fully stored.
    private var flushJob: Job? = null
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
        // Every delivery, not only a START: a startup is torn down with stopSelfResult, which stops
        // the service only for the id of the most recent delivery. Recording the id here is what
        // keeps an aborted startup from leaving a stopped-but-alive service behind.
        activeStartId = startId
        if (startupPending) {
            val handled = when (pendingStartupOutcome(intent?.action, startupFromStandby)) {
                // A command other than START can reach a freshly created instance (e.g. the user
                // taps Resume just as the auto-save finished the previous service). Such an
                // instance must stop immediately: it was started via startForegroundService, and
                // neither calling startForeground nor stopSelf would crash with a
                // foreground-timeout exception.
                PendingStartupOutcome.ABORT -> { failStartup(); true }
                PendingStartupOutcome.FINISH -> { cancelPendingStartup(); finishService(); true }
                PendingStartupOutcome.BACK_TO_STANDBY -> { failStandbyStartup(); true }
                PendingStartupOutcome.HANDLE -> false
            }
            if (handled) return START_NOT_STICKY
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
                else startTracking()
            ACTION_PAUSE -> pauseTracking(automatic = false)
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> if (status == TrackingStatus.STANDBY) finishService() else stopAndSave()
            ACTION_DISCARD -> if (status == TrackingStatus.STANDBY) finishService() else stopAndDiscard()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (status != TrackingStatus.IDLE || startupPending) return
        AppSettings.load(this) // pick up the latest auto-pause settings, even in a fresh process
        // Each of these refusals used to end in a bare stopSelf(): the button simply did nothing,
        // with no snackbar, no notification and no state change to explain it.
        if (DatabaseRestoreCoordinator.state.value == RestoreOperationState.Running) {
            TrackingState.publishStartupFailure(StartupFailureReason.DATABASE_BUSY)
            stopSelf()
            return
        }
        if (!DatabaseMaintenance.reserveRide()) {
            TrackingState.publishStartupFailure(StartupFailureReason.DATABASE_BUSY)
            stopSelf()
            return
        }
        ownsRideReservation = true
        if (!hasLocationPermission()) {
            releaseRideReservation()
            TrackingState.publishStartupFailure(StartupFailureReason.NO_PERMISSION)
            stopSelf()
            return
        }
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startupFromStandby = false
        startupGeneration++
        startupPending = true
        registrationReady = false
        draftReady = false
        // In the rare case this instance is reused after a stop, allow the new ride to save. It
        // runs before the foreground notification so that it also drops the previous ride's draft
        // handle: a startup failing below erases "its" draft, and that must never be a stored ride.
        resetRideState()
        TrackingState.publish(TrackingSnapshot())
        try {
            startForegroundNotification()
            foregroundStarted = true
        } catch (_: RuntimeException) {
            failStartup()
            return
        }

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
        speedWindow.reset()
        flushJob = null
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
        currentSpeedMps = null
        altitudeMeters = null
        gpsAccuracyMeters = null
        bearingDegrees = null
        pausedAutomatically = false
        clearLowSpeedStreak()
        movementHold.reset()
        departureHold.reset()
        resumeMovementHold.reset()
        resumeDepartureHold.reset()
        standstillAnchor = null
        pauseStartedElapsedRealtime = null
        standbyStartedElapsedRealtime = null
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

    /** Abandon a cold startup: stop listening, erase the draft it opened, tell the rider and stop
     *  the service. */
    private fun failStartup() {
        if (!startupPending) return
        stopping.set(true)
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        cancelPendingStartup(then = ::endFailedStartup)
    }

    /** The visible half of a failed startup: nothing is recording, the reservation the draft held
     *  is free again, and the service stops for the latest command it was given. */
    private fun endFailedStartup() {
        TrackingState.publishStartupFailure(StartupFailureReason.FAILED)
        status = TrackingStatus.IDLE
        releaseRideReservation()
        if (foregroundStarted) {
            foregroundStarted = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(activeStartId)
    }

    /**
     * Drop a pending startup without deciding what comes after it: the draft it opened is erased
     * and its late GPS and draft acks are invalidated, leaving the caller free to choose between
     * standby and shutdown. [then] runs on the main thread once the draft is gone — at once when
     * there was none, which is what lets a startup that failed inside [startForegroundNotification]
     * stop the service within the foreground-start timeout rather than after a database write that
     * a backup or restore may be holding up. It is skipped if a newer startup has taken the service
     * over by then, whose state is not this one's to tear down.
     *
     * The erase itself outlives this call; should the service die first, the empty draft left
     * behind is exactly what the launch-time recovery pass exists to clear.
     */
    private fun cancelPendingStartup(then: () -> Unit = {}) {
        startupPending = false
        val generation = ++startupGeneration
        val persistence = draftPersistence
        draftPersistence = null
        if (persistence == null) {
            then()
            return
        }
        scope.launch {
            withContext(NonCancellable) {
                persistence.discard()
                withContext(Dispatchers.Main) { if (generation == startupGeneration) then() }
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
        cancelPendingStartup()
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
        val previous = lastTrustedFix
        var reanchored = false
        val fix = when (val validation = validateLocationFix(candidate, previous)) {
            is FixValidation.Accepted -> validation.fix
            // A fix that contradicts a reference which has itself gone silent for too long wins:
            // the alternative is rejecting every genuine fix for hours (see shouldReanchor).
            is FixValidation.Jumped -> {
                if (previous == null || !shouldReanchor(previous, validation.fix)) return
                reanchor()
                reanchored = true
                validation.fix
            }
            FixValidation.Rejected -> return
        }
        // Every hold below measures an unbroken run of observations, but each wants its own idea of
        // what breaks that run; see [brokeObservationRun]. Auto-pause errs towards forgetting, the
        // standby holds towards staying reachable on a stream that is merely slow.
        val speedRunBroke = reanchored || brokeObservationRun(previous, fix, GPS_STALE_MS)
        if (speedRunBroke) {
            clearLowSpeedStreak()
            resumeMovementHold.reset()
            resumeDepartureHold.reset()
        }
        if (brokeObservationRun(previous, fix, RECORDING_OUTAGE_MS)) {
            movementHold.reset()
            departureHold.reset()
        }
        val observedSpeed = speedObservation(previous, fix, sameObservationRun = !speedRunBroke)
        lastTrustedFix = fix
        lastTrustedFixElapsedRealtime = fix.elapsedRealtimeNanos / 1_000_000L

        // Coroutine/Handler delays need not advance in deep sleep. A fix is a wakeup opportunity
        // to enforce the elapsed-realtime standby deadline before it can open another ride.
        if (status == TrackingStatus.STANDBY && standbyHasExpired()) {
            finishService()
            return
        }

        // Missing receiver speed is either replaced by a trustworthy coordinate observation or
        // shown as unknown; it never inherits the preceding fix's value.
        currentSpeedMps = observedSpeed?.metersPerSecond
        fix.altitudeMeters?.let { altitudeMeters = it }
        gpsAccuracyMeters = fix.accuracyMeters
        // Bearing is only trustworthy while moving; keep the last heading when the fix omits it,
        // so the puck doesn't spin to north at a standstill.
        if (fix.bearingDegrees != null &&
            observedSpeed != null &&
            observedSpeed.metersPerSecond >= AUTO_PAUSE_SPEED_MPS
        ) {
            bearingDegrees = fix.bearingDegrees
        }

        when (status) {
            TrackingStatus.RECORDING -> recordLocation(fix, observedSpeed)
            TrackingStatus.PAUSED -> maybeAutoResume(fix, observedSpeed)
            TrackingStatus.STANDBY -> maybeStandbyStart(fix, observedSpeed)
            TrackingStatus.IDLE -> return
        }
        // Re-evaluate elapsed deadlines after the fix has had its chance to resume/start. For a
        // paused ride, a fresh stationary fix can settle the bounded GPS confirmation immediately.
        if (status == TrackingStatus.PAUSED) rearmAutoSave()
        if (status == TrackingStatus.STANDBY) rearmStandbyTimeout()
        publish()
    }

    /** Drop everything derived from a reference the tracker has just disowned: the filter estimate
     *  and the track's continuity. The next recorded fix opens a new segment, so the leap to the
     *  new anchor is never drawn as a line nor counted as distance. */
    private fun reanchor() {
        kalman.reset()
        lastPoint = null
        lastPointElapsedRealtimeNanos = null
        pendingSegmentStart = true
    }

    private fun recordLocation(fix: ValidatedLocationFix, observedSpeed: SpeedObservation?) {
        val prev = lastPoint
        val nowElapsedNanos = fix.elapsedRealtimeNanos
        val nowElapsedMillis = nowElapsedNanos / 1_000_000L
        var dt = 0L
        if (prev != null) {
            val prevElapsed = lastPointElapsedRealtimeNanos ?: return
            dt = elapsedMillisBetween(prevElapsed, nowElapsedNanos) ?: return
        }
        // Monotonic time since ride start; wall-clock-safe basis for the chart's time axis.
        val elapsedSinceStart = (nowElapsedMillis - startElapsedRealtime).coerceAtLeast(0L)

        // Kalman-smooth the fix; the track and the distance both build on filtered points,
        // so standstill jitter neither paints zigzags nor inflates the total.
        val smoothed = kalman.filter(
            rawLat = fix.lat,
            rawLon = fix.lon,
            accuracyM = fix.accuracyMeters,
            timeMs = nowElapsedMillis,
            speedMps = observedSpeed?.metersPerSecond ?: 0.0,
        )
        // Whether the recording carried on between the two fixes, judged on the step rather than
        // the interval alone: fixes tens of seconds apart are routine where the signal is jammed,
        // and the ride they describe is real. A true outage (tunnel, indoors) produces no fixes at
        // all, and its stretch must add neither distance nor moving time.
        val stepMeters = if (prev == null) 0.0 else haversineMeters(prev.lat, prev.lon, smoothed.lat, smoothed.lon)
        val gapped = prev != null && isRecordingGap(dt, stepMeters)
        // This fix opens a new recording segment if a pause broke the track or an outage gapped it.
        val segmentStart = pendingSegmentStart || gapped
        pendingSegmentStart = false
        // A coordinate estimate may not cross the boundary that this point opens. Receiver speed
        // belongs to the fix itself and remains valid on either side of a pause/outage.
        val recordedSpeed = observedSpeed?.takeUnless {
            it.source == SpeedObservationSource.COORDINATES && (prev == null || gapped)
        }

        if (prev != null && !gapped) {
            distanceMeters += stepMeters
            movingTimeMillis += dt
        } else {
            // Nothing the tracker saw connects this fix to the last one, so no window may span the
            // two: the ground between them was never ridden under observation.
            speedWindow.reset()
        }
        speedWindow.observe(nowElapsedMillis, smoothed.lat, smoothed.lon)?.let {
            maxSpeedMps = max(maxSpeedMps, it)
        }

        val point = TrackPoint(
            tripId = 0,
            lat = smoothed.lat,
            lon = smoothed.lon,
            time = fix.wallTimeMillis,
            speedMps = recordedSpeed?.metersPerSecond?.toFloat(),
            altitudeMeters = fix.altitudeMeters,
            segmentStart = segmentStart,
            elapsedMillis = elapsedSinceStart,
        )
        points += point
        // The segment flag lets the map and chart split at pause/outage boundaries; the elapsed
        // time drives the chart's monotonic time axis; the speed feeds the live speed chart.
        route.add(
            smoothed.copy(
                timeMillis = fix.wallTimeMillis,
                speedMps = recordedSpeed?.metersPerSecond?.toFloat() ?: 0f,
                segmentStart = segmentStart,
                elapsedMillis = elapsedSinceStart,
            )
        )
        lastPoint = point
        lastPointElapsedRealtimeNanos = nowElapsedNanos
        if (points.size - scheduledFlushCount >= DRAFT_FLUSH_EVERY_POINTS) flushDraft()

        updateNotification()
        recordedSpeed?.let { evaluateAutoPause(fix, it.metersPerSecond, nowElapsedMillis) }
    }

    private fun evaluateAutoPause(fix: ValidatedLocationFix, speed: Double, now: Long) {
        if (!AppSettings.autoPauseEnabled.value) {
            clearLowSpeedStreak()
            return
        }
        val thresholdMps = AppSettings.autoPauseSpeedKmh.value / MPS_TO_KMH
        val holdMillis = AppSettings.autoPauseHoldSec.value * 1000L
        if (speed >= thresholdMps) {
            clearLowSpeedStreak()
            return
        }
        // A hold that covers real ground is not a standstill however slow the fixes read: a jammed
        // receiver reports 0 for a moving bike, and the distance is what gives it away. Restarting
        // the streak from here also keeps a rescued ride from flapping straight back into a pause.
        if (lowSpeedSince == 0L || hasLeftAnchor(lowSpeedAnchor, fix)) {
            lowSpeedSince = now
            lowSpeedAnchor = fix
            return
        }
        if (now - lowSpeedSince >= holdMillis) pauseTracking(automatic = true)
    }

    private fun clearLowSpeedStreak() {
        lowSpeedSince = 0L
        lowSpeedAnchor = null
    }

    // Only an automatic pause resumes by itself; a manual one waits for the button.
    private fun maybeAutoResume(fix: ValidatedLocationFix, observedSpeed: SpeedObservation?) {
        if (!pausedAutomatically) return
        val now = fix.elapsedRealtimeNanos / 1_000_000L
        val resumeMps = resumeSpeedMps(AppSettings.autoPauseSpeedKmh.value)
        val movingBySpeed = resumeMovementHold.observe(
            now,
            observedSpeed?.metersPerSecond?.let { it >= resumeMps } == true,
        )
        // Leaving the spot counts as movement even when the fix claims otherwise: that is what
        // rescues a ride from a pause the jamming caused rather than the rider. Both paths must
        // hold across fixes; a lone noisy step cannot resume a ride.
        val movingByDeparture = resumeDepartureHold.observe(now, hasLeftAnchor(standstillAnchor, fix))
        if (movingBySpeed || movingByDeparture) resumeTracking(automatic = true)
    }

    private fun pauseTracking(automatic: Boolean) {
        if (status != TrackingStatus.RECORDING) return
        status = TrackingStatus.PAUSED
        pausedAutomatically = automatic
        lowSpeedSince = 0L
        standstillAnchor = lastTrustedFix
        resumeMovementHold.reset()
        resumeDepartureHold.reset()
        pauseStartedElapsedRealtime = SystemClock.elapsedRealtime()
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
        standstillAnchor = null
        resumeMovementHold.reset()
        resumeDepartureHold.reset()
        cancelAutoSave()
        updateNotification()
        publish()
        // The rider triggered a manual resume on-screen; only the unasked-for one warrants a buzz.
        if (automatic) vibrateAutoResume()
    }

    // Standby: after the long-pause auto-save the ride is already stored, so movement here opens
    // a brand-new ride rather than resuming the finished one.
    private fun maybeStandbyStart(fix: ValidatedLocationFix, observedSpeed: SpeedObservation?) {
        val now = fix.elapsedRealtimeNanos / 1_000_000L
        // Distance from the spot is the only proof of a trip a jammed receiver leaves, but a single
        // fix away from it proves nothing — that is exactly what a spoofed jump looks like, and it
        // would open a ride the rider never started. The departure has to hold across fixes.
        if (departureHold.observe(now, hasLeftAnchor(standstillAnchor, fix))) {
            startRideFromStandby(automatic = true)
            return
        }
        val startMps = resumeSpeedMps(AppSettings.autoPauseSpeedKmh.value)
        if (movementHold.observe(
                now,
                observedSpeed?.metersPerSecond?.let { it >= startMps } == true,
            )
        ) {
            startRideFromStandby(automatic = true)
        }
    }

    /** Keep the service alive after the auto-save, listening at a lighter GPS cadence for the
     *  rider to set off again; a long, motionless standby then shuts everything down. */
    private fun enterStandby() {
        // Survives the reset below: the ride is over, but where it ended is what tells the tracker
        // the rider has set off again.
        val anchor = lastTrustedFix
        resetRideState()
        standstillAnchor = anchor
        status = TrackingStatus.STANDBY
        standbyStartedElapsedRealtime = SystemClock.elapsedRealtime()
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
        val started = standbyStartedElapsedRealtime ?: return
        standbyJob = scope.launch(Dispatchers.Main) {
            while (status == TrackingStatus.STANDBY) {
                when (val decision = elapsedDeadlineDecision(
                    startedMillis = started,
                    nowMillis = SystemClock.elapsedRealtime(),
                    timeoutMillis = STANDBY_TIMEOUT_MS,
                )) {
                    DeadlineDecision.Disabled -> return@launch
                    DeadlineDecision.Expired -> {
                        finishService()
                        return@launch
                    }
                    is DeadlineDecision.Wait -> delay(decision.millis)
                }
            }
        }
    }

    private fun rearmStandbyTimeout() {
        if (status == TrackingStatus.STANDBY) scheduleStandbyTimeout()
    }

    private fun standbyHasExpired(): Boolean {
        val started = standbyStartedElapsedRealtime ?: return false
        return elapsedDeadlineDecision(started, SystemClock.elapsedRealtime(), STANDBY_TIMEOUT_MS) ==
            DeadlineDecision.Expired
    }

    private fun cancelStandbyTimeout() {
        standbyJob?.cancel()
        standbyJob = null
    }

    /** Double buzz on auto-pause — noticeable in a pocket, unlike any on-screen hint. */
    private fun vibrateAutoPause() {
        vibrate(longArrayOf(0, 200, 150, 200))
    }

    /** Single buzz on auto-resume, so it reads as clearly different from the double auto-pause.
     *  Longer than one auto-pause pulse: this one fires while the bike is already moving again,
     *  where a short buzz is lost in the road vibration. */
    private fun vibrateAutoResume() {
        vibrate(longArrayOf(0, 400))
    }

    private fun vibrate(timings: LongArray) {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private fun scheduleAutoSave() {
        // Re-arming after a fix replaces only the waiter; the pause instant must remain stable.
        autoSaveJob?.cancel()
        autoSaveJob = null
        val pauseStarted = pauseStartedElapsedRealtime ?: return
        // Runs on the main thread so this can't race with a resume or recordLocation mutating
        // status/points; a resume's cancelAutoSave() aborts it at either suspension point.
        autoSaveJob = scope.launch(Dispatchers.Main) {
            AppSettings.autoSaveMin.collectLatest { minutes ->
                while (status == TrackingStatus.PAUSED) {
                    when (val decision = autoSaveDeadlineDecision(
                        pauseStartedMillis = pauseStarted,
                        nowMillis = SystemClock.elapsedRealtime(),
                        autoSaveMinutes = minutes.toLong(),
                        hasFreshFix = hasFreshFix(),
                        gpsWaitMillis = AUTO_SAVE_GPS_WAIT_MS,
                    )) {
                        // Keep collecting: changing 0 to a positive value during this pause arms a
                        // deadline based on the time that has already elapsed.
                        DeadlineDecision.Disabled -> return@collectLatest
                        DeadlineDecision.Expired -> {
                            if (autoSaveDeadlineIsCurrent(
                                expectedPauseStartedMillis = pauseStarted,
                                activePauseStartedMillis = pauseStartedElapsedRealtime,
                                isPaused = status == TrackingStatus.PAUSED,
                                isStopping = stopping.get(),
                            )) {
                                saveAndEnterStandby()
                            }
                            return@collectLatest
                        }
                        is DeadlineDecision.Wait ->
                            delay(decision.millis.coerceAtMost(AUTO_SAVE_GPS_RECHECK_MS))
                    }
                }
            }
        }
    }

    private fun rearmAutoSave() {
        if (status == TrackingStatus.PAUSED) scheduleAutoSave()
    }

    /** Whether a fix was accepted recently enough to still describe where the rider is. */
    private fun hasFreshFix(): Boolean =
        lastTrustedFixElapsedRealtime > 0L &&
            SystemClock.elapsedRealtime() - lastTrustedFixElapsedRealtime <= GPS_STALE_MS

    private fun cancelAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        pauseStartedElapsedRealtime = null
    }

    /** Schedule a full checkpoint; the writer reconciles it with durable rows on retry. Each
     *  waits for the flush before it, so checkpoints reach the database in the order they were
     *  taken however long a write is held up — and only one of them is ever outstanding. */
    private fun flushDraft() {
        val persistence = draftPersistence ?: return
        if (points.size == scheduledFlushCount) return
        val checkpoint = checkpoint(finished = false)
        scheduledFlushCount = checkpoint.points.size
        val previous = flushJob
        flushJob = scope.launch {
            previous?.join()
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
        persistenceFailed = false
        // Unconditional: the snapshot now also carries that the ride is being written, and the
        // controls go quiet on it for as long as that takes.
        publish()
        val persistence = draftPersistence ?: return handleSaveFailure()
        // The last flush waits on every flush before it, so joining it joins them all.
        val pendingJobs = listOfNotNull(draftStartJob, flushJob)
        val finalCheckpoint = checkpoint(finished = true)
        // Decided here, on the main thread, from the reason this stop was ordered: a Stop arriving
        // later can downgrade the standby that follows, but not the ride's own claim to be stored.
        val worthSaving = save && isRideWorthSaving(
            pointCount = finalCheckpoint.points.size,
            distanceMeters = finalCheckpoint.trip.distanceMeters,
            automatic = thenStandby,
        )
        scope.launch {
            withContext(NonCancellable) {
                pendingJobs.joinAll()
                val result = if (worthSaving) {
                    persistence.persist(finalCheckpoint)
                } else {
                    persistence.discard()
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
                avgGpsSpeedMps = if (finished) averageObservedSpeed(recorded) else null,
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
                saving = stopping.get(),
                persistenceFailed = persistenceFailed,
                route = route.snapshot(),
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
        /** How often the elapsed auto-save re-checks for the GPS to come back before closing
         *  the ride; short enough that the save follows the returning signal closely. */
        private const val AUTO_SAVE_GPS_RECHECK_MS = 15_000L

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
