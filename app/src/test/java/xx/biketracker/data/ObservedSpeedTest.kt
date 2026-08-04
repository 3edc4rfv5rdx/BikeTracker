package xx.biketracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedSpeedTest {

    private fun point(speed: Float?) = TrackPoint(
        tripId = 1,
        lat = 50.0,
        lon = 30.0,
        time = 1_000,
        speedMps = speed,
    )

    @Test
    fun averageExcludesMissingObservationsButKeepsRealZero() {
        assertEquals(4.0, averageObservedSpeed(listOf(point(null), point(0f), point(8f)))!!, 0.0)
    }

    @Test
    fun noSpeedObservationsHasNoAverage() {
        assertNull(averageObservedSpeed(listOf(point(null))))
    }
}
