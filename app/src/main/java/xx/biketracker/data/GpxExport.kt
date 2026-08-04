package xx.biketracker.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.biketracker.haversineMeters
import xx.biketracker.isSegmentBoundary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Per-trip GPX 1.1 export for interop (Strava, Komoot, …). Files land in a `GPX-export` subfolder
 * of the shared export directory, kept apart from the whole-database backups. This is intentionally
 * not a restorable snapshot: GPX carries only the track, not the app's own figures.
 */

/** GPX subfolder under the shared export directory. */
private val GPX_DIR = "$EXPORT_DIR/GPX-export"

const val GPX_MIME = "application/gpx+xml"

/** UTC timestamps, as GPX requires (Z suffix). */
private fun isoUtc() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

/** ride-YYYYMMDD-HHMMSS.gpx from the ride's local start time. */
private fun gpxFileName(startTime: Long): String =
    "ride-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startTime))}.gpx"

/**
 * Absolute UTC millis to stamp on a point, monotonic so exported times never run backward and
 * confuse a GPX consumer. New rows carry [TrackPoint.elapsedMillis] (monotonic since ride start),
 * so their time is the ride start plus that offset — immune to a mid-ride wall-clock correction.
 * Legacy rows fall back to the recorded wall time; a zero (never reached the track) has no time.
 */
private fun gpxPointTimeMillis(trip: Trip, point: TrackPoint): Long? {
    val elapsed = point.elapsedMillis
    return when {
        elapsed != null -> trip.startTime + elapsed
        point.time > 0 -> point.time
        else -> null
    }
}

/**
 * Build the GPX document for [trip] from its [points]. Coordinates use 7 decimals (~1 cm),
 * altitude one. A recording gap (pause or GPS outage) starts a new `<trkseg>`, so an importing
 * app never draws a straight line across a stop. Point times are monotonic; see [gpxPointTimeMillis].
 *
 * A point that is nowhere is left out entirely rather than exported as `NaN`: the file has to stay
 * readable by every consumer, this app's own importer included, whatever a restored database holds.
 */
fun buildGpx(trip: Trip, points: List<TrackPoint>): String {
    val usable = points.filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
    val iso = isoUtc()
    val sb = StringBuilder(64 + usable.size * 80)
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"BikeTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("  <metadata><time>").append(iso.format(Date(trip.startTime))).append("</time></metadata>\n")
    sb.append("  <trk>\n    <name>").append(gpxEscape(trip.displayName())).append("</name>\n")
    trip.note?.takeIf { it.isNotBlank() }?.let {
        sb.append("    <desc>").append(gpxEscape(it)).append("</desc>\n")
    }

    var open = false
    for (i in usable.indices) {
        val p = usable[i]
        val startSeg = i == 0 || isSegmentBoundary(
            usable[i - 1].time, p.time, p.segmentStart, p.elapsedMillis != null,
        ) { haversineMeters(usable[i - 1].lat, usable[i - 1].lon, p.lat, p.lon) }
        if (startSeg) {
            if (open) sb.append("    </trkseg>\n")
            sb.append("    <trkseg>\n")
            open = true
        }
        sb.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
        p.altitudeMeters?.takeIf { it.isFinite() }
            ?.let { sb.append("<ele>").append(oneDecimal(it)).append("</ele>") }
        gpxPointTimeMillis(trip, p)?.let { sb.append("<time>").append(iso.format(Date(it))).append("</time>") }
        sb.append("</trkpt>\n")
    }
    if (open) sb.append("    </trkseg>\n")
    sb.append("  </trk>\n</gpx>\n")
    return sb.toString()
}

private fun coord(value: Double) = String.format(Locale.US, "%.7f", value)
private fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)

/**
 * A ride's own words made legal in an XML 1.0 document: markup characters escaped, and every code
 * point the standard forbids dropped. A name or note is whatever was pasted into the edit dialog,
 * and a control character or a lone half of a surrogate pair in it would leave the exported file
 * not well-formed — unreadable by any consumer, this app's own importer included. Tabs and line
 * breaks are legal and kept; a character outside the basic plane is kept whole.
 */
private fun gpxEscape(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
            ch.isHighSurrogate() -> {
                val low = text.getOrNull(i + 1)
                if (low != null && low.isLowSurrogate()) {
                    sb.append(ch).append(low)
                    i++
                }
            }
            ch.isLowSurrogate() -> Unit // a trailing half with nothing in front of it
            ch < ' ' || ch == '\uFFFE' || ch == '\uFFFF' -> Unit // C0 controls and non-characters
            else -> sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

/**
 * Write [trip]'s GPX into the shared `GPX-export` folder and return the content [Uri] of the file,
 * suitable for an ACTION_SEND share. Must run off the main thread.
 */
suspend fun exportRideGpx(context: Context, trip: Trip, points: List<TrackPoint>): Uri =
    withContext(Dispatchers.IO) {
        val bytes = buildGpx(trip, points).toByteArray(Charsets.UTF_8)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, gpxFileName(trip.startTime))
            put(MediaStore.MediaColumns.MIME_TYPE, GPX_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, GPX_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var target: Uri? = null
        try {
            target = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("Cannot create GPX file")
            resolver.openOutputStream(target)?.use { it.write(bytes) } ?: error("Cannot open output stream")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(target, published, null, null) == 1) { "Cannot publish GPX file" }
            target
        } catch (failure: Throwable) {
            target?.let { resolver.delete(it, null, null) }
            throw failure
        }
    }
