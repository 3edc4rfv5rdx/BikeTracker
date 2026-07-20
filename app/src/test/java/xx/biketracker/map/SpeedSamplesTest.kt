package xx.biketracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GeoPoint

class SpeedSamplesTest {

    /** Points on one parallel, evenly spaced in longitude, so every step covers the same meters. */
    private fun point(
        index: Int,
        timeMillis: Long,
        speedMps: Float = 5f,
        segmentStart: Boolean = false,
        elapsedMillis: Long? = null,
    ) = GeoPoint(50.0, 30.0 + index * 1e-4, timeMillis, speedMps, segmentStart, elapsedMillis)

    @Test
    fun fewerThanTwoPointsYieldNoSamples() {
        assertEquals(0, buildSpeedSamples(emptyList()).size)
        assertEquals(0, buildSpeedSamples(listOf(point(0, 1_000L))).size)
    }

    @Test
    fun oneSamplePerRoutePoint() {
        val route = (0..9).map { point(it, it * GPS_INTERVAL_MS) }
        assertEquals(route.size, buildSpeedSamples(route).size)
    }

    @Test
    fun distanceAccumulatesButNotAcrossRecordingGaps() {
        val route = listOf(
            point(0, 1_000L),
            point(1, 1_000L + GPS_INTERVAL_MS),
            point(2, 300_000L), // long pause before this fix: a recording gap
            point(3, 300_000L + GPS_INTERVAL_MS),
        )
        val samples = buildSpeedSamples(route)
        val step = samples[1].distanceMeters
        assertTrue(step > 0)
        assertEquals(samples[1].distanceMeters, samples[2].distanceMeters, 1e-9)
        assertEquals(step, samples[3].distanceMeters - samples[2].distanceMeters, 1e-6)
        // Moving time follows the same rule: the pause gap contributes nothing.
        assertEquals(GPS_INTERVAL_MS, samples[1].movingTimeMillis)
        assertEquals(GPS_INTERVAL_MS, samples[2].movingTimeMillis)
        assertEquals(2 * GPS_INTERVAL_MS, samples[3].movingTimeMillis)
    }

    @Test
    fun speedsAreMovingAveraged() {
        // Three points fit entirely inside the smoothing window, so every
        // sample carries the plain average of all raw speeds.
        val route = listOf(
            point(0, 1_000L, speedMps = 0f),
            point(1, 2_000L, speedMps = 10f),
            point(2, 3_000L, speedMps = 20f),
        )
        buildSpeedSamples(route).forEach { assertEquals(10f, it.speedMps, 1e-6f) }
    }

    @Test
    fun untimedPointsNeverGap() {
        val route = (0..3).map { point(it, timeMillis = 0L) }
        val samples = buildSpeedSamples(route)
        assertTrue(samples.last().distanceMeters > samples[1].distanceMeters)
    }

    /** A stopped segment, then a fast one split by an explicit boundary two seconds later. */
    private fun stoppedThenFastRide(): List<GeoPoint> {
        val stopped = (0..4).map { point(it, it * GPS_INTERVAL_MS, speedMps = 0f) }
        val resumeAt = 4 * GPS_INTERVAL_MS + 2_000L
        val fast = (5..9).map {
            point(it, resumeAt + (it - 5) * GPS_INTERVAL_MS, speedMps = 20f, segmentStart = it == 5)
        }
        return stopped + fast
    }

    @Test
    fun smoothingNeverCrossesASegmentBoundary() {
        val samples = buildSpeedSamples(stoppedThenFastRide())
        // The window stays inside each segment, so the stopped tail and the fast head keep their
        // own averages instead of bleeding across the pause.
        assertEquals(0f, samples[4].speedMps, 1e-6f)
        assertEquals(20f, samples[5].speedMps, 1e-6f)
    }

    @Test
    fun flaggedBoundaryBreaksDistanceEvenUnderTheGapThreshold() {
        val samples = buildSpeedSamples(stoppedThenFastRide())
        assertTrue(samples[5].segmentStart)
        // The 2 s gap is under the stale threshold, yet the boundary adds neither distance nor time.
        assertEquals(samples[4].distanceMeters, samples[5].distanceMeters, 1e-9)
        assertEquals(samples[4].movingTimeMillis, samples[5].movingTimeMillis)
    }

    @Test
    fun timeAxisIsMonotonicDespiteBackwardClockJump() {
        // New ride carrying persisted elapsed time; the epoch clock steps backward mid-ride.
        val route = listOf(
            point(0, 100_000L, elapsedMillis = 0L),
            point(1, 40_000L, elapsedMillis = 1_000L),
            point(2, 41_000L, elapsedMillis = 2_000L),
            point(3, 42_000L, elapsedMillis = 3_000L),
        )
        val samples = buildSpeedSamples(route)
        for (i in 1 until samples.size) {
            assertTrue(samples[i].elapsedMillis >= samples[i - 1].elapsedMillis)
        }
        assertEquals(3_000L, samples.last().elapsedMillis)
    }

    @Test
    fun forwardClockJumpWithinSegmentDoesNotInflateMovingTimeOrSplit() {
        val route = listOf(
            point(0, 1_000L, elapsedMillis = 0L),
            point(1, 3_600_000L, elapsedMillis = 1_000L), // +1 h clock jump, no real pause
            point(2, 3_601_000L, elapsedMillis = 2_000L),
        )
        val samples = buildSpeedSamples(route)
        // Moving time follows the monotonic elapsed clock, not the inflated epoch delta.
        assertEquals(2_000L, samples.last().movingTimeMillis)
        // And the jump is not misread as a segment boundary.
        assertTrue(samples.none { it.segmentStart })
    }

    @Test
    fun oldRideWithoutElapsedClampsBackwardEpochJump() {
        // No elapsed metadata: the axis is rebuilt from epoch deltas, clamped so it never reverses.
        val route = listOf(point(0, 100_000L), point(1, 40_000L), point(2, 41_000L))
        val samples = buildSpeedSamples(route)
        for (i in 1 until samples.size) {
            assertTrue(samples[i].elapsedMillis >= samples[i - 1].elapsedMillis)
        }
    }

    @Test
    fun segmentShorterThanTheWindowAveragesWithinItself() {
        // A two-point second segment must average only its own points, not reach into the first.
        val first = (0..4).map { point(it, it * GPS_INTERVAL_MS, speedMps = 2f) }
        val resumeAt = 4 * GPS_INTERVAL_MS + 2_000L
        val second = listOf(
            point(5, resumeAt, speedMps = 10f, segmentStart = true),
            point(6, resumeAt + GPS_INTERVAL_MS, speedMps = 10f),
        )
        val samples = buildSpeedSamples(first + second)
        assertEquals(10f, samples[5].speedMps, 1e-6f)
        assertEquals(10f, samples[6].speedMps, 1e-6f)
    }
}
