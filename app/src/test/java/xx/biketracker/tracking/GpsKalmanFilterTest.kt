package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [GpsKalmanFilter]: the first fix seeds the estimate, [GpsKalmanFilter.reset]
 *  re-seeds it, standstill fixes are only partially applied, a long gap snaps to the new fix,
 *  and a more accurate fix (lower accuracy metres) is trusted more. */
class GpsKalmanFilterTest {

    @Test
    fun firstFixIsReturnedRaw() {
        val out = GpsKalmanFilter().filter(50.0, 30.0, accuracyM = 5f, timeMs = 0, speedMps = 0.0)
        assertEquals(50.0, out.lat, 1e-12)
        assertEquals(30.0, out.lon, 1e-12)
    }

    @Test
    fun resetReseedsFromTheNextFix() {
        val f = GpsKalmanFilter()
        f.filter(50.0, 30.0, 5f, 0, 0.0)
        f.filter(50.001, 30.001, 5f, 1_000, 0.0)
        f.reset()
        val out = f.filter(10.0, 20.0, 5f, 2_000, 0.0)
        assertEquals(10.0, out.lat, 1e-12)
        assertEquals(20.0, out.lon, 1e-12)
    }

    @Test
    fun standstillFixIsOnlyPartiallyApplied() {
        val f = GpsKalmanFilter()
        f.filter(0.0, 0.0, 5f, 0, 0.0)
        // The estimate moves toward the new fix but does not jump onto it — that is the smoothing.
        val out = f.filter(0.002, 0.0, 5f, 1_000, 0.0)
        assertTrue(out.lat > 0.0 && out.lat < 0.002)
    }

    @Test
    fun aLongGapSnapsToTheNewFix() {
        val f = GpsKalmanFilter()
        f.filter(0.0, 0.0, 5f, 0, 0.0)
        // 100 s at 20 m/s inflates the process variance so the gain approaches 1.
        val out = f.filter(1.0, 0.0, 5f, 100_000, 20.0)
        assertTrue(out.lat > 0.99)
    }

    @Test
    fun aMoreAccurateFixIsTrustedMore() {
        fun secondLat(accuracyM: Float): Double {
            val f = GpsKalmanFilter()
            f.filter(0.0, 0.0, accuracyM, 0, 0.0)
            return f.filter(0.01, 0.0, accuracyM, 1_000, 0.0).lat
        }
        // A tight ±1 m fix pulls the estimate closer to the raw 0.01 than a loose ±100 m one.
        assertTrue(secondLat(1f) > secondLat(100f))
    }
}
