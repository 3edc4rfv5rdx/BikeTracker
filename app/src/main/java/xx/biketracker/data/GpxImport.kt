package xx.biketracker.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import xx.biketracker.GeoPoint
import xx.biketracker.MAX_GPX_FILE_BYTES
import xx.biketracker.MAX_IMPORTED_POINTS
import xx.biketracker.MAX_PLAUSIBLE_SPEED_MPS
import xx.biketracker.haversineMeters
import java.io.InputStream
import java.io.StringReader
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory

/**
 * Read-only GPX import for the "view a track on the map" feature — the reverse of [buildGpx], but
 * it never touches the database. Only geometry is recovered: each `<trkseg>` becomes a recording
 * segment (its first point flagged [GeoPoint.segmentStart]) so the map and chart split where the
 * file says the recording stopped, while the per-point speed and elapsed offset the chart needs are
 * derived from the timestamps ([withDerivedMetadata]).
 *
 * [truncated] says the file held more points than [MAX_IMPORTED_POINTS] and only its start is here.
 */
class ParsedGpx(val name: String?, val route: List<GeoPoint>, val truncated: Boolean = false)

/** What came of trying to import a document the user picked. */
sealed interface GpxImportOutcome {
    class Imported(val track: ParsedGpx) : GpxImportOutcome

    /** Bigger than any GPX track is, so it was never read: the pick was a mistake, not a track. */
    data object TooLarge : GpxImportOutcome

    /** Unreadable, not XML, not GPX, or GPX carrying no points at all. */
    data object Failed : GpxImportOutcome
}

/** Whether a document of [sizeBytes] is worth opening; a provider that will not say its size
 *  (null) is trusted, since the reading below is streamed and bounded by its own point cap. */
fun isImportableGpxSize(sizeBytes: Long?): Boolean =
    sizeBytes == null || sizeBytes <= MAX_GPX_FILE_BYTES

/** Import the document at [uri] for viewing. Blocking: call it off the main thread. */
fun importGpx(resolver: ContentResolver, uri: Uri): GpxImportOutcome {
    if (!isImportableGpxSize(documentSize(resolver, uri))) return GpxImportOutcome.TooLarge
    val track = try {
        resolver.openInputStream(uri)?.use { parseGpx(it) }
    } catch (_: Exception) {
        // Deliberately not runCatching: an OutOfMemoryError is not a failed import to report and
        // carry on from, and swallowing one would leave the process in a state it may not survive.
        null
    }
    return if (track == null) GpxImportOutcome.Failed else GpxImportOutcome.Imported(track)
}

/** The document's size as its provider reports it, or null when it reports none. */
private fun documentSize(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
    }
} catch (_: Exception) {
    null
}

/**
 * Parse [input] into a track, or null when it is not GPX we can read or carries no points.
 *
 * Read as a stream, an element at a time: a picked file may be anything on the device, and holding
 * a whole document — as text and then again as a tree — is memory the app has no reason to spend
 * and no way to bound. Only the route survives the pass, and never more than [maxPoints] of it.
 *
 * The parser is namespace-aware, so the GPX elements are matched on their local name whatever
 * prefix a file gives them (`<gpx:trkpt>` is as valid as `<trkpt>`), while an element of some other
 * namespace that merely shares a local name — an extension's own `<name>`, say — is not mistaken
 * for one of GPX's own. A file that declares no namespace at all is read as GPX too, since that is
 * what many exporters write.
 */
fun parseGpx(input: InputStream, maxPoints: Int = MAX_IMPORTED_POINTS): ParsedGpx? {
    val handler = GpxHandler(maxPoints)
    try {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE: the file is user-supplied and never needs a DTD. The handler
            // resolves every entity to nothing, in case a parser refuses one of these features.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newSAXParser().parse(input, handler)
    } catch (_: PointLimitReached) {
        // The start of the track is worth showing; the caller says so on screen.
    } catch (_: Exception) {
        return null
    }
    if (handler.route.isEmpty()) return null
    return ParsedGpx(
        name = handler.trackName ?: handler.metadataName ?: handler.rootName,
        route = withDerivedMetadata(handler.route),
        truncated = handler.truncated,
    )
}

/** Thrown to stop the parse once [MAX_IMPORTED_POINTS] have been read; not a failure. */
private class PointLimitReached : SAXException()

/** The namespaces a GPX element may be in. The empty one is a file that declares none. */
private val GPX_NAMESPACES = setOf(
    "",
    "http://www.topografix.com/GPX/1/1",
    "http://www.topografix.com/GPX/1/0",
)

