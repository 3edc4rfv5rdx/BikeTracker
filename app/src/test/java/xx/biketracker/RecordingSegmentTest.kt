package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the point-to-point timing helpers: gap detection, segment boundaries and the
 *  monotonic step between two recorded fixes. RECORDING_OUTAGE_MS is 120 s. */
class RecordingSegmentTest {

    // isRecordingGap -----------------------------------------------------------------------------

    @Test
    fun sparseButExplicableSamplingIsNotAGap() {
        // Where the signal is jammed the fixes arrive tens of seconds apart and the ride goes on:
        // ground a bike could have covered in the time is real movement, not a break.
        assertFalse(isRecordingGap(20_000, stepMeters = 60.0))   // 3 m/s over 20 s
        assertFalse(isRecordingGap(60_000, stepMeters = 900.0))  // 15 m/s over a minute
        assertFalse(isRecordingGap(90_000, stepMeters = 0.0))    // stood still, still observed
    }

    @Test
    fun silencePastTheOutageWindowIsAlwaysAGap() {
        assertFalse(isRecordingGap(RECORDING_OUTAGE_MS, stepMeters = 0.0))     // exactly: not yet
        assertTrue(isRecordingGap(RECORDING_OUTAGE_MS + 1, stepMeters = 0.0))  // one past: gap
        // Nothing about the step can rescue a silence that long — it was never observed.
        assertTrue(isRecordingGap(RECORDING_OUTAGE_MS + 1, stepMeters = 100.0))
    }

    @Test
    fun aStepNobodyCouldHaveRiddenIsAGap() {
        val limit = MAX_PLAUSIBLE_SPEED_MPS
        assertFalse(isRecordingGap(1_000, stepMeters = limit))       // exactly at the limit
        assertTrue(isRecordingGap(1_000, stepMeters = limit + 1.0))  // past it: a jump, not a ride
    }

    @Test
    fun backwardOrZeroTimeIsNeverAGap() {
        assertFalse(isRecordingGap(0, stepMeters = 1_000.0))
        assertFalse(isRecordingGap(-5_000, stepMeters = 1_000.0))
    }

    // isSegmentBoundary --------------------------------------------------------------------------

    @Test
    fun flaggedSegmentStartIsAlwaysABoundary() {
        // Even with elapsed metadata and no wall gap, the explicit flag wins.
        assertTrue(
            isSegmentBoundary(1_000, 1_500, segmentStart = true, hasElapsedMetadata = true) { 0.0 },
        )
    }

    @Test
    fun newRidesIgnoreTheWallClockEntirely() {
        // A big wall jump on a ride that carries elapsed metadata is a clock change, not a pause.
        assertFalse(
            isSegmentBoundary(1_000, 1_000_000, segmentStart = false, hasElapsedMetadata = true) { 0.0 },
        )
    }

    @Test
    fun legacyRidesFallBackToTheWallTimeGap() {
        // A silence past the outage window splits an old ride...
        assertTrue(
            isSegmentBoundary(
                1_000, 1_000 + RECORDING_OUTAGE_MS + 1, segmentStart = false, hasElapsedMetadata = false,
            ) { 0.0 },
        )
        // ...but a sparsely sampled stretch of real riding does not.
        assertFalse(
            isSegmentBoundary(1_000, 31_000, segmentStart = false, hasElapsedMetadata = false) { 90.0 },
        )
    }

    @Test
    fun untimedLegacyPointsNeverSplit() {
        // Old data recorded before times reached the track can't be measured against anything.
        assertFalse(
            isSegmentBoundary(0, 900_000, segmentStart = false, hasElapsedMetadata = false) { 0.0 },
        )
        assertFalse(
            isSegmentBoundary(1_000, 0, segmentStart = false, hasElapsedMetadata = false) { 0.0 },
        )
    }

    @Test
    fun theStepIsOnlyMeasuredOnTheLegacyPath() {
        // Callers walking a whole track rely on this: computing the distance is not free.
        var measured = 0
        isSegmentBoundary(1_000, 1_500, segmentStart = true, hasElapsedMetadata = false) {
            measured++
            0.0
        }
        isSegmentBoundary(1_000, 900_000, segmentStart = false, hasElapsedMetadata = true) {
            measured++
            0.0
        }
        assertEquals(0, measured)
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
