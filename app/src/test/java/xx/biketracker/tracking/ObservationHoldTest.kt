package xx.biketracker.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.GPS_STALE_MS
import xx.biketracker.RECORDING_OUTAGE_MS
import xx.biketracker.STANDBY_DEPARTURE_HOLD_MS

/**
 * The "must hold across fixes" primitive behind standby auto-start, and the rule that decides when
 * a run of observations has been broken. Both are driven here at the two cadences that matter: the
 * standby request's own 4 s, and the tens of seconds a jammed or obstructed receiver delivers.
 */
class ObservationHoldTest {

    private val second = 1_000_000_000L

    private fun fixAt(elapsedSeconds: Long) = ValidatedLocationFix(
        lat = 50.0,
        lon = 30.0,
        wallTimeMillis = 1_700_000_000_000L + elapsedSeconds * 1_000L,
        elapsedRealtimeNanos = elapsedSeconds * second,
        accuracyMeters = 5f,
        speedMps = 4.0,
        altitudeMeters = null,
        bearingDegrees = null,
    )

    // ObservationHold ----------------------------------------------------------------------------

    @Test
    fun aLoneObservationNeverSatisfiesTheHold() {
        val hold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
        // However long it has been since anything was seen, one fix is one fix — the spoofed jump
        // this hold exists to reject looks exactly like this.
        assertFalse(hold.observe(nowMillis = 600_000L, qualifies = true))
    }

    @Test
    fun twoObservationsAtTheStandbyCadenceSatisfyIt() {
        val hold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
        assertFalse(hold.observe(0L, qualifies = true))
        assertTrue(hold.observe(4_000L, qualifies = true))
    }

    @Test
    fun twoObservationsOnASparseStreamSatisfyItToo() {
        // The regression this guards: fixes 20 s apart used to re-arm the hold on every one of
        // them, so standby could never confirm anything and the next ride was simply never
        // recorded.
        val hold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
        assertFalse(hold.observe(0L, qualifies = true))
        assertTrue(hold.observe(20_000L, qualifies = true))
    }

    @Test
    fun aBrokenStreakStartsOver() {
        val hold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
        assertFalse(hold.observe(0L, qualifies = true))
        assertFalse(hold.observe(4_000L, qualifies = false)) // back at the anchor: nothing happened
        assertFalse(hold.observe(8_000L, qualifies = true))  // and the next one is a lone fix again
        assertTrue(hold.observe(12_000L, qualifies = true))
    }

    @Test
    fun resetDiscardsEvidenceFromBeforeIt() {
        val hold = ObservationHold(STANDBY_DEPARTURE_HOLD_MS)
        assertFalse(hold.observe(0L, qualifies = true))
        hold.reset()
        assertFalse(hold.observe(300_000L, qualifies = true))
        assertTrue(hold.observe(304_000L, qualifies = true))
    }

    // brokeObservationRun ------------------------------------------------------------------------

    @Test
    fun theFirstFixOfARunAlwaysBreaksIt() {
        assertTrue(brokeObservationRun(previous = null, fix = fixAt(10), windowMs = GPS_STALE_MS))
        assertTrue(brokeObservationRun(previous = null, fix = fixAt(10), windowMs = RECORDING_OUTAGE_MS))
    }

    @Test
    fun autoPauseForgetsAcrossAnySilence() {
        // A fix back after 11 s of nothing must not settle a low-speed hold measured from before it.
        assertFalse(brokeObservationRun(fixAt(0), fixAt(10), GPS_STALE_MS))
        assertTrue(brokeObservationRun(fixAt(0), fixAt(11), GPS_STALE_MS))
    }

    @Test
    fun theStandbyHoldsSurviveAMerelySlowStream() {
        // 20 s and even a minute apart is a cadence, not an interruption...
        assertFalse(brokeObservationRun(fixAt(0), fixAt(20), RECORDING_OUTAGE_MS))
        assertFalse(brokeObservationRun(fixAt(0), fixAt(60), RECORDING_OUTAGE_MS))
        // ...but a silence no sampling interval accounts for still discards what came before it.
        assertTrue(brokeObservationRun(fixAt(0), fixAt(121), RECORDING_OUTAGE_MS))
    }
}
