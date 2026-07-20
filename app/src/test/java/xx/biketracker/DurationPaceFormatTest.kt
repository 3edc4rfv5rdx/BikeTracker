package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for [formatDuration] (MM:SS below an hour, H:MM:SS at/above) and [formatPace]
 *  ("M:SS" per km, "—" without distance). Values assume the JVM's default en_US test locale. */
class DurationPaceFormatTest {

    @Test
    fun durationUnderAnHourIsMinutesAndSeconds() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun durationAtOrAboveAnHourGainsTheHoursField() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("10:00:00", formatDuration(36_000_000))
    }

    @Test
    fun paceIsMinutesPerKilometre() {
        assertEquals("5:00", formatPace(1_000.0, 300_000))   // 1 km in 5 min
        assertEquals("5:00", formatPace(500.0, 150_000))     // scales below a km
    }

    @Test
    fun paceRoundsToTheNearestSecond() {
        assertEquals("5:05", formatPace(1_000.0, 305_400))
        assertEquals("5:06", formatPace(1_000.0, 305_600))
    }

    @Test
    fun paceIsADashWithoutDistance() {
        assertEquals("—", formatPace(0.0, 300_000))
    }
}
