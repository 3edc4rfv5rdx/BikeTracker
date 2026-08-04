package xx.biketracker.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.AUTO_SAVE_GPS_WAIT_MS

/**
 * The rule that ends the elapsed auto-save's wait for the GPS to come back. Waiting is what keeps a
 * ride from being closed on the strength of a jammed signal; the bound is what keeps the service
 * from waiting for a signal that is never coming.
 */
class AutoSaveWaitTest {

    private fun mayClose(hasFreshFix: Boolean, waitedMillis: Long) =
        autoSaveMayClose(hasFreshFix, waitedMillis, AUTO_SAVE_GPS_WAIT_MS)

    @Test
    fun aReturningFixSettlesItAtOnce() {
        assertTrue(mayClose(hasFreshFix = true, waitedMillis = 0L))
    }

    @Test
    fun withoutAFixTheWaitGoesOn() {
        assertFalse(mayClose(hasFreshFix = false, waitedMillis = 0L))
        assertFalse(mayClose(hasFreshFix = false, waitedMillis = AUTO_SAVE_GPS_WAIT_MS - 1))
    }

    @Test
    fun theWaitIsGivenUpOnceItsLimitIsReached() {
        // A phone left somewhere without a signal would otherwise hold the service — and its
        // location request — open for as long as it sat there.
        assertTrue(mayClose(hasFreshFix = false, waitedMillis = AUTO_SAVE_GPS_WAIT_MS))
        assertTrue(mayClose(hasFreshFix = false, waitedMillis = AUTO_SAVE_GPS_WAIT_MS * 2))
    }

    @Test
    fun aFixArrivingLateStillSettlesIt() {
        assertTrue(mayClose(hasFreshFix = true, waitedMillis = AUTO_SAVE_GPS_WAIT_MS * 2))
    }

    @Test
    fun theLimitLeavesRoomForSeveralRechecks() {
        // The loop re-checks every AUTO_SAVE_GPS_RECHECK_MS (15 s); a limit anywhere near that
        // would turn the wait into a formality that never gives a returning signal a chance.
        assertTrue(AUTO_SAVE_GPS_WAIT_MS >= 10 * 60_000L)
    }
}
