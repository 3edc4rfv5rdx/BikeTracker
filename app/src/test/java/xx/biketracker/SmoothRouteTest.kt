package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [smoothRoute]: a centered moving average followed by Douglas-Peucker.
 *  Endpoints are always kept raw, a straight line collapses to its ends, and a real corner stays. */
class SmoothRouteTest {

    @Test
    fun shortRoutesAreReturnedUnchanged() {
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        assertSame(route, smoothRoute(route))
    }

    @Test
    fun endpointsAreKeptExactly() {
        val route = List(8) { GeoPoint(50.0 + it * 0.0003, 30.0 + it * 0.0007) }
        val smoothed = smoothRoute(route)
        assertEquals(route.first().lat, smoothed.first().lat, 1e-12)
        assertEquals(route.first().lon, smoothed.first().lon, 1e-12)
        assertEquals(route.last().lat, smoothed.last().lat, 1e-12)
        assertEquals(route.last().lon, smoothed.last().lon, 1e-12)
    }

    @Test
    fun aStraightLineCollapsesToItsEndpoints() {
        // Collinear points stay collinear through the moving average, so simplify drops the middle.
        val line = List(6) { GeoPoint(it * 0.001, 0.0) }
        assertEquals(2, smoothRoute(line).size)
    }

    @Test
    fun aRealCornerIsPreserved() {
        // An L-shape: east then north. The turn deviates far past the 2 m tolerance, so points
        // beyond the two endpoints survive simplification.
        val east = List(5) { GeoPoint(0.0, it * 0.001) }
        val north = List(5) { GeoPoint((it + 1) * 0.001, 0.004) }
        assertTrue(smoothRoute(east + north).size > 2)
    }

    @Test
    fun aSmoothedPointKeepsEverythingButItsPosition() {
        // Averaging moves a point; it does not make it a different point. A smoothed route whose
        // times, speeds and flags had been reset to defaults would be read as a ride that took no
        // time, at no speed, with no pause in it — and nothing would report the loss.
        val corner = List(9) { i ->
            GeoPoint(
                lat = if (i < 5) 0.0 else (i - 4) * 0.001,
                lon = if (i < 5) i * 0.001 else 0.004,
                timeMillis = 1_700_000_000_000L + i * 1_000L,
                speedMps = 5f + i,
                segmentStart = i == 5,
                elapsedMillis = i * 1_000L,
            )
        }
        val smoothed = smoothRoute(corner)

        assertTrue("expected simplification to keep a middle point", smoothed.size > 2)
        for (point in smoothed) {
            // Each surviving point still answers for itself: find it by what it carries.
            val original = corner.single { it.timeMillis == point.timeMillis }
            assertEquals(original.speedMps, point.speedMps, 0f)
            assertEquals(original.segmentStart, point.segmentStart)
            assertEquals(original.elapsedMillis, point.elapsedMillis)
        }
    }

    @Test
    fun aSmoothedLegacySegmentIsStillReadAsOneRecording() {
        // Rides recorded before the elapsed offset existed have only their timestamps to say where
        // the recording broke. A smoothing pass that dropped those would hand back a segment whose
        // points all claim time zero — unmeasurable against each other, and silently so.
        //
        // A ride's own pace, at the recording cadence: ~11 m every 1.5 s. Anything faster would be
        // read as a jump, and the split below would be counting that instead. It turns a corner
        // halfway, so simplification keeps points between the two ends — the ones that carry a
        // rebuilt point's defaults if anything does.
        val segment = List(8) { i ->
            GeoPoint(
                lat = if (i < 4) 50.0 else 50.0 + (i - 3) * 0.0001,
                lon = 30.0 + minOf(i, 3) * 0.00016,
                timeMillis = 1_000L + i * 1_500L,
            )
        }
        val smoothed = smoothRoute(segment)

        assertTrue("expected simplification to keep a middle point", smoothed.size > 2)
        assertTrue("smoothing lost the timestamps", smoothed.all { it.timeMillis > 0L })
        assertEquals(1, splitRouteSegments(smoothed).size)
    }
}
