package xx.biketracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideWorthSavingTest {

    @Test
    fun aRideWithoutATrackIsNeverSaved() {
        assertFalse(isRideWorthSaving(pointCount = 1, distanceMeters = 500.0, automatic = false))
        assertFalse(isRideWorthSaving(pointCount = 40, distanceMeters = 0.0, automatic = false))
    }

    @Test
    fun theRidersOwnStopSavesHoweverShortTheRide() {
        assertTrue(isRideWorthSaving(pointCount = 2, distanceMeters = 5.0, automatic = false))
    }

    /** The stretch a jammed receiver wanders while the bike stands still — whether the auto-save
     *  or the recovery pass is the one closing it. */
    @Test
    fun anUnaskedForSaveDropsARideTooShortToBeOne() {
        assertFalse(
            isRideWorthSaving(
                pointCount = 30,
                distanceMeters = MIN_AUTO_SAVED_DISTANCE_M - 1.0,
                automatic = true,
            )
        )
    }

    @Test
    fun anUnaskedForSaveKeepsARideThatCoveredTheDistance() {
        assertTrue(
            isRideWorthSaving(
                pointCount = 30,
                distanceMeters = MIN_AUTO_SAVED_DISTANCE_M,
                automatic = true,
            )
        )
    }
}
