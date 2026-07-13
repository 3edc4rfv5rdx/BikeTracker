package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.GPS_STALE_MS

class MonotonicTimeTest {

    @Test
    fun fixIntervalsUseElapsedRealtimeOnly() {
        val previousElapsed = 80_000_000_000L
        val currentElapsed = previousElapsed + 1_500_000_000L

        assertEquals(1_500L, elapsedMillisBetween(previousElapsed, currentElapsed))
        // Wall-clock values are intentionally absent: changing them cannot affect the interval.
    }

    @Test
    fun duplicateBackwardAndSubMillisecondFixesAreRejected() {
        assertNull(elapsedMillisBetween(10_000_000L, 10_000_000L))
        assertNull(elapsedMillisBetween(10_000_000L, 9_000_000L))
        assertNull(elapsedMillisBetween(10_000_000L, 10_999_999L))
    }

    @Test
    fun gpsStalenessUsesElapsedRealtime() {
        val updatedAt = 42_000L
        val snapshot = TrackingSnapshot(
            status = TrackingStatus.RECORDING,
            gpsAccuracyMeters = 5f,
            updatedAtElapsedRealtime = updatedAt,
        )

        assertFalse(snapshot.hasGpsTrouble(updatedAt + GPS_STALE_MS))
        assertTrue(snapshot.hasGpsTrouble(updatedAt + GPS_STALE_MS + 1))
    }
}
