package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteStartHeadingTest {

    private fun point(
        lat: Double,
        lon: Double,
        timeMillis: Long = 0L,
        segmentStart: Boolean = false,
        elapsedMillis: Long? = null,
    ) = GeoPoint(
        lat = lat,
        lon = lon,
        timeMillis = timeMillis,
        segmentStart = segmentStart,
        elapsedMillis = elapsedMillis,
    )

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

    /** A pause ends the first segment: where riding resumed says nothing about where it began. */
    @Test
    fun aFirstSegmentTooShortToShowADirectionHasNoHeading() {
        val heading = routeStartHeading(
            listOf(
                point(50.0, 30.0, elapsedMillis = 0),
                point(50.00005, 30.0, elapsedMillis = 1_000), // ~5 m — still standing at the start
                point(50.01, 30.05, elapsedMillis = 600_000, segmentStart = true), // resumed far away
                point(50.011, 30.05, elapsedMillis = 601_000),
            )
        )

        assertNull(heading)
    }

    /** The same on a legacy ride, whose boundaries are read from its wall-clock gaps alone. */
    @Test
    fun aLegacyOutageBeforeTheSpanEndsTheSearch() {
        val heading = routeStartHeading(
            listOf(
                point(50.0, 30.0, timeMillis = 1_000_000),
                point(50.00005, 30.0, timeMillis = 1_001_000),
                point(50.01, 30.05, timeMillis = 1_500_000), // eight minutes later, far away
            )
        )

        assertNull(heading)
    }

    /** A boundary past the span comes too late to matter — the direction is already known. */
    @Test
    fun aPauseAfterTheStartLeavesTheHeadingAlone() {
        val heading = routeStartHeading(
            listOf(
                point(50.0, 30.0, elapsedMillis = 0),
                point(50.001, 30.0, elapsedMillis = 60_000), // ~111 m north
                point(50.05, 30.09, elapsedMillis = 900_000, segmentStart = true),
            )
        )

        assertEquals(0.0, heading!!, 0.5)
    }

    @Test
    fun aTrackThatNeverLeavesItsStartHasNoHeading() {
        assertNull(routeStartHeading(emptyList()))
        assertNull(routeStartHeading(listOf(point(50.0, 30.0))))
        assertNull(routeStartHeading(listOf(point(50.0, 30.0), point(50.00005, 30.0))))
    }
}
