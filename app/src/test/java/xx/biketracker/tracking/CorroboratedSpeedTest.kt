package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorroboratedSpeedTest {

    @Test
    fun aSpeedTheTrackCoversIsKept() {
        // 10 m/s claimed, 15 m covered in 1.5 s — the receiver and the track agree.
        assertEquals(
            10.0,
            corroboratedSpeedMps(reportedMps = 10.0, stepMeters = 15.0, dtMillis = 1_500L)!!,
            1e-9,
        )
    }

    @Test
    fun aDopplerSpeedRunningAheadOfTheSmoothedTrackIsStillKept() {
        // Smoothing shortens the step, so the track always lags the reported speed a little.
        assertEquals(
            11.0,
            corroboratedSpeedMps(reportedMps = 11.0, stepMeters = 12.0, dtMillis = 1_500L)!!,
            1e-9,
        )
    }

    @Test
    fun aSpeedInventedForAStandingBikeIsDropped() {
        // 35 km/h claimed while the track moved 30 cm — jamming noise, not a sprint.
        assertNull(corroboratedSpeedMps(reportedMps = 9.7, stepMeters = 0.3, dtMillis = 1_500L))
    }

    @Test
    fun nothingIsCorroboratedWithoutASpeedOrATimeStep() {
        assertNull(corroboratedSpeedMps(reportedMps = null, stepMeters = 15.0, dtMillis = 1_500L))
        assertNull(corroboratedSpeedMps(reportedMps = 10.0, stepMeters = 15.0, dtMillis = 0L))
    }

    @Test
    fun aStandstillReportedHonestlyCostsNothing() {
        // A parked bike reporting 0 is corroborated by a track that also went nowhere.
        assertEquals(
            0.0,
            corroboratedSpeedMps(reportedMps = 0.0, stepMeters = 0.0, dtMillis = 1_500L)!!,
            1e-9,
        )
    }
}
