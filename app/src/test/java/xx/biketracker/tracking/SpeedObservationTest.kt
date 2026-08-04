package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.AUTO_RESUME_HOLD_MS

class SpeedObservationTest {

    private fun fix(
        seconds: Long,
        lon: Double = 30.0,
        speedMps: Double? = null,
        accuracyMeters: Float = 2f,
    ) = ValidatedLocationFix(
        lat = 50.0,
        lon = lon,
        wallTimeMillis = 1_000L + seconds * 1_000L,
        elapsedRealtimeNanos = (1_000L + seconds * 1_000L) * 1_000_000L,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        altitudeMeters = null,
        bearingDegrees = null,
    )

    @Test
    fun aLongPositionOnlyRunAtRestProducesTrustworthyZeros() {
        var previous: ValidatedLocationFix? = null
        val hold = ObservationHold(15_000L)
        var paused = false
        for (seconds in 0L..21L step 3) {
            val current = fix(seconds)
            val observation = speedObservation(previous, current, sameObservationRun = previous != null)
            if (previous == null) assertNull(observation)
            else {
                assertEquals(SpeedObservationSource.COORDINATES, observation?.source)
                assertEquals(0.0, observation!!.metersPerSecond, 0.0)
                paused = paused || hold.observe(seconds * 1_000L, qualifies = true)
            }
            previous = current
        }
        assertTrue(paused)
    }

    @Test
    fun aLongPositionOnlyRunInMotionProducesCoordinateSpeed() {
        var previous = fix(0)
        repeat(5) { index ->
            val current = fix(
                seconds = (index + 1L) * 3L,
                lon = 30.0 + (index + 1) * 0.0003,
            )
            val observation = speedObservation(previous, current, sameObservationRun = true)
            assertEquals(SpeedObservationSource.COORDINATES, observation?.source)
            assertTrue(observation!!.metersPerSecond > 1.0)
            previous = current
        }
    }

    @Test
    fun receiverSpeedWinsAndMissingSpeedNeverInheritsIt() {
        val previous = fix(0, speedMps = 8.0)
        val reported = speedObservation(null, previous, sameObservationRun = false)
        assertEquals(SpeedObservationSource.RECEIVER, reported?.source)
        assertEquals(8.0, reported!!.metersPerSecond, 0.0)

        val missing = speedObservation(previous, fix(3), sameObservationRun = true)
        assertEquals(SpeedObservationSource.COORDINATES, missing?.source)
        assertEquals(0.0, missing!!.metersPerSecond, 0.0)
    }

    @Test
    fun gapsAndReanchorsDoNotInventSpeed() {
        assertNull(speedObservation(fix(0), fix(60, lon = 30.01), sameObservationRun = false))
    }

    @Test
    fun oneNoisyMovementObservationCannotResume() {
        val hold = ObservationHold(AUTO_RESUME_HOLD_MS)
        assertFalse(hold.observe(1_000L, qualifies = true))
        assertFalse(hold.observe(2_000L, qualifies = false))
        assertFalse(hold.observe(5_000L, qualifies = true))
    }
}
