package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSegmentsTest {

    private fun point(index: Int, timeMillis: Long, segmentStart: Boolean = false) =
        GeoPoint(50.0 + index * 1e-4, 30.0 + index * 1e-4, timeMillis, segmentStart = segmentStart)

    /** Points at the normal GPS cadence, then a gap, then more points. */
    private fun rideWithGap(gapMillis: Long): List<GeoPoint> {
        val before = (0..3).map { point(it, it * GPS_INTERVAL_MS) }
        val resumeAt = 3 * GPS_INTERVAL_MS + gapMillis
        val after = (4..7).map { point(it, resumeAt + (it - 4) * GPS_INTERVAL_MS) }
        return before + after
    }

    @Test
    fun continuousRideStaysOneSegment() {
        val route = (0..9).map { point(it, it * GPS_INTERVAL_MS) }
        assertEquals(listOf(route), splitRouteSegments(route))
    }

    @Test
    fun pauseWithMovementSplitsIntoTwoSegments() {
        val route = rideWithGap(gapMillis = 5 * 60_000L) // rider pauses and walks off for 5 min
        val segments = splitRouteSegments(route)
        assertEquals(2, segments.size)
        assertEquals(route.take(4), segments[0])
        assertEquals(route.drop(4), segments[1])
    }

    @Test
    fun gpsOutageJustAboveStaleThresholdSplits() {
        val segments = splitRouteSegments(rideWithGap(gapMillis = GPS_STALE_MS + 1))
        assertEquals(2, segments.size)
    }

    @Test
    fun shortDeliveryHiccupDoesNotSplit() {
        val segments = splitRouteSegments(rideWithGap(gapMillis = GPS_STALE_MS))
        assertEquals(1, segments.size)
    }

    @Test
    fun shortPauseWithFlagSplitsRegardlessOfGap() {
        // Rider pauses, rolls a few meters, resumes within 2 s: the wall gap is under the stale
        // threshold, so only the explicit boundary flag can split the segment.
        val before = (0..3).map { point(it, it * GPS_INTERVAL_MS) }
        val resumeAt = 3 * GPS_INTERVAL_MS + 2_000L
        val after = (4..7).map { point(it, resumeAt + (it - 4) * GPS_INTERVAL_MS, segmentStart = it == 4) }
        val segments = splitRouteSegments(before + after)
        assertEquals(2, segments.size)
        assertEquals(before, segments[0])
        assertEquals(after, segments[1])
    }

    @Test
    fun sameShortGapWithoutFlagStaysContinuous() {
        // Identical 2 s gap but no pause: a location-delivery hiccup must not split the ride.
        val before = (0..3).map { point(it, it * GPS_INTERVAL_MS) }
        val resumeAt = 3 * GPS_INTERVAL_MS + 2_000L
        val after = (4..7).map { point(it, resumeAt + (it - 4) * GPS_INTERVAL_MS) }
        assertEquals(1, splitRouteSegments(before + after).size)
    }

    @Test
    fun repeatedOutagesProduceOneSegmentEach() {
        val route = rideWithGap(gapMillis = 60_000L)
        val lastTime = route.last().timeMillis
        val third = (8..10).map { point(it, lastTime + 120_000L + (it - 8) * GPS_INTERVAL_MS) }
        val segments = splitRouteSegments(route + third)
        assertEquals(3, segments.size)
        assertEquals(third, segments[2])
    }

    @Test
    fun forwardClockJumpOnNewRideDoesNotSplit() {
        // New ride: points carry elapsed metadata and no boundary flag, so a forward wall-clock
        // jump larger than the stale threshold must not be misread as a pause.
        val route = listOf(
            GeoPoint(50.0, 30.0, timeMillis = 1_000L, elapsedMillis = 0L),
            GeoPoint(50.0001, 30.0001, timeMillis = 3_600_000L, elapsedMillis = 1_000L),
            GeoPoint(50.0002, 30.0002, timeMillis = 3_601_000L, elapsedMillis = 2_000L),
        )
        assertEquals(1, splitRouteSegments(route).size)
    }

    @Test
    fun flaggedBoundaryOnNewRideStillSplits() {
        val route = listOf(
            GeoPoint(50.0, 30.0, timeMillis = 1_000L, elapsedMillis = 0L),
            GeoPoint(50.0001, 30.0001, timeMillis = 2_000L, elapsedMillis = 1_000L, segmentStart = true),
        )
        assertEquals(2, splitRouteSegments(route).size)
    }

    @Test
    fun untimedOldRouteStaysOneSegment() {
        val route = (0..5).map { point(it, timeMillis = 0L) }
        assertEquals(listOf(route), splitRouteSegments(route))
    }

    @Test
    fun mixedUntimedPointsNeverSplit() {
        // A timestamped point next to an untimed one has no measurable gap.
        val route = listOf(point(0, 1_000L), point(1, 0L), point(2, 900_000L))
        assertEquals(1, splitRouteSegments(route).size)
    }

    @Test
    fun backwardClockJumpDoesNotSplit() {
        val route = listOf(point(0, 100_000L), point(1, 40_000L), point(2, 41_500L))
        assertEquals(1, splitRouteSegments(route).size)
    }

    @Test
    fun singlePointAndEmptyRoutes() {
        assertEquals(emptyList<List<GeoPoint>>(), splitRouteSegments(emptyList()))
        val single = listOf(point(0, 1_000L))
        assertEquals(listOf(single), splitRouteSegments(single))
    }

    @Test
    fun isolatedPointBetweenOutagesBecomesItsOwnSegment() {
        val route = listOf(point(0, 1_000L), point(1, 61_000L), point(2, 121_000L))
        assertEquals(3, splitRouteSegments(route).size)
    }
}
