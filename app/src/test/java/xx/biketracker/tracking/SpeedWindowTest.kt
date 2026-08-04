package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.MAX_SPEED_WINDOW_MS

/**
 * The rule behind a ride's maximum speed. Note what the window is never given: the speed the
 * receiver reported. A ride recorded under a signal jammed hard enough to blank the speed entirely
 * still gets a maximum here, out of the ground its positions covered.
 */
class SpeedWindowTest {

    /** Latitude [meters] north of the equator-agnostic origin, in haversine's own earth radius. */
    private fun lat(meters: Double) = 50.0 + meters / (6_371_000.0 * Math.PI / 180.0)

    // A fixed cadence to drive the window's arithmetic with; deliberately a literal rather than
    // GPS_INTERVAL_MS, so retuning the request cannot silently change what these cases mean.
    private val fix = 1_500L

    @Test
    fun aWindowNotYetSpannedCountsForNothing() {
        val window = SpeedWindow()
        assertNull(window.observe(0L, lat(0.0), 30.0))
        assertNull(window.observe(fix, lat(11.0), 30.0))
    }

    @Test
    fun aSpeedHeldAcrossTheWindowIsReported() {
        val window = SpeedWindow()
        window.observe(0L, lat(0.0), 30.0)
        window.observe(fix, lat(18.0), 30.0)
        // 36 m of ground in 3 s.
        assertEquals(12.0, window.observe(2 * fix, lat(36.0), 30.0)!!, 1e-6)
    }

    @Test
    fun anExcursionThatComesStraightBackCountsForNothing() {
        // One fix lands 37 m off the track and the next is back on it — a step the jump filter
        // admits at 25 m/s, and the shape a noisy fix actually has. It covers plenty of track and
        // no ground, so the ride is not credited with 90 km/h for it.
        val window = SpeedWindow()
        window.observe(0L, lat(0.0), 30.0)
        window.observe(fix, lat(37.0), 30.0)
        assertEquals(0.0, window.observe(2 * fix, lat(0.0), 30.0)!!, 1e-6)
    }

    @Test
    fun theWindowStaysTightAsTheRideGoesOn() {
        // A steady 12 m/s must read 12 m/s at every step, not drift as the ride lengthens: the
        // window is trimmed to the tightest span that still covers the minimum.
        val window = SpeedWindow()
        for (i in 0..9) {
            val speed = window.observe(i * fix, lat(i * 18.0), 30.0)
            if (i < 2) assertNull(speed) else assertEquals(12.0, speed!!, 1e-6)
        }
    }

    @Test
    fun aStandstillDrainsTheWindow() {
        val window = SpeedWindow()
        for (i in 0..9) window.observe(i * fix, lat(i * 18.0), 30.0)
        // The bike stops where it was; once the window holds only stopped fixes it reads zero.
        val stoppedAt = lat(9 * 18.0)
        window.observe(10 * fix, stoppedAt, 30.0)
        assertEquals(0.0, window.observe(11 * fix, stoppedAt, 30.0)!!, 1e-6)
    }

    @Test
    fun aResetKeepsTheWindowFromSpanningARecordingBreak() {
        val window = SpeedWindow()
        window.observe(0L, lat(0.0), 30.0)
        window.reset()
        // Kilometres away and minutes later, on the far side of a pause or an outage: the ride was
        // never observed covering that ground, so no speed comes of it.
        assertNull(window.observe(600_000L, lat(20_000.0), 30.0))
        assertEquals(0.0, window.observe(603_000L, lat(20_000.0), 30.0)!!, 1e-6)
    }

    @Test
    fun oneSparseStepStillSpansTheWindow() {
        // Where the signal is jammed the fixes are tens of seconds apart, and a single step is all
        // the evidence there is — but it is already far longer than the window asks for.
        val window = SpeedWindow()
        window.observe(0L, lat(0.0), 30.0)
        assertEquals(4.0, window.observe(20_000L, lat(80.0), 30.0)!!, 1e-6)
    }

    @Test
    fun noSingleStepAtTheNormalCadenceCanSpanTheWindow() {
        // The whole point of the window: one step, and therefore one fix's error, must never be
        // enough to set the ride's maximum. Two steps at the requested cadence are the minimum.
        assertTrue(MAX_SPEED_WINDOW_MS >= 2 * GPS_INTERVAL_MS)
    }
}
