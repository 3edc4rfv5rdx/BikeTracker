package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the point-to-point timing helpers: gap detection, segment boundaries and the
 *  monotonic step between two recorded fixes. GPS_STALE_MS is 10 s. */
class RecordingSegmentTest {

    // isRecordingGap -----------------------------------------------------------------------------

    @Test
    fun gapNeedsBothTimestampsPresent() {
        // Zero timestamps (old data recorded before times reached the route) never gap.
        assertFalse(isRecordingGap(0, 20_000))
        assertFalse(isRecordingGap(1_000, 0))
    }

    @Test
    fun gapTriggersOnlyPastTheStaleWindow() {
        assertFalse(isRecordingGap(1_000, 1_000 + GPS_STALE_MS))       // exactly 10 s: not yet
        assertTrue(isRecordingGap(1_000, 1_000 + GPS_STALE_MS + 1))    // one past 10 s: gap
    }

    @Test
    fun backwardWallTimeIsNotAGap() {
        assertFalse(isRecordingGap(5_000, 500))
    }

    // isSegmentBoundary --------------------------------------------------------------------------

    @Test
    fun flaggedSegmentStartIsAlwaysABoundary() {
        // Even with elapsed metadata and no wall gap, the explicit flag wins.
        assertTrue(isSegmentBoundary(1_000, 1_500, segmentStart = true, hasElapsedMetadata = true))
    }

    @Test
    fun newRidesIgnoreTheWallTimeGap() {
        // A big wall jump on a ride that carries elapsed metadata is a clock change, not a pause.
        assertFalse(
            isSegmentBoundary(1_000, 1_000_000, segmentStart = false, hasElapsedMetadata = true),
        )
    }

    @Test
    fun legacyRidesFallBackToTheWallTimeGap() {
        assertTrue(
            isSegmentBoundary(1_000, 1_000 + GPS_STALE_MS + 1, segmentStart = false, hasElapsedMetadata = false),
        )
        assertFalse(
            isSegmentBoundary(1_000, 1_500, segmentStart = false, hasElapsedMetadata = false),
        )
    }

    // monotonicStepMillis ------------------------------------------------------------------------

    @Test
    fun stepUsesElapsedDeltaWhenBothPointsCarryIt() {
        // Wall clock is deliberately inconsistent to prove it's ignored when elapsed is present.
        assertEquals(2_500L, monotonicStepMillis(1_000, 3_500, 999_999, 0))
    }

    @Test
    fun stepFallsBackToWallDeltaForLegacyRows() {
        assertEquals(3_000L, monotonicStepMillis(null, 5_000, 1_000, 4_000))
        assertEquals(3_000L, monotonicStepMillis(1_000, null, 1_000, 4_000))
    }

    @Test
    fun stepClampsBackwardCorrectionsToZero() {
        assertEquals(0L, monotonicStepMillis(5_000, 4_000, 1_000, 9_000))   // elapsed ran backward
        assertEquals(0L, monotonicStepMillis(null, null, 5_000, 4_000))     // wall ran backward
    }
}
