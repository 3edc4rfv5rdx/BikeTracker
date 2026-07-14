package xx.biketracker.tracking

import xx.biketracker.ACCURACY_THRESHOLD_M
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingStatus { IDLE, RECORDING, PAUSED }

/**
 * Live view of the ride in progress, published by [TrackingService] and collected
 * by the UI. Distances and speeds are SI (meters, m/s); the UI converts for display.
 */
data class TrackingSnapshot(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val pausedAutomatically: Boolean = false, // meaningful only while status == PAUSED
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val currentSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val altitudeMeters: Double? = null,
    val gpsAccuracyMeters: Float? = null, // horizontal accuracy of the last fix; null before one
    val bearingDegrees: Float? = null, // heading of travel; null until the first fix that reports one
    val startTime: Long = 0L,
    val startElapsedRealtime: Long = 0L,
    val updatedAtElapsedRealtime: Long = 0L, // publication baseline for the live moving timer
    val lastTrustedFixElapsedRealtime: Long = 0L,
    val persistenceFailed: Boolean = false,
    val startupFailed: Boolean = false,
    val route: List<GeoPoint> = emptyList(),
)

/**
 * True while a ride is active but the fixes are stale, missing, or too inaccurate to trust.
 * A fix timestamped after [nowElapsedRealtime] is fresh, not trouble: the UI samples its
 * clock at a coarser cadence than fixes arrive, so a negative age is routine.
 */
fun TrackingSnapshot.hasGpsTrouble(nowElapsedRealtime: Long): Boolean =
    status != TrackingStatus.IDLE &&
        (lastTrustedFixElapsedRealtime <= 0L ||
            nowElapsedRealtime - lastTrustedFixElapsedRealtime > GPS_STALE_MS ||
            gpsAccuracyMeters == null ||
            gpsAccuracyMeters > ACCURACY_THRESHOLD_M)

/** Moving time ticking locally between GPS updates; frozen while paused or idle. */
fun TrackingSnapshot.liveMovingTimeMillis(nowElapsedRealtime: Long): Long =
    movingTimeMillis +
        if (status == TrackingStatus.RECORDING) {
            (nowElapsedRealtime - updatedAtElapsedRealtime).coerceAtLeast(0L)
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

    internal fun publish(snapshot: TrackingSnapshot) {
        _snapshot.value = snapshot
    }

    internal fun reset() {
        _snapshot.value = TrackingSnapshot()
    }
}
