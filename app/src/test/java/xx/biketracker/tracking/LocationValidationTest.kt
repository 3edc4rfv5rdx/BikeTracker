package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.FIX_REANCHOR_MS
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.settings.AUTO_RESUME_MARGIN_KMH
import xx.biketracker.settings.DEFAULT_AUTO_PAUSE_SPEED_KMH
import xx.biketracker.settings.resumeSpeedMps

class LocationValidationTest {

    private fun candidate(
        lat: Double = 50.0,
        lon: Double = 30.0,
        wallTimeMillis: Long = 1_700_000_000_000L,
        elapsedRealtimeNanos: Long = 10_000_000_000L,
        accuracyMeters: Float? = 5f,
        speedMps: Double? = 4.0,
        altitudeMeters: Double? = 120.0,
        bearingDegrees: Float? = 90f,
    ) = LocationFixCandidate(
        lat = lat,
        lon = lon,
        wallTimeMillis = wallTimeMillis,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        altitudeMeters = altitudeMeters,
        bearingDegrees = bearingDegrees,
    )

    /** The fix a caller would go on to record, or null for anything it must drop. */
    private fun accept(
        candidate: LocationFixCandidate,
        previous: ValidatedLocationFix? = null,
    ): ValidatedLocationFix? =
        (validateLocationFix(candidate, previous) as? FixValidation.Accepted)?.fix

    @Test
    fun inaccurateOrIncompleteFixesAreRejected() {
        assertNull(accept(candidate(accuracyMeters = 26f)))
        assertNull(accept(candidate(accuracyMeters = null)))
        assertNull(accept(candidate(elapsedRealtimeNanos = 0L)))
    }

    @Test
    fun nonFiniteTelemetryIsRejected() {
        assertNull(accept(candidate(lat = Double.NaN)))
        assertNull(accept(candidate(lon = Double.POSITIVE_INFINITY)))
        assertNull(accept(candidate(speedMps = Double.NaN)))
        assertNull(accept(candidate(altitudeMeters = Double.NEGATIVE_INFINITY)))
        assertNull(accept(candidate(bearingDegrees = Float.NaN)))
    }

    @Test
    fun missingOptionalTelemetryIsAcceptedWithoutInventingValues() {
        val fix = accept(candidate(speedMps = null, altitudeMeters = null, bearingDegrees = null))

        assertNotNull(fix)
        assertNull(fix!!.speedMps)
        assertNull(fix.altitudeMeters)
        assertNull(fix.bearingDegrees)
    }

    @Test
    fun excessiveReportedSpeedIsRejectedEvenForPlausibleCoordinates() {
        assertNull(accept(candidate(speedMps = MAX_PLAUSIBLE_SPEED_MPS + 0.1)))
    }

    @Test
    fun coordinateJumpIsRejectedBeforeItReachesTheFilter() {
        val previous = accept(candidate())!!
        val jump = candidate(
            lat = previous.lat + 0.01,
            elapsedRealtimeNanos = previous.elapsedRealtimeNanos + 1_000_000_000L,
        )

        assertNull(accept(jump, previous))
        assertTrue(validateLocationFix(jump, previous) is FixValidation.Jumped)
    }

    @Test
    fun aFreshReferenceKeepsTheJumpingFixOut() {
        val previous = accept(candidate())!!
        val jump = validateLocationFix(
            candidate(
                lat = previous.lat + 0.01,
                elapsedRealtimeNanos = previous.elapsedRealtimeNanos + FIX_REANCHOR_MS * 1_000_000L - 1,
            ),
            previous,
        )

        assertFalse(shouldReanchor(previous, (jump as FixValidation.Jumped).fix))
    }

    @Test
    fun aReferenceWithNoAcceptedSuccessorIsGivenUpOn() {
        // The spoofed reference the ride anchored on, and a genuine fix half a world away.
        val spoofed = accept(candidate())!!
        val genuine = validateLocationFix(
            candidate(
                lat = spoofed.lat + 5.0,
                elapsedRealtimeNanos = spoofed.elapsedRealtimeNanos + FIX_REANCHOR_MS * 1_000_000L,
            ),
            spoofed,
        )

        assertTrue(shouldReanchor(spoofed, (genuine as FixValidation.Jumped).fix))
    }

    @Test
    fun outOfOrderProviderDataIsDroppedRatherThanReanchoredOn() {
        val previous = accept(candidate())!!
        val stale = candidate(
            lat = previous.lat + 5.0,
            elapsedRealtimeNanos = previous.elapsedRealtimeNanos - 1_000_000_000L,
        )

        assertEquals(FixValidation.Rejected, validateLocationFix(stale, previous))
    }

    @Test
    fun validResumeSpeedFixIsAccepted() {
        val resumeSpeed = resumeSpeedMps(DEFAULT_AUTO_PAUSE_SPEED_KMH)
        val previous = accept(candidate())!!
        val resume = candidate(
            lat = previous.lat + 0.00001,
            elapsedRealtimeNanos = previous.elapsedRealtimeNanos + 1_000_000_000L,
            speedMps = resumeSpeed,
            altitudeMeters = null,
            bearingDegrees = null,
        )

        val validated = accept(resume, previous)
        assertNotNull(validated)
        assertEquals(resumeSpeed, validated!!.speedMps!!, 0.0)
    }

    @Test
    fun resumeThresholdSitsOneMarginAboveTheConfiguredPauseSpeed() {
        assertEquals(
            (DEFAULT_AUTO_PAUSE_SPEED_KMH + AUTO_RESUME_MARGIN_KMH) / 3.6,
            resumeSpeedMps(DEFAULT_AUTO_PAUSE_SPEED_KMH),
            1e-9,
        )
        // A raised pause threshold lifts the resume threshold by the same fixed margin.
        assertEquals(
            resumeSpeedMps(DEFAULT_AUTO_PAUSE_SPEED_KMH) + AUTO_RESUME_MARGIN_KMH / 3.6,
            resumeSpeedMps(DEFAULT_AUTO_PAUSE_SPEED_KMH + 1),
            1e-9,
        )
    }
}
