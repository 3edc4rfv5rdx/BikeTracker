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
}