/** Marks an element belonging to some other namespace, so nothing inside it is read as GPX. */
private const val FOREIGN = "?foreign" // "?" cannot start an XML name, so no element collides

/** Which element's text is being collected, and where it belongs when the element ends. */
private enum class Capture { NONE, TRACK_NAME, METADATA_NAME, ROOT_NAME, POINT_TIME }

/**
 * Builds the route as the document streams past. GPX nests shallowly and rigidly, so each element
 * is placed by its depth and the elements open above it — `<name>` means three different things
 * depending on where it sits, and only the ones directly under `<trk>`, `<metadata>` and `<gpx>`
 * name anything (an `<author><name>` would otherwise label a ride with whoever exported it).
 */
private class GpxHandler(private val maxPoints: Int) : DefaultHandler() {
    val route = ArrayList<GeoPoint>()
    var truncated = false
        private set
    var trackName: String? = null
        private set
    var metadataName: String? = null
        private set
    var rootName: String? = null
        private set

    /** Local names of the elements open right now, the root first. */
    private val open = ArrayList<String>()
    private var isGpx = false
    private var tracks = 0
    private var inTrack = false
    private var inSegment = false
    private var inMetadata = false
    private var segmentStart = false

    private var capture = Capture.NONE
    private val text = StringBuilder()

    /** True between a `<trkpt>` with usable coordinates and its end tag. */
    private var pointOpen = false
    private var pointLat = 0.0
    private var pointLon = 0.0
    private var pointTimeMillis = 0L

    /** Nothing outside the document is ever fetched, whatever it declares. */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        InputSource(StringReader(""))

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = if (uri.orEmpty() in GPX_NAMESPACES) localName.orEmpty() else FOREIGN
        // An element inside one whose text was being collected ends that collection: the text of
        // a name is its own, not its children's.
        capture = Capture.NONE
        open += name
        when (open.size) {
            1 -> isGpx = name == "gpx"
            2 -> if (isGpx) when (name) {
                "trk" -> { tracks++; inTrack = true }
                "metadata" -> inMetadata = true
                "name" -> capture = Capture.ROOT_NAME // GPX 1.0 puts the file's name here
            }
            3 -> when {
                // Only the first <trk> names the track, which is the one whose name is shown.
                inTrack && name == "name" && tracks == 1 -> capture = Capture.TRACK_NAME
                inTrack && name == "trkseg" -> { inSegment = true; segmentStart = true }
                inMetadata && name == "name" -> capture = Capture.METADATA_NAME
            }
            4 -> if (inSegment && name == "trkpt") startPoint(attributes)
            5 -> if (pointOpen && name == "time") capture = Capture.POINT_TIME
        }
        if (capture != Capture.NONE) text.setLength(0)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (capture != Capture.NONE) text.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (capture) {
            Capture.TRACK_NAME -> trackName = trackName ?: captured()
            Capture.METADATA_NAME -> metadataName = metadataName ?: captured()
            Capture.ROOT_NAME -> rootName = rootName ?: captured()
            Capture.POINT_TIME -> pointTimeMillis = captured()?.let(::parseIsoMillis) ?: 0L
            Capture.NONE -> {}
        }
        capture = Capture.NONE
        val name = open.removeAt(open.lastIndex)
        when {
            open.size == 1 && name == "trk" -> inTrack = false
            open.size == 1 && name == "metadata" -> inMetadata = false
            open.size == 2 && name == "trkseg" -> inSegment = false
            open.size == 3 && name == "trkpt" && pointOpen -> endPoint()
        }
    }

    private fun startPoint(attributes: Attributes) {
        val lat = attribute(attributes, "lat")?.toDoubleOrNull() ?: return
        val lon = attribute(attributes, "lon")?.toDoubleOrNull() ?: return
        pointLat = lat
        pointLon = lon
        pointTimeMillis = 0L
        pointOpen = true
    }

    private fun endPoint() {
        pointOpen = false
        // Judged before adding, so a file that ends exactly on the cap is whole rather than
        // reported as shortened.
        if (route.size == maxPoints) {
            truncated = true
            throw PointLimitReached()
        }
        route += GeoPoint(
            lat = pointLat,
            lon = pointLon,
            timeMillis = pointTimeMillis,
            segmentStart = segmentStart,
        )
        segmentStart = false
    }

    /** An unprefixed attribute carries no namespace; the qualified name is the fallback for a
     *  parser that reports it that way. */
    private fun attribute(attributes: Attributes, name: String): String? =
        attributes.getValue("", name) ?: attributes.getValue(name)

    private fun captured(): String? = text.toString().trim().takeIf { it.isNotEmpty() }
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
