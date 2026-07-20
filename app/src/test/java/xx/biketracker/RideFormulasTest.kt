package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the speed / energy / distance formulas in Common.kt. */
class RideFormulasTest {

    // avgSpeedMps --------------------------------------------------------------------------------

    @Test
    fun averageSpeedIsDistanceOverTime() {
        assertEquals(10.0, avgSpeedMps(1_000.0, 100_000), 1e-9)   // 1000 m in 100 s
    }

    @Test
    fun averageSpeedIsZeroWithoutMovingTime() {
        assertEquals(0.0, avgSpeedMps(500.0, 0), 1e-9)
        assertEquals(0.0, avgSpeedMps(500.0, -5), 1e-9)
    }

    // cyclingMet ---------------------------------------------------------------------------------

    @Test
    fun cyclingMetPicksTheEffortBand() {
        assertEquals(4.0, cyclingMet(10.0), 1e-9)
        assertEquals(6.8, cyclingMet(18.0), 1e-9)
        assertEquals(8.0, cyclingMet(20.0), 1e-9)
        assertEquals(10.0, cyclingMet(23.0), 1e-9)
        assertEquals(12.0, cyclingMet(27.0), 1e-9)
        assertEquals(15.8, cyclingMet(35.0), 1e-9)
    }

    @Test
    fun cyclingMetBandsAreLowerInclusive() {
        // Each threshold belongs to the faster band: < is exclusive at the top of the slower one.
        assertEquals(6.8, cyclingMet(16.0), 1e-9)
        assertEquals(8.0, cyclingMet(19.0), 1e-9)
        assertEquals(10.0, cyclingMet(22.0), 1e-9)
        assertEquals(12.0, cyclingMet(25.0), 1e-9)
        assertEquals(15.8, cyclingMet(30.0), 1e-9)
    }

    // caloriesKcal -------------------------------------------------------------------------------

    @Test
    fun caloriesAreMetTimesWeightTimesHours() {
        // 20 km in 1 h => 20 km/h => MET 8.0; 8 * 70 kg * 1 h = 560 kcal.
        assertEquals(560.0, caloriesKcal(20_000.0, 3_600_000, 70), 1e-6)
    }

    @Test
    fun caloriesNeedWeightAndMovingTime() {
        assertEquals(0.0, caloriesKcal(20_000.0, 3_600_000, 0), 1e-9)
        assertEquals(0.0, caloriesKcal(20_000.0, 0, 70), 1e-9)
    }

    // haversineMeters ----------------------------------------------------------------------------

    @Test
    fun distanceIsZeroForTheSamePoint() {
        assertEquals(0.0, haversineMeters(50.45, 30.52, 50.45, 30.52), 1e-9)
    }

    @Test
    fun oneDegreeSpansAboutOneEleventhOfAThousandKm() {
        // One degree of latitude anywhere, and one of longitude at the equator, is ~111.195 km.
        assertEquals(111_195.0, haversineMeters(0.0, 0.0, 1.0, 0.0), 5.0)
        assertEquals(111_195.0, haversineMeters(0.0, 0.0, 0.0, 1.0), 5.0)
    }

    @Test
    fun distanceIsSymmetric() {
        val a = haversineMeters(50.45, 30.52, 49.84, 24.03)
        val b = haversineMeters(49.84, 24.03, 50.45, 30.52)
        assertEquals(a, b, 1e-6)
    }
}
