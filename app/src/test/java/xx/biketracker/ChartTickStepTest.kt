package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The X-axis tick step must keep the number of tick intervals within [MAX_TICKS] for every finite
 * positive span, and never return a non-positive or non-finite step that would hang the draw loop.
 */
class ChartTickStepTest {

    private val maxTicks = 6

    private fun assertBounded(step: Double, span: Double) {
        assertTrue("step must be finite and positive, was $step", step.isFinite() && step > 0.0)
        // At most maxTicks intervals fit the span, so the axis never overflows its tick budget.
        assertTrue("span/step=${span / step} exceeds $maxTicks", span / step <= maxTicks + 1e-9)
    }

    @Test
    fun distanceStepStaysBoundedAcrossScales() {
        val spans = doubleArrayOf(
            5.0, 50.0, 250.0,          // very short / zoomed windows (meters)
            1_000.0, 12_345.0,         // ordinary rides
            600_000.0, 1_000_000.0,    // 600 km, 1000 km
            50_000_000.0,              // absurdly long import
        )
        for (span in spans) assertBounded(distanceTickStepMeters(span, maxTicks), span)
    }

    @Test
    fun timeStepStaysBoundedAcrossScales() {
        val minute = 60_000.0
        val spans = doubleArrayOf(
            5_000.0, 30_000.0,                 // seconds-scale zoomed windows
            10 * minute, 90 * minute,          // ordinary rides
            5 * 60 * minute,                   // 5 hours
            3 * 24 * 60 * minute,              // 3-day ride
            60 * 24 * 60 * minute,             // 60-day import
        )
        for (span in spans) assertBounded(timeTickStepMillis(span, maxTicks), span)
    }

    @Test
    fun ordinaryRidesUseTheExpectedRoundStep() {
        // A ~12 km window: 2 km step gives 6 intervals, the finest that fits the budget.
        assertEquals(2_000.0, distanceTickStepMeters(12_000.0, maxTicks), 0.0)
        // A 90-minute window: 15 min step gives 6 intervals.
        assertEquals(15 * 60_000.0, timeTickStepMillis(90 * 60_000.0, maxTicks), 0.0)
    }

    @Test
    fun degenerateSpansYieldAFinitePositiveStep() {
        for (bad in doubleArrayOf(0.0, -100.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(distanceTickStepMeters(bad, maxTicks).let { it.isFinite() && it > 0.0 })
            assertTrue(timeTickStepMillis(bad, maxTicks).let { it.isFinite() && it > 0.0 })
        }
        // A non-positive tick budget must not divide by zero or loop forever.
        assertTrue(distanceTickStepMeters(1_000.0, 0).let { it.isFinite() && it > 0.0 })
        assertTrue(timeTickStepMillis(60_000.0, 0).let { it.isFinite() && it > 0.0 })
    }

    @Test
    fun niceTickStepRoundsUpTo125() {
        assertEquals(1.0, niceTickStep(0.7), 0.0)
        assertEquals(2.0, niceTickStep(1.3), 0.0)
        assertEquals(5.0, niceTickStep(4.1), 0.0)
        assertEquals(10.0, niceTickStep(6.0), 0.0)
        assertEquals(200.0, niceTickStep(150.0), 0.0)
        assertEquals(1.0, niceTickStep(Double.NaN), 0.0)
        assertEquals(1.0, niceTickStep(-3.0), 0.0)
    }
}
