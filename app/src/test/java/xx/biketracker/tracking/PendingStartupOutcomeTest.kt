package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a command does when it lands in the window where a ride is opening. The distinction that
 * matters is what the service was before that window: a cold start has nothing to fall back to, a
 * start from standby has a live session that must survive anything short of the rider ending it.
 */
class PendingStartupOutcomeTest {

    private fun cold(action: String?) = pendingStartupOutcome(action, fromStandby = false)
    private fun standby(action: String?) = pendingStartupOutcome(action, fromStandby = true)

    @Test
    fun stopDuringAColdStartupTearsTheServiceDown() {
        assertEquals(PendingStartupOutcome.ABORT, cold(TrackingService.ACTION_STOP))
        assertEquals(PendingStartupOutcome.ABORT, cold(TrackingService.ACTION_DISCARD))
    }

    @Test
    fun pauseOrResumeDuringAColdStartupTearsTheServiceDown() {
        // Nothing is recorded yet, so there is nothing for them to act on.
        assertEquals(PendingStartupOutcome.ABORT, cold(TrackingService.ACTION_PAUSE))
        assertEquals(PendingStartupOutcome.ABORT, cold(TrackingService.ACTION_RESUME))
    }

    @Test
    fun anUnknownCommandDuringAColdStartupTearsTheServiceDown() {
        assertEquals(PendingStartupOutcome.ABORT, cold(null))
        assertEquals(PendingStartupOutcome.ABORT, cold("xx.biketracker.action.NONSENSE"))
    }

    @Test
    fun stopDuringAStandbyStartupEndsTheSession() {
        // The rider asked for it, so the standby goes too — but through a shutdown that knows
        // about the half-open ride, not through the cold-start teardown.
        assertEquals(PendingStartupOutcome.FINISH, standby(TrackingService.ACTION_STOP))
        assertEquals(PendingStartupOutcome.FINISH, standby(TrackingService.ACTION_DISCARD))
    }

    @Test
    fun anythingElseDuringAStandbyStartupFallsBackToStandby() {
        // Tearing the service down here would end a working standby session nobody asked to end,
        // and with it the auto-start that is the whole point of standby.
        assertEquals(PendingStartupOutcome.BACK_TO_STANDBY, standby(TrackingService.ACTION_PAUSE))
        assertEquals(PendingStartupOutcome.BACK_TO_STANDBY, standby(TrackingService.ACTION_RESUME))
        assertEquals(PendingStartupOutcome.BACK_TO_STANDBY, standby(null))
    }

    @Test
    fun aSecondStartIsLeftToTheNormalPath() {
        // Where it is refused as a duplicate; the startup already under way is not disturbed.
        assertEquals(PendingStartupOutcome.HANDLE, cold(TrackingService.ACTION_START))
        assertEquals(PendingStartupOutcome.HANDLE, standby(TrackingService.ACTION_START))
    }
}
