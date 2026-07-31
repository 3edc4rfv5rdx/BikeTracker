package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test
import xx.biketracker.GPS_STALE_MS

class LiveMovingTimeTest {

    private fun recording(
        movingTimeMillis: Long = 60_000L,
        updatedAtElapsedRealtime: Long = 100_000L,
        lastTrustedFixElapsedRealtime: Long = 100_000L,
        status: TrackingStatus = TrackingStatus.RECORDING,
    ) = TrackingSnapshot(
        status = status,
        movingTimeMillis = movingTimeMillis,
        updatedAtElapsedRealtime = updatedAtElapsedRealtime,
        lastTrustedFixElapsedRealtime = lastTrustedFixElapsedRealtime,
    )

    @Test
    fun theTimerTicksLocallyBetweenFixes() {
        assertEquals(62_000L, recording().liveMovingTimeMillis(nowElapsedRealtime = 102_000L))
    }

    @Test
    fun theTimerStopsWhereRecordingStopsCounting() {
        val snapshot = recording()

        // Still inside the gap the recorder will credit.
        assertEquals(
            60_000L + GPS_STALE_MS,
            snapshot.liveMovingTimeMillis(nowElapsedRealtime = 100_000L + GPS_STALE_MS),
        )
        // Past it the outage counts for nothing, so the display must not run on and then
        // jump backwards when the signal returns.
        assertEquals(
            60_000L + GPS_STALE_MS,
            snapshot.liveMovingTimeMillis(nowElapsedRealtime = 400_000L),
        )
    }

    @Test
    fun aRideWithoutASingleFixShowsNoTime() {
        val snapshot = recording(movingTimeMillis = 0L, lastTrustedFixElapsedRealtime = 0L)

        assertEquals(0L, snapshot.liveMovingTimeMillis(nowElapsedRealtime = 400_000L))
    }

    @Test
    fun aPausedRideDoesNotTick() {
        val snapshot = recording(status = TrackingStatus.PAUSED)

        assertEquals(60_000L, snapshot.liveMovingTimeMillis(nowElapsedRealtime = 400_000L))
    }
}
