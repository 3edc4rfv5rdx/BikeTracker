package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Test
import xx.biketracker.data.TrackPoint

/** Pure tests for [elevationGainBySegment]; the 3 m threshold means only rises past 3 m count. */
class ElevationGainTest {

    private fun tp(
        time: Long,
        altitude: Double?,
        elapsedMillis: Long? = time,
        segmentStart: Boolean = false,
    ) = TrackPoint(
        tripId = 1,
        lat = 50.0,
        lon = 30.0,
        time = time,
        speedMps = 5f,
        altitudeMeters = altitude,
        segmentStart = segmentStart,
        elapsedMillis = elapsedMillis,
    )

    @Test
    fun climbWithinASegmentAccumulatesPastTheThreshold() {
        val pts = listOf(tp(0, 100.0), tp(1_000, 104.0), tp(2_000, 108.0))
        assertEquals(8.0, elevationGainBySegment(pts), 1e-9)
    }

    @Test
    fun jumpAcrossAFlaggedPauseIsNotAClimb() {
        val pts = listOf(
            tp(0, 100.0), tp(1_000, 102.0),
            tp(2_000, 130.0, segmentStart = true), tp(3_000, 133.0),
        )
        // Only the 3 m rise inside the second segment counts; the 28 m step across the pause doesn't.
        assertEquals(3.0, elevationGainBySegment(pts), 1e-9)
    }

    @Test
    fun jumpAcrossALegacyOutageGapIsNotAClimb() {
        // No elapsed metadata plus a > 10 s wall gap is a boundary via the legacy heuristic.
        val pts = listOf(
            tp(0, 100.0, elapsedMillis = null),
            tp(1_000, 102.0, elapsedMillis = null),
            tp(20_000, 130.0, elapsedMillis = null),
            tp(21_000, 133.0, elapsedMillis = null),
        )
        assertEquals(3.0, elevationGainBySegment(pts), 1e-9)
    }

    @Test
    fun nullAltitudesAreSkipped() {
        val pts = listOf(tp(0, 100.0), tp(1_000, null), tp(2_000, 105.0))
        assertEquals(5.0, elevationGainBySegment(pts), 1e-9)
    }

    @Test
    fun onePointSegmentsContributeNothing() {
        val pts = listOf(
            tp(0, 100.0),
            tp(1_000, 200.0, segmentStart = true),
            tp(2_000, 50.0, segmentStart = true),
        )
        assertEquals(0.0, elevationGainBySegment(pts), 1e-9)
    }

    @Test
    fun descentMeasuresDropsWithinASegmentOnly() {
        val pts = listOf(
            tp(0, 100.0), tp(1_000, 96.0), tp(2_000, 92.0),
            tp(3_000, 200.0, segmentStart = true), tp(4_000, 197.0),
        )
        // Segment 1 drops 8 m and segment 2 drops 3 m; the +108 m jump across the boundary is ignored.
        assertEquals(11.0, elevationGainBySegment(pts, descent = true), 1e-9)
    }
}
