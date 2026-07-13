package xx.biketracker

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayBoundaryTest {

    private val kyiv = TimeZone.getTimeZone("Europe/Kiev")

    private fun at(
        timeZone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long = Calendar.getInstance(timeZone).run {
        clear()
        set(year, month - 1, day, hour, minute, 0)
        timeInMillis
    }

    private fun assertIsMidnight(millis: Long, timeZone: TimeZone) {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }

    @Test
    fun ordinaryDayRollsOverAtLocalMidnight() {
        val now = at(kyiv, 2026, 7, 13, hour = 15, minute = 30)
        val wait = millisUntilNextMidnight(now, kyiv)
        assertEquals(at(kyiv, 2026, 7, 14), now + wait)
        assertIsMidnight(now + wait, kyiv)
    }

    @Test
    fun exactlyAtMidnightWaitsAFullDayInsteadOfFiringImmediately() {
        val midnight = at(kyiv, 2026, 7, 13)
        assertEquals(at(kyiv, 2026, 7, 14) - midnight, millisUntilNextMidnight(midnight, kyiv))
    }

    @Test
    fun dstTransitionDaysStillLandOnTrueMidnight() {
        // Ukraine springs forward on 2026-03-29 (23-hour day) and falls back on
        // 2026-10-25 (25-hour day); the boundary must be calendar midnight, not now+24h.
        // From 01:00 the remaining wall time is 23 h, so the real wait is 22 h / 24 h.
        val springNight = at(kyiv, 2026, 3, 29, hour = 1)
        val springWait = millisUntilNextMidnight(springNight, kyiv)
        assertIsMidnight(springNight + springWait, kyiv)
        assertTrue(springWait < 23L * 60 * 60 * 1000)

        val fallNight = at(kyiv, 2026, 10, 25, hour = 1)
        val fallWait = millisUntilNextMidnight(fallNight, kyiv)
        assertIsMidnight(fallNight + fallWait, kyiv)
        assertTrue(fallWait > 23L * 60 * 60 * 1000)
    }

    @Test
    fun leapDayIsCountedIn() {
        val now = at(kyiv, 2028, 2, 28, hour = 12)
        val midnight = now + millisUntilNextMidnight(now, kyiv)
        val calendar = Calendar.getInstance(kyiv).apply { timeInMillis = midnight }
        assertEquals(29, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, calendar.get(Calendar.MONTH))
    }

    @Test
    fun timezoneChangesTheBoundary() {
        val now = at(kyiv, 2026, 7, 13, hour = 15)
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertNotEquals(millisUntilNextMidnight(now, kyiv), millisUntilNextMidnight(now, tokyo))
        assertIsMidnight(now + millisUntilNextMidnight(now, tokyo), tokyo)
    }

    @Test
    fun resultIsAlwaysPositive() {
        assertTrue(millisUntilNextMidnight(0L, kyiv) >= 1L)
        assertTrue(millisUntilNextMidnight(Long.MIN_VALUE / 4, kyiv) >= 1L)
    }
}
