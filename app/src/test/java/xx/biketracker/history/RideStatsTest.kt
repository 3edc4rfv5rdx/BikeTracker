package xx.biketracker.history

import org.junit.Assert.assertEquals
import org.junit.Test
import xx.biketracker.data.TrackPoint

/**
 * Pure tests for [computeRideStats]. All timings are in millis; a "moving" fix uses 5 m/s (18 km/h,
 * the second speed bucket) and a "slow" fix uses 0.2 m/s (below the auto-pause threshold).
 */
class RideStatsTest {

    private fun tp(
        time: Long,
        speedMps: Float,
        elapsedMillis: Long? = time,
        segmentStart: Boolean = false,
        altitudeMeters: Double? = null,
    ) = TrackPoint(
        tripId = 1,
        lat = 50.0,
        lon = 30.0,
        time = time,
        speedMps = speedMps,
        altitudeMeters = altitudeMeters,
        segmentStart = segmentStart,
        elapsedMillis = elapsedMillis,
    )

    @Test
    fun forwardWallClockJumpDoesNotInflateTime() {
        // Elapsed time advances 1 s per fix; the wall clock leaps hours ahead mid-ride.
        val stats = computeRideStats(
            listOf(
                tp(time = 0, speedMps = 5f, elapsedMillis = 0),
                tp(time = 1_000, speedMps = 5f, elapsedMillis = 1_000),
                tp(time = 999_999_000, speedMps = 5f, elapsedMillis = 2_000),
                tp(time = 1_000_000_000, speedMps = 5f, elapsedMillis = 3_000),
            )
        )
        assertEquals(3_000L, stats.speedZoneMillis.sum())
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(0, stats.stopCount)
    }

    @Test
    fun backwardWallClockOnLegacyRowsIsClampedNotSubtracted() {
        // No elapsed metadata, so time falls back to the wall clock; the backward step clamps to 0.
        val stats = computeRideStats(
            listOf(
                tp(time = 0, speedMps = 5f, elapsedMillis = null),
                tp(time = 1_000, speedMps = 5f, elapsedMillis = null),
                tp(time = 500, speedMps = 5f, elapsedMillis = null),
                tp(time = 1_500, speedMps = 5f, elapsedMillis = null),
            )
        )
        assertEquals(2_000L, stats.speedZoneMillis.sum())
        assertEquals(0L, stats.stoppedMillis)
    }

    @Test
    fun segmentBoundaryGapIsNeverAStop() {
        // A 60 s pause/outage boundary between two moving fixes must not become stopped time.
        val stats = computeRideStats(
            listOf(
                tp(time = 0, speedMps = 5f, elapsedMillis = 0),
                tp(time = 1_000, speedMps = 5f, elapsedMillis = 1_000),
                tp(time = 61_000, speedMps = 5f, elapsedMillis = 61_000, segmentStart = true),
                tp(time = 62_000, speedMps = 5f, elapsedMillis = 62_000),
            )
        )
        assertEquals(0, stats.stopCount)
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(2_000L, stats.speedZoneMillis.sum())
    }

    @Test
    fun legacyWallGapBoundaryIsNeverAStop() {
        // Old rows detect the boundary by a wall-time gap; it is still excluded from stopped time.
        val stats = computeRideStats(
            listOf(
                tp(time = 0, speedMps = 5f, elapsedMillis = null),
                tp(time = 1_000, speedMps = 5f, elapsedMillis = null),
                tp(time = 61_000, speedMps = 5f, elapsedMillis = null),
                tp(time = 62_000, speedMps = 5f, elapsedMillis = null),
            )
        )
        assertEquals(0, stats.stopCount)
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(2_000L, stats.speedZoneMillis.sum())
    }

    @Test
    fun briefLowSpeedMotionEntersTheSlowestBucketNotAStop() {
        val stats = computeRideStats(
            listOf(
                tp(time = 0, speedMps = 5f, elapsedMillis = 0),
                tp(time = 1_000, speedMps = 0.2f, elapsedMillis = 1_000),
                tp(time = 2_000, speedMps = 0.2f, elapsedMillis = 2_000),
                tp(time = 3_000, speedMps = 5f, elapsedMillis = 3_000),
            )
        )
        assertEquals(0, stats.stopCount)
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(2_000L, stats.speedZoneMillis[0]) // the two slow seconds
        assertEquals(4_000L, stats.speedZoneMillis.sum())
    }

    @Test
    fun sustainedLowSpeedRunIsAStop() {
        val points = ArrayList<TrackPoint>()
        points += tp(time = 0, speedMps = 5f, elapsedMillis = 0)
        for (t in 1..12) points += tp(time = t * 1_000L, speedMps = 0.2f, elapsedMillis = t * 1_000L)
        points += tp(time = 13_000, speedMps = 5f, elapsedMillis = 13_000)
        val stats = computeRideStats(points)
        assertEquals(1, stats.stopCount)
        assertEquals(12_000L, stats.stoppedMillis)
    }

    @Test
    fun bucketsPlusStopsSumToNonBoundaryTimeAcrossMultipleSegments() {
        // Moving, a boundary, faster, another boundary, then a brief crawl into a moving fix.
        val points = listOf(
            tp(time = 0, speedMps = 5f, elapsedMillis = 0),
            tp(time = 1_000, speedMps = 5f, elapsedMillis = 1_000),
            tp(time = 31_000, speedMps = 5f, elapsedMillis = 31_000, segmentStart = true),
            tp(time = 32_000, speedMps = 8f, elapsedMillis = 32_000),
            tp(time = 92_000, speedMps = 8f, elapsedMillis = 92_000, segmentStart = true),
            tp(time = 93_000, speedMps = 0.2f, elapsedMillis = 93_000),
            tp(time = 94_000, speedMps = 5f, elapsedMillis = 94_000),
        )
        val stats = computeRideStats(points)
        // Both boundary gaps are excluded, leaving four 1 s non-boundary intervals.
        assertEquals(4_000L, stats.speedZoneMillis.sum() + stats.stoppedMillis)
        assertEquals(0, stats.stopCount)
        assertEquals(0L, stats.stoppedMillis)
    }
}
