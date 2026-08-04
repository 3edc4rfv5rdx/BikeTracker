package xx.biketracker.map

import org.maplibre.geojson.Point
import xx.biketracker.GeoPoint
import xx.biketracker.ROUTE_SIMPLIFY_CHUNK
import xx.biketracker.ROUTE_SIMPLIFY_TOLERANCE_M
import xx.biketracker.ROUTE_SMOOTH_WINDOW
import xx.biketracker.isSegmentBoundary
import xx.biketracker.simplifyRoute
import xx.biketracker.smoothedPointAt

/**
 * The lines the map draws for a track, kept up to date as the track grows.
 *
 * A live ride hands the whole route to the map on every fix, and deriving the drawn shape from
 * scratch each time — splitting the recording segments, averaging out the GPS scatter, dropping
 * the points that add no geometry, allocating a `Point` for every survivor — is several megabytes
 * of work per fix by the end of a long ride, on the one screen a rider watches while cycling.
 *
 * A ride only ever grows at its end, so almost none of that work needs redoing. This keeps what a
 * new fix cannot change: recording segments before the current one are final, the moving average
 * of a point is final once [ROUTE_SMOOTH_WINDOW] / 2 points follow it, and the simplified geometry
 * is final for every whole chunk of [ROUTE_SIMPLIFY_CHUNK] settled points. Only the open chunk of
 * the open segment is re-derived, so the cost of a fix stops growing with the ride.
 *
 * Simplifying in chunks is the one deliberate difference from doing it in one pass over the track.
 * Which points survive differs a little near a chunk's ends, but what simplifying promises does
 * not: every recorded position stays within [ROUTE_SIMPLIFY_TOLERANCE_M] of the drawn line, since
 * that is what simplifying each chunk guarantees for the points inside it. A track shorter than a
 * chunk is simplified exactly as it was before.
 *
 * The result depends only on the points fed in, never on how they were fed: updating fix by fix
 * gives the same lines as one update with the finished ride.
 *
 * Not thread-safe: it is a cache, and callers must not run two updates at once.
 */
internal class SmoothedTrack {

    private val segments = mutableListOf<Segment>()

    /** Points already folded into [segments], and the last of them — a route that does not
     *  continue where the previous one ended is a different track and starts the cache over. */
    private var consumed = 0
    private var lastConsumed: GeoPoint? = null

    /** The drawn line of each recording segment, in order. Segments of a single point yield a
     *  single-point list: they cannot form a line, and the caller drops them. */
    fun update(route: List<GeoPoint>): List<List<Point>> {
        if (route.size < consumed || (consumed > 0 && route.getOrNull(consumed - 1) != lastConsumed)) {
            segments.clear()
            consumed = 0
        }
        for (i in consumed until route.size) {
            val boundary = i > 0 && isSegmentBoundary(route[i - 1], route[i])
            if (i == 0 || boundary) segments += Segment(firstIndex = i)
            segments.last().extendTo(i + 1)
        }
        consumed = route.size
        lastConsumed = route.lastOrNull()
        return segments.map { it.polyline(route) }
    }
}

/** One recording segment's share of the track, and the drawn line derived from it. */
private class Segment(private val firstIndex: Int) {
    private var endIndex = firstIndex // exclusive

    /** Moving-average positions that no further fix can change, from [firstIndex] on. */
    private val smoothed = mutableListOf<GeoPoint>()

    /** Simplified geometry of each whole chunk of [smoothed], each starting on the point the
     *  previous one ended on. */
    private val chunks = mutableListOf<List<Point>>()

    private var line: List<Point> = emptyList()
    private var lineLength = -1

    fun extendTo(endIndexExclusive: Int) {
        endIndex = endIndexExclusive
    }

    fun polyline(route: List<GeoPoint>): List<Point> {
        val length = endIndex - firstIndex
        if (lineLength == length) return line
        val points = route.subList(firstIndex, endIndex)
        line = if (length < 3) {
            // Too short to average or simplify — the same as an untouched track (see smoothRoute).
            toPoints(points)
        } else {
            extendSmoothed(points)
            sealWholeChunks()
            joinedWith(openTail(points))
        }
        lineLength = length
        return line
    }

    /** Average in the points whose window has closed since the last update. */
    private fun extendSmoothed(points: List<GeoPoint>) {
        val settled = points.size - ROUTE_SMOOTH_WINDOW / 2
        while (smoothed.size < settled) {
            smoothed += smoothedPointAt(points, smoothed.size, ROUTE_SMOOTH_WINDOW)
        }
    }

    /** Simplify every chunk that now lies wholly inside the settled positions. Consecutive chunks
     *  share an endpoint, so the drawn line stays connected across them. */
    private fun sealWholeChunks() {
        while ((chunks.size + 1) * ROUTE_SIMPLIFY_CHUNK <= smoothed.size - 1) {
            val from = chunks.size * ROUTE_SIMPLIFY_CHUNK
            chunks += toPoints(
                simplifyRoute(
                    smoothed.subList(from, from + ROUTE_SIMPLIFY_CHUNK + 1),
                    ROUTE_SIMPLIFY_TOLERANCE_M,
                )
            )
        }
    }

    /** The still-open end of the segment: settled positions no chunk has claimed yet, plus the
     *  last few whose average is still moving, simplified afresh on every fix. */
    private fun openTail(points: List<GeoPoint>): List<Point> {
        val from = chunks.size * ROUTE_SIMPLIFY_CHUNK
        val tail = ArrayList<GeoPoint>(points.size - from)
        for (i in from until points.size) {
            tail += if (i < smoothed.size) smoothed[i] else smoothedPointAt(points, i, ROUTE_SMOOTH_WINDOW)
        }
        return toPoints(simplifyRoute(tail, ROUTE_SIMPLIFY_TOLERANCE_M))
    }

    private fun toPoints(positions: List<GeoPoint>): List<Point> =
        positions.map { Point.fromLngLat(it.lon, it.lat) }

    /** Concatenate the sealed chunks and the open tail, each contributing everything but the
     *  point it shares with the piece before it. */
    private fun joinedWith(tail: List<Point>): List<Point> {
        val joined = ArrayList<Point>(chunks.sumOf { it.size } + tail.size)
        for (chunk in chunks) joined += if (joined.isEmpty()) chunk else chunk.subList(1, chunk.size)
        joined += if (joined.isEmpty()) tail else tail.subList(1, tail.size)
        return joined
    }
}
