package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the base [elevationGainMeters] (no segment boundaries); the default 3 m
 *  threshold means only a rise past 3 m above the running reference counts. */
class ElevationGainMetersTest {

    @Test
    fun steadyClimbAccumulatesEachStep() {
        assertEquals(8.0, elevationGainMeters(listOf(100.0, 104.0, 108.0)), 1e-9)
    }

    @Test
    fun smallStepsDoNotRaiseTheReference() {
        // 100 -> 102 is under 3 m, so it neither counts nor moves the reference off 100; the
        // later 104 is then measured from 100 (a 4 m gain), not from 102.
        assertEquals(4.0, elevationGainMeters(listOf(100.0, 102.0, 104.0)), 1e-9)
    }

    @Test
    fun descentLowersTheReferenceSoTheNextClimbCountsFromTheLowPoint() {
        // Drop to 95 lowers the reference; the climb to 99 is then a 4 m gain from 95.
        assertEquals(4.0, elevationGainMeters(listOf(100.0, 95.0, 99.0)), 1e-9)
    }

    @Test
    fun nullAltitudesAreSkipped() {
        assertEquals(5.0, elevationGainMeters(listOf(100.0, null, 105.0)), 1e-9)
    }

    @Test
    fun emptyOrSinglePointHasNoGain() {
        assertEquals(0.0, elevationGainMeters(emptyList()), 1e-9)
        assertEquals(0.0, elevationGainMeters(listOf(100.0)), 1e-9)
    }

    @Test
    fun thresholdIsConfigurable() {
        // With a 1 m threshold the 2 m step now counts.
        assertEquals(2.0, elevationGainMeters(listOf(100.0, 102.0), thresholdMeters = 1.0), 1e-9)
    }
}
