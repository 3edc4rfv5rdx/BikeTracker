package xx.biketracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point
import xx.biketracker.GPS_INTERVAL_MS
import xx.biketracker.GeoPoint
import xx.biketracker.ROUTE_SIMPLIFY_CHUNK
import xx.biketracker.ROUTE_SIMPLIFY_TOLERANCE_M
import xx.biketracker.smoothRoute
import xx.biketracker.splitRouteSegments
import kotlin.math.sin

/**
 * The drawn track, derived a fix at a time instead of from scratch. The property that matters is
 * that nothing depends on how the ride arrived: a track fed fix by fix must be drawn exactly as
 * the same track handed over in one piece, or the map would show a live ride differently from the
 * stored one it becomes.
 */
class TrackGeometryTest {

    /** A wandering track: straight enough to be simplified, crooked enough to keep geometry. */
    private fun track(count: Int, first: Int = 0, segmentStartAt: Int = -1) =
        (first until first + count).map { i ->
            GeoPoint(
                lat = 50.0 + sin(i / 7.0) * 1e-4,
                lon = 30.0 + i * 1e-4,
                timeMillis = 1_000L + i * GPS_INTERVAL_MS,
                speedMps = 5f,
                segmentStart = i == segmentStartAt,
                elapsedMillis = i * GPS_INTERVAL_MS,
            )
        }

    private fun coords(lines: List<List<Point>>) =
        lines.map { line -> line.map { it.longitude() to it.latitude() } }

    private fun grownFixByFix(route: List<GeoPoint>): List<List<Point>> {
        val cache = SmoothedTrack()
        var line = emptyList<List<Point>>()
        for (n in 1..route.size) line = cache.update(route.subList(0, n))
        return line
    }

    /** Furthest any of [positions] falls from the drawn [line], on a local planar projection. */
    private fun maxDeviationMeters(line: List<Point>, positions: List<GeoPoint>): Double {
        val metersPerDegree = 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(positions.first().lat))
        fun x(lon: Double) = lon * cosLat * metersPerDegree
        fun y(lat: Double) = lat * metersPerDegree
        return positions.maxOf { position ->
            val px = x(position.lon)
            val py = y(position.lat)
            line.zipWithNext().minOf { (a, b) ->
                val ax = x(a.longitude())
                val ay = y(a.latitude())
                val dx = x(b.longitude()) - ax
                val dy = y(b.latitude()) - ay
                val lengthSq = dx * dx + dy * dy
                val t = if (lengthSq == 0.0) 0.0 else
                    (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
                kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            }
        }
    }

    @Test
    fun aShortTrackIsDrawnExactlyAsSmoothingItWholeWould() {
        // Below one chunk nothing is sealed, so this is the old whole-track path, unchanged.
        val route = track(50)
        val expected = splitRouteSegments(route).map { segment -> smoothRoute(segment).map { it.lon to it.lat } }

        assertEquals(expected, coords(SmoothedTrack().update(route)))
    }

    @Test
    fun aTrackGrownFixByFixIsDrawnLikeOneHandedOverWhole() {
        // Long enough to seal two chunks and leave a third open.
        val route = track(ROUTE_SIMPLIFY_CHUNK * 2 + 120)

        assertEquals(coords(SmoothedTrack().update(route)), coords(grownFixByFix(route)))
    }

    @Test
    fun chunkSeamsLeaveNoRepeatedPoint() {
        val line = SmoothedTrack().update(track(ROUTE_SIMPLIFY_CHUNK * 2 + 120)).single()

        assertTrue(line.size > 2)
        assertTrue(line.zipWithNext().none { (a, b) -> a.longitude() == b.longitude() && a.latitude() == b.latitude() })
    }

    @Test
    fun simplifyingInChunksStaysWithinToleranceOfTheTrack() {
        // The point of simplifying in chunks is that it changes nothing the rider can see: the
        // drawn line must hold the same tolerance a whole-track pass holds, and still start and
        // end where the ride did.
        val route = track(ROUTE_SIMPLIFY_CHUNK * 2 + 120)
        val chunked = SmoothedTrack().update(route).single()

        assertTrue(chunked.size < route.size) // it does simplify
        assertTrue(maxDeviationMeters(chunked, smoothRoute(route)) <= ROUTE_SIMPLIFY_TOLERANCE_M)
        assertEquals(route.first().lon, chunked.first().longitude(), 1e-12)
        assertEquals(route.last().lon, chunked.last().longitude(), 1e-12)
    }

    @Test
    fun aRecordingBreakDrawsASecondLine() {
        val route = track(30) + track(30, first = 100, segmentStartAt = 100)
        val lines = SmoothedTrack().update(route)

        assertEquals(2, lines.size)
        assertEquals(coords(lines), coords(grownFixByFix(route)))
    }

    @Test
    fun aSegmentOfOnePointCannotFormALine() {
        // The caller drops these; the live one is still visible as the puck.
        val route = track(30) + track(1, first = 100, segmentStartAt = 100)
        val lines = SmoothedTrack().update(route)

        assertEquals(2, lines.size)
        assertEquals(1, lines.last().size)
    }

    @Test
    fun anotherTrackStartsTheDrawingOver() {
        val cache = SmoothedTrack()
        cache.update(track(40))
        val other = track(60, first = 5_000)

        assertEquals(coords(SmoothedTrack().update(other)), coords(cache.update(other)))
    }

    @Test
    fun aTrackThatShrinksStartsTheDrawingOver() {
        // Nothing in the app rewinds a route, but a cache that trusted one would draw a ride that
        // never happened rather than fail loudly.
        val route = track(40)
        val cache = SmoothedTrack()
        cache.update(route)

        assertEquals(coords(SmoothedTrack().update(route.take(10))), coords(cache.update(route.take(10))))
    }
}
