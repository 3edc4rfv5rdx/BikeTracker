package xx.biketracker

import java.util.Calendar
import java.util.Locale
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

    private fun withFirstDayOfWeek(day: Int, block: () -> Unit) {
        val previous = Locale.getDefault()
        // Locale drives Calendar.firstDayOfWeek: Monday in fr-FR, Sunday in en-US.
        Locale.setDefault(if (day == Calendar.MONDAY) Locale.FRANCE else Locale.US)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun startOfWeekLandsOnLocaleFirstDayAtMidnight() {
        // 2026-07-15 is a Wednesday.
        val now = at(kyiv, 2026, 7, 15, hour = 9)
        withFirstDayOfWeek(Calendar.MONDAY) {
            val start = startOfWeekMillis(now, kyiv)
            assertEquals(at(kyiv, 2026, 7, 13), start) // Monday
            assertIsMidnight(start, kyiv)
        }
        withFirstDayOfWeek(Calendar.SUNDAY) {
            val start = startOfWeekMillis(now, kyiv)
            assertEquals(at(kyiv, 2026, 7, 12), start) // Sunday
            assertIsMidnight(start, kyiv)
        }
    }

    @Test
    fun startOfWeekOnTheFirstDayReturnsThatMidnight() {
        withFirstDayOfWeek(Calendar.MONDAY) {
            val monday = at(kyiv, 2026, 7, 13, hour = 23, minute = 59)
            assertEquals(at(kyiv, 2026, 7, 13), startOfWeekMillis(monday, kyiv))
        }
    }

    @Test
    fun startOfMonthIsFirstDayAtMidnight() {
        val now = at(kyiv, 2026, 7, 15, hour = 9)
        val start = startOfMonthMillis(now, kyiv)
        assertEquals(at(kyiv, 2026, 7, 1), start)
        assertIsMidnight(start, kyiv)
    }

    @Test
    fun startOfYearIsJanuaryFirstAtMidnight() {
        val now = at(kyiv, 2026, 7, 15, hour = 9)
        val start = startOfYearMillis(now, kyiv)
        assertEquals(at(kyiv, 2026, 1, 1), start)
        assertIsMidnight(start, kyiv)
    }

    @Test
    fun periodStartsHonorTheTimezone() {
        val now = at(kyiv, 2026, 7, 15, hour = 9)
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertIsMidnight(startOfMonthMillis(now, tokyo), tokyo)
        assertIsMidnight(startOfYearMillis(now, tokyo), tokyo)
        withFirstDayOfWeek(Calendar.MONDAY) {
            assertIsMidnight(startOfWeekMillis(now, tokyo), tokyo)
        }
    }
}
