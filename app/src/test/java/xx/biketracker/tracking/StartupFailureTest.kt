package xx.biketracker.tracking

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A start the service refuses has to reach the rider. The trap is the state flow: it publishes
 * changes, so a plain "it failed" flag makes every refusal after the first look like no news at all.
 */
class StartupFailureTest {

    @After
    fun clearProcessWideState() = TrackingState.reset()

    private val current get() = TrackingState.snapshot.value.startupFailure

    @Test
    fun theReasonReachesTheSnapshot() {
        TrackingState.publishStartupFailure(StartupFailureReason.DATABASE_BUSY)
        assertEquals(StartupFailureReason.DATABASE_BUSY, current!!.reason)
    }

    @Test
    fun twoRefusalsForTheSameReasonAreTwoDistinctValues() {
        TrackingState.publishStartupFailure(StartupFailureReason.NO_PERMISSION)
        val first = current!!
        TrackingState.publishStartupFailure(StartupFailureReason.NO_PERMISSION)
        val second = current!!

        assertEquals(first.reason, second.reason)
        // Equal values would leave the second tap unreported; the rider would tap into silence.
        assertNotEquals(first, second)
    }

    @Test
    fun aRefusalClearsWhateverTheSnapshotHeldBefore() {
        // There is no ride behind a refused start, so nothing of one may linger on screen.
        TrackingState.publish(
            TrackingSnapshot(status = TrackingStatus.RECORDING, distanceMeters = 1_234.0)
        )
        TrackingState.publishStartupFailure(StartupFailureReason.FAILED)

        assertEquals(TrackingStatus.IDLE, TrackingState.snapshot.value.status)
        assertEquals(0.0, TrackingState.snapshot.value.distanceMeters, 0.0)
    }

    @Test
    fun aFreshSnapshotCarriesNoFailure() {
        TrackingState.publishStartupFailure(StartupFailureReason.FAILED)
        TrackingState.reset()
        assertNull(current)
    }
}
