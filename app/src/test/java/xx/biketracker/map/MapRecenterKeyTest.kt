package xx.biketracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MapRecenterKeyTest {

    @Test
    fun twoConsecutiveLiveRidesGetDifferentKeys() {
        val first = mapRecenterKey(null, 1_000L)
        val second = mapRecenterKey(null, 2_000L)
        assertNotEquals(first, second)
    }

    @Test
    fun keyIsStableWithinOneRide() {
        assertEquals(mapRecenterKey(null, 1_000L), mapRecenterKey(null, 1_000L))
    }

    @Test
    fun stopThenStartBeforeFirstFixReArms() {
        val ride = mapRecenterKey(null, 1_000L)
        val idle = mapRecenterKey(null, 0L)
        val nextRide = mapRecenterKey(null, 3_000L)
        assertNotEquals(ride, idle)
        assertNotEquals(ride, nextRide)
        assertNotEquals(idle, nextRide)
    }

    @Test
    fun storedTripNeverCollidesWithLiveRide() {
        // Same numeric value must not be the same key when it means trip id vs. start time.
        assertNotEquals(mapRecenterKey(42L, 0L), mapRecenterKey(null, 42L))
    }

    @Test
    fun switchingBetweenTripAndActiveRideChangesTheKey() {
        val live = mapRecenterKey(null, 1_000L)
        val trip = mapRecenterKey(7L, 1_000L)
        assertNotEquals(live, trip)
        assertEquals(trip, mapRecenterKey(7L, 1_000L))
    }
}
