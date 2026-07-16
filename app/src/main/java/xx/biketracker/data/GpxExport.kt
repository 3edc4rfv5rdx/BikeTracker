package xx.biketracker.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.biketracker.isRecordingGap
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
 * Build the GPX document for [trip] from its [points]. Coordinates use 7 decimals (~1 cm),
 * altitude one. A recording gap (pause or GPS outage) starts a new `<trkseg>`, so an importing
 * app never draws a straight line across a stop.
 */
fun buildGpx(trip: Trip, points: List<TrackPoint>): String {
    val iso = isoUtc()
    val sb = StringBuilder(64 + points.size * 80)
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"BikeTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("  <metadata><time>").append(iso.format(Date(trip.startTime))).append("</time></metadata>\n")
    sb.append("  <trk>\n    <name>").append(gpxEscape(gpxTrackName(trip))).append("</name>\n")
    trip.note?.takeIf { it.isNotBlank() }?.let {
        sb.append("    <desc>").append(gpxEscape(it)).append("</desc>\n")
    }

    var open = false
    for (i in points.indices) {
        val p = points[i]
        val startSeg = i == 0 || isRecordingGap(points[i - 1].time, p.time)
        if (startSeg) {
            if (open) sb.append("    </trkseg>\n")
            sb.append("    <trkseg>\n")
            open = true
        }
        sb.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
        p.altitudeMeters?.let { sb.append("<ele>").append(oneDecimal(it)).append("</ele>") }
        if (p.time > 0) sb.append("<time>").append(iso.format(Date(p.time))).append("</time>")
        sb.append("</trkpt>\n")
    }
    if (open) sb.append("    </trkseg>\n")
    sb.append("  </trk>\n</gpx>\n")
    return sb.toString()
}

/** Human-readable track name: the rider's own ride name when set, else its start date and time. */
private fun gpxTrackName(trip: Trip): String =
    trip.title?.takeIf { it.isNotBlank() }
        ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(trip.startTime))

private fun coord(value: Double) = String.format(Locale.US, "%.7f", value)
private fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)

private fun gpxEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            target
        } catch (failure: Throwable) {
            target?.let { resolver.delete(it, null, null) }
            throw failure
        }
    }
