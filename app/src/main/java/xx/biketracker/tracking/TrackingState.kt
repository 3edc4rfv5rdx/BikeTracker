package xx.biketracker.tracking

import xx.biketracker.ACCURACY_THRESHOLD_M
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

enum class TrackingStatus { IDLE, RECORDING, PAUSED, STANDBY }

/** Why a ride could not start, so the rider is told which of these it was rather than that it
 *  simply didn't happen. */
enum class StartupFailureReason {
    /** A backup or a restore holds the database; a ride cannot open its draft alongside one. */
    DATABASE_BUSY,
    /** Location permission was gone by the time the service looked. */
    NO_PERMISSION,
    /** The draft could not be opened, or the provider refused the location request. */
    FAILED,
}

/**
 * A refused start, for the UI to report once. [attempt] counts refusals across the process: two
 * taps refused for the same reason must be two distinct values, or the state flow would treat the
 * second as no news and the rider would tap into silence.
 */
data class StartupFailure(val reason: StartupFailureReason, val attempt: Int)

/**
 * Live view of the ride in progress, published by [TrackingService] and collected
 * by the UI. Distances and speeds are SI (meters, m/s); the UI converts for display.
 */
data class TrackingSnapshot(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val pausedAutomatically: Boolean = false, // meaningful only while status == PAUSED
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    // Null only while recording when the latest accepted fix supplied no trustworthy speed.
    val currentSpeedMps: Double? = 0.0,
    val maxSpeedMps: Double = 0.0,
    val altitudeMeters: Double? = null,
    val gpsAccuracyMeters: Float? = null, // horizontal accuracy of the last fix; null before one
    val bearingDegrees: Float? = null, // heading of travel; null until the first fix that reports one
    val startTime: Long = 0L,
    val startElapsedRealtime: Long = 0L,
    val updatedAtElapsedRealtime: Long = 0L, // publication baseline for the live moving timer
    val lastTrustedFixElapsedRealtime: Long = 0L,
    // True from the moment a stop is ordered until the ride has been written to the database (or
    // the write failed): the controls have nothing to command in that window.
    val saving: Boolean = false,
    val persistenceFailed: Boolean = false,
    val startupFailure: StartupFailure? = null,
    val route: List<GeoPoint> = emptyList(),
)

/** True while a ride is being recorded or paused; standby between rides doesn't count. */
val TrackingSnapshot.rideActive: Boolean
    get() = status == TrackingStatus.RECORDING || status == TrackingStatus.PAUSED

/**
 * True while a ride is active but the fixes are stale, missing, or too inaccurate to trust.
 * A fix timestamped after [nowElapsedRealtime] is fresh, not trouble: the UI samples its
 * clock at a coarser cadence than fixes arrive, so a negative age is routine.
 */
fun TrackingSnapshot.hasGpsTrouble(nowElapsedRealtime: Long): Boolean =
    rideActive &&
        (lastTrustedFixElapsedRealtime <= 0L ||
            nowElapsedRealtime - lastTrustedFixElapsedRealtime > GPS_STALE_MS ||
            gpsAccuracyMeters == null ||
            gpsAccuracyMeters > ACCURACY_THRESHOLD_M)

/**
 * Moving time ticking locally between GPS updates; frozen while paused or idle.
 *
 * The tick only ever runs as far as the next fix is due ([GPS_INTERVAL_MS] past the last one).
 * Anything past that is a guess about time nobody has measured yet, and a guess the recorder may
 * refuse — a stretch it judges an outage adds no moving time at all — so the display would run up
 * and then fall back every time a fix landed. On a jammed signal, where fixes arrive tens of
 * seconds apart, that sawtooth is what reads as the ride timer resetting over and over. The timer
 * therefore lags a sparse fix stream and catches up in steps; it never runs ahead of the record.
 */
fun TrackingSnapshot.liveMovingTimeMillis(nowElapsedRealtime: Long): Long =
    movingTimeMillis +
        if (status == TrackingStatus.RECORDING) {
            (min(nowElapsedRealtime, lastTrustedFixElapsedRealtime + GPS_INTERVAL_MS) -
                updatedAtElapsedRealtime).coerceAtLeast(0L)
        } else {
            0L
        }

/**
 * Process-wide holder so the UI can observe tracking state without binding to the
 * service. The service is the only writer; the UI only reads [snapshot].
 */
object TrackingState {
    private val _snapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    // Numbered here rather than in the service: a refused start stops the service, so the next
    // refusal is counted by a fresh instance and would otherwise repeat the previous number.
    private var startupFailures = 0

    internal fun publish(snapshot: TrackingSnapshot) {
        _snapshot.value = snapshot
    }

    /** Publish a start that was refused, clearing the rest of the snapshot: there is no ride. */
    internal fun publishStartupFailure(reason: StartupFailureReason) {
        _snapshot.value = TrackingSnapshot(startupFailure = StartupFailure(reason, ++startupFailures))
    }

    internal fun reset() {
        _snapshot.value = TrackingSnapshot()
    }
}
