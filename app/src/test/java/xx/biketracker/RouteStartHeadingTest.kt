package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteStartHeadingTest {

    private fun point(lat: Double, lon: Double) = GeoPoint(lat = lat, lon = lon)

    /** ~0.001° of latitude is ~111 m — well past the span the heading is read over. */
    @Test
    fun theHeadingIsTheDirectionTheTrackLeavesItsStart() {
        val north = routeStartHeading(listOf(point(50.0, 30.0), point(50.001, 30.0)))
        val east = routeStartHeading(listOf(point(50.0, 30.0), point(50.0, 30.002)))

        assertEquals(0.0, north!!, 0.5)
        assertEquals(90.0, east!!, 0.5)
    }

    /** Jitter at the start must not decide the direction: the span is measured from the first
     *  point, and only a fix beyond it counts. */
    @Test
    fun standstillJitterAtTheStartIsSkipped() {
        val heading = routeStartHeading(
            listOf(
                point(50.0, 30.0),
                point(49.99995, 30.0), // ~5 m south — noise, not a departure
                point(50.001, 30.0),
            )
        )

        assertEquals(0.0, heading!!, 1.0)
    }

    @Test
    fun aTrackThatNeverLeavesItsStartHasNoHeading() {
        assertNull(routeStartHeading(emptyList()))
        assertNull(routeStartHeading(listOf(point(50.0, 30.0))))
        assertNull(routeStartHeading(listOf(point(50.0, 30.0), point(50.00005, 30.0))))
    }
}
