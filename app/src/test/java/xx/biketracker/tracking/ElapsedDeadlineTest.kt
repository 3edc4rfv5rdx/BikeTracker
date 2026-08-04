package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedDeadlineTest {

    @Test
    fun shorterLongerAndDisabledSettingsApplyToTheExistingPause() {
        val start = 1_000L
        val now = start + 5 * 60_000L

        assertEquals(
            DeadlineDecision.Expired,
            autoSaveDeadlineDecision(start, now, 3, hasFreshFix = true, gpsWaitMillis = 60_000),
        )
        assertEquals(
            DeadlineDecision.Wait(5 * 60_000L),
            autoSaveDeadlineDecision(start, now, 10, hasFreshFix = true, gpsWaitMillis = 60_000),
        )
        assertEquals(
            DeadlineDecision.Disabled,
            autoSaveDeadlineDecision(start, now, 0, hasFreshFix = true, gpsWaitMillis = 60_000),
        )
    }

    @Test
    fun clockJumpCountsTowardAutoSaveAndItsBoundedGpsWait() {
        val start = 20_000L
        assertEquals(
            DeadlineDecision.Wait(30_000L),
            autoSaveDeadlineDecision(
                start, start + 10 * 60_000L + 30_000L, 10,
                hasFreshFix = false, gpsWaitMillis = 60_000L,
            ),
        )
        assertEquals(
            DeadlineDecision.Expired,
            autoSaveDeadlineDecision(
                start, start + 40 * 60_000L, 10,
                hasFreshFix = false, gpsWaitMillis = 60_000L,
            ),
        )
    }

    @Test
    fun aFreshFixExpiresOnlyOnceThePauseDeadlineHasPassed() {
        val start = 500L
        assertEquals(
            DeadlineDecision.Wait(1L),
            autoSaveDeadlineDecision(start, start + 59_999L, 1, true, 30_000L),
        )
        assertEquals(
            DeadlineDecision.Expired,
            autoSaveDeadlineDecision(start, start + 60_000L, 1, true, 30_000L),
        )
    }

    @Test
    fun durationMathSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, saturatingDuration(Long.MAX_VALUE, 60_000L))
        assertEquals(0L, saturatingDuration(-1L, 60_000L))
    }

    @Test
    fun standbyExpiresAcrossADeepSleepStyleJump() {
        assertEquals(DeadlineDecision.Wait(1_000L), elapsedDeadlineDecision(1_000L, 5_000L, 5_000L))
        assertEquals(DeadlineDecision.Expired, elapsedDeadlineDecision(1_000L, 50_000L, 5_000L))
    }

    @Test
    fun resumeStopAndANewerPauseInvalidateAReadyTimer() {
        val pause = 1_000L
        assertEquals(true, autoSaveDeadlineIsCurrent(pause, pause, isPaused = true, isStopping = false))
        assertEquals(false, autoSaveDeadlineIsCurrent(pause, null, isPaused = false, isStopping = false))
        assertEquals(false, autoSaveDeadlineIsCurrent(pause, pause, isPaused = true, isStopping = true))
        assertEquals(false, autoSaveDeadlineIsCurrent(pause, pause + 1, isPaused = true, isStopping = false))
    }
}
