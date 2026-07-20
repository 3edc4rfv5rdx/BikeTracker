package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
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

    @Test
    fun inaccurateOrIncompleteFixesAreRejected() {
        assertNull(validateLocationFix(candidate(accuracyMeters = 26f), previous = null))
        assertNull(validateLocationFix(candidate(accuracyMeters = null), previous = null))
        assertNull(validateLocationFix(candidate(elapsedRealtimeNanos = 0L), previous = null))
    }

    @Test
    fun nonFiniteTelemetryIsRejected() {
        assertNull(validateLocationFix(candidate(lat = Double.NaN), previous = null))
        assertNull(validateLocationFix(candidate(lon = Double.POSITIVE_INFINITY), previous = null))
        assertNull(validateLocationFix(candidate(speedMps = Double.NaN), previous = null))
        assertNull(validateLocationFix(candidate(altitudeMeters = Double.NEGATIVE_INFINITY), previous = null))
        assertNull(validateLocationFix(candidate(bearingDegrees = Float.NaN), previous = null))
    }

    @Test
    fun missingOptionalTelemetryIsAcceptedWithoutInventingValues() {
        val fix = validateLocationFix(
            candidate(speedMps = null, altitudeMeters = null, bearingDegrees = null),
            previous = null,
        )

        assertNotNull(fix)
        assertNull(fix!!.speedMps)
        assertNull(fix.altitudeMeters)
        assertNull(fix.bearingDegrees)
    }

    @Test
    fun excessiveReportedSpeedIsRejectedEvenForPlausibleCoordinates() {
        assertNull(
            validateLocationFix(
                candidate(speedMps = MAX_PLAUSIBLE_SPEED_MPS + 0.1),
                previous = null,
            )
        )
    }

    @Test
    fun coordinateJumpIsRejectedBeforeItReachesTheFilter() {
        val previous = validateLocationFix(candidate(), previous = null)!!
        val jump = candidate(
            lat = previous.lat + 0.01,
            elapsedRealtimeNanos = previous.elapsedRealtimeNanos + 1_000_000_000L,
        )

        assertNull(validateLocationFix(jump, previous))
    }

    @Test
    fun validResumeSpeedFixIsAccepted() {
        val resumeSpeed = resumeSpeedMps(DEFAULT_AUTO_PAUSE_SPEED_KMH)
        val previous = validateLocationFix(candidate(), previous = null)!!
        val resume = candidate(
            lat = previous.lat + 0.00001,
            elapsedRealtimeNanos = previous.elapsedRealtimeNanos + 1_000_000_000L,
            speedMps = resumeSpeed,
            altitudeMeters = null,
            bearingDegrees = null,
        )

        val validated = validateLocationFix(resume, previous)
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
