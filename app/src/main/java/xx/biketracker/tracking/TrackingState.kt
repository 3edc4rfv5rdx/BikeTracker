package xx.biketracker.tracking

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
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val currentSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val altitudeMeters: Double? = null,
    val startTime: Long = 0L,
    val updatedAtWall: Long = 0L, // System wall clock when this snapshot was published
    val route: List<GeoPoint> = emptyList(),
)

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
