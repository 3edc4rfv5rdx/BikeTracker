package xx.biketracker.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import xx.biketracker.GeoPoint
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.haversineMeters
import java.io.StringReader
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only GPX import for the "view a track on the map" feature — the reverse of [buildGpx], but
 * it never touches the database. Only geometry is recovered: each `<trkseg>` becomes a recording
 * segment (its first point flagged [GeoPoint.segmentStart]) so the map and chart split where the
 * file says the recording stopped, while the per-point speed and elapsed offset the chart needs are
 * derived from the timestamps ([withDerivedMetadata]).
 */
class ParsedGpx(val name: String?, val route: List<GeoPoint>)

/** Parse [xml] into a track, or null when it is not GPX we can read or carries no points. */
fun parseGpx(xml: String): ParsedGpx? {
    val doc = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false // GPX uses a default namespace; match tag names literally
            // Harden against XXE: the file is user-supplied and never needs a DTD.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (_: Exception) {
        return null
    }

    val route = ArrayList<GeoPoint>()
    val segments = doc.getElementsByTagName("trkseg")
    for (s in 0 until segments.length) {
        val seg = segments.item(s) as? Element ?: continue
        val points = seg.getElementsByTagName("trkpt")
        for (p in 0 until points.length) {
            val pt = points.item(p) as? Element ?: continue
            val lat = pt.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = pt.getAttribute("lon").toDoubleOrNull() ?: continue
            route += GeoPoint(
                lat = lat,
                lon = lon,
                timeMillis = childText(pt, "time")?.let(::parseIsoMillis) ?: 0L,
                segmentStart = p == 0, // first point of each recording segment
            )
        }
    }
    if (route.isEmpty()) return null
    return ParsedGpx(name = trackName(doc), route = withDerivedMetadata(route))
}

/**
 * The name to label the imported track with. `<trk><name>` is the track's own and the only one that
 * names what is actually drawn; the file-level name — `<metadata><name>` in GPX 1.1, a `<name>`
 * straight under `<gpx>` in 1.0 — stands in when the track has none.
 *
 * Only direct children count. `<author><name>` sits inside `<metadata>` right beside the file's own
 * name, so a document-wide search for the first `<name>` will label a ride with whoever exported it.
 */
private fun trackName(doc: Document): String? {
    val root = doc.documentElement ?: return null
    val track = doc.getElementsByTagName("trk").item(0) as? Element
    track?.let { directChildText(it, "name") }?.let { return it }
    directChild(root, "metadata")?.let { directChildText(it, "name") }?.let { return it }
    return directChildText(root, "name")
}

/**
 * Fill in what GPX itself does not carry: a monotonic offset since the track's start, and a
 * per-point speed derived from the distance and time since the previous point.
 *
 * The elapsed offset settles how the track is segmented as well. `<trkseg>` is the file's own
 * explicit statement of where its recording stopped, and [parseGpx] records it on each segment's
 * first point; a track carrying elapsed metadata is one [xx.biketracker.isSegmentBoundary] takes at
 * its word, so BikeTracker's recording heuristic — tuned to its own once-a-second cadence — never
 * second-guesses it. A file logged once every five minutes is then read as the coarse recording of
 * one ride that it is, rather than as a row of single-point pieces no line can be drawn through.
 *
 * The parser does still split where the file is not credible on its own terms: a step nobody could
 * have ridden ([MAX_PLAUSIBLE_SPEED_MPS]) is a jump in the data, and both drawing a line across it
 * and believing the speed it implies would be inventing a journey. Judging that here keeps every
 * decision about an imported file in the one place that has the file in front of it.
 */
private fun withDerivedMetadata(route: List<GeoPoint>): List<GeoPoint> {
    var elapsedMillis = 0L
    val derived = ArrayList<GeoPoint>(route.size)
    for (i in route.indices) {
        val point = route[i]
        val prev = route.getOrNull(i - 1)
        // Only a pair of real timestamps measures anything: a missing one — or a clock that ran
        // backward between them — advances the offset by nothing rather than inventing time.
        val stepMillis = if (prev != null && prev.timeMillis > 0L && point.timeMillis > 0L) {
            (point.timeMillis - prev.timeMillis).coerceAtLeast(0L)
        } else {
            0L
        }
        elapsedMillis += stepMillis
        // A speed can only be read off a measurable step within one segment.
        var stepSpeedMps = 0.0
        if (prev != null && !point.segmentStart && stepMillis > 0L) {
            stepSpeedMps = haversineMeters(prev.lat, prev.lon, point.lat, point.lon) / (stepMillis / 1000.0)
        }
        val jumped = stepSpeedMps > MAX_PLAUSIBLE_SPEED_MPS
        derived += point.copy(
            speedMps = if (jumped) 0f else stepSpeedMps.toFloat(),
            segmentStart = point.segmentStart || jumped,
            elapsedMillis = elapsedMillis,
        )
    }
    return derived
}

private fun parseIsoMillis(text: String): Long? =
    runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()

private fun childText(element: Element, tag: String): String? {
    val nodes = element.getElementsByTagName(tag)
    return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
}

/** First direct child element of [element] with this tag; descendants are deliberately not searched. */
private fun directChild(element: Element, tag: String): Element? {
    val children = element.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element && child.tagName == tag) return child
    }
    return null
}

/** Trimmed text of [directChild], or null when it is absent or blank. */
private fun directChildText(element: Element, tag: String): String? =
    directChild(element, tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
