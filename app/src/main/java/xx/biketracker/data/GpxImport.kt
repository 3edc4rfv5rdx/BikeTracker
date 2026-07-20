package xx.biketracker.data

import org.w3c.dom.Element
import org.xml.sax.InputSource
import xx.biketracker.GeoPoint
import xx.biketracker.haversineMeters
import java.io.StringReader
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only GPX import for the "view a track on the map" feature — the reverse of [buildGpx], but
 * it never touches the database. Only geometry is recovered: each `<trkseg>` becomes a recording
 * segment (its first point flagged [GeoPoint.segmentStart]) so the map and chart split at gaps, and
 * per-point speed is derived from the timestamps so the speed chart has something to plot.
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
    return ParsedGpx(name = firstElementText(doc, "name"), route = withDerivedSpeed(route))
}

/** GPX carries no speed, so derive each point's from its distance and time since the previous one
 *  in the same segment; a segment start or a missing timestamp leaves it at 0. */
private fun withDerivedSpeed(route: List<GeoPoint>): List<GeoPoint> = route.mapIndexed { i, p ->
    if (i == 0 || p.segmentStart) return@mapIndexed p
    val prev = route[i - 1]
    val dtSeconds = (p.timeMillis - prev.timeMillis) / 1000.0
    if (prev.timeMillis <= 0 || p.timeMillis <= 0 || dtSeconds <= 0.0) return@mapIndexed p
    val speed = (haversineMeters(prev.lat, prev.lon, p.lat, p.lon) / dtSeconds).toFloat()
    p.copy(speedMps = speed.coerceAtLeast(0f))
}

private fun parseIsoMillis(text: String): Long? =
    runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()

private fun childText(element: Element, tag: String): String? {
    val nodes = element.getElementsByTagName(tag)
    return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
}

private fun firstElementText(doc: org.w3c.dom.Document, tag: String): String? {
    val nodes = doc.getElementsByTagName(tag)
    return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
}
