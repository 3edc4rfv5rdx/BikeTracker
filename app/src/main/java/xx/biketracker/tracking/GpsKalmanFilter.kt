package xx.biketracker.tracking

import xx.biketracker.GeoPoint
import kotlin.math.max

/**
 * Minimal Kalman filter over GPS fixes: a constant-position model per axis with process
 * noise scaled by the reported speed. At standstill the estimate barely moves, flattening
 * the jitter that paints zigzags and inflates distance; at riding speed the process noise
 * dominates and the filter follows the fixes closely, so corners are not cut. Lat/lon are
 * filtered in degrees with a shared gain — at these scales the axes are independent enough.
 */
class GpsKalmanFilter {
    private var lat = 0.0
    private var lon = 0.0
    private var varianceM2 = -1.0 // negative means uninitialized
    private var lastTimeMs = 0L

    fun reset() {
        varianceM2 = -1.0
    }

    fun filter(rawLat: Double, rawLon: Double, accuracyM: Float, timeMs: Long, speedMps: Double): GeoPoint {
        val accuracy = max(accuracyM.toDouble(), 1.0)
        if (varianceM2 < 0) {
            lat = rawLat
            lon = rawLon
            varianceM2 = accuracy * accuracy
        } else {
            // Grow the uncertainty by how far we could have travelled since the last fix;
            // after a long gap (e.g. a pause) the gain approaches 1 and the filter snaps
            // to the new position instead of dragging a stale estimate along.
            val dtSec = max(timeMs - lastTimeMs, 0L) / 1000.0
            val processSpeed = max(speedMps, MIN_PROCESS_SPEED_MPS)
            varianceM2 += dtSec * processSpeed * processSpeed
            val gain = varianceM2 / (varianceM2 + accuracy * accuracy)
            lat += gain * (rawLat - lat)
            lon += gain * (rawLon - lon)
            varianceM2 *= 1 - gain
        }
        lastTimeMs = timeMs
        return GeoPoint(lat, lon)
    }

    private companion object {
        /** Floor for the process noise so a reported speed of 0 can't freeze the filter. */
        const val MIN_PROCESS_SPEED_MPS = 1.0
    }
}
