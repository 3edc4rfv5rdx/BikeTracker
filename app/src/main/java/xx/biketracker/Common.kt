package xx.biketracker

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared constants and pure helpers used across the tracking service and the UI.
 * Everything numeric here is in SI units (meters, m/s, milliseconds); unit labels
 * (km, km/h) live in strings.xml and are appended by the caller, so this file has
 * no Android/resource dependency and stays trivially reusable.
 */

// --- Units ---
const val MPS_TO_KMH = 3.6
const val METERS_PER_KM = 1000.0

// --- GPS request cadence ---
const val GPS_INTERVAL_MS = 1500L
const val GPS_MIN_INTERVAL_MS = 1000L

// --- Point filtering ---
/** Drop fixes whose reported horizontal accuracy is worse than this (meters). */
const val ACCURACY_THRESHOLD_M = 25f
/** Drop a segment implying a speed above this (m/s ≈ 108 km/h) — almost surely a GPS jump. */
const val MAX_PLAUSIBLE_SPEED_MPS = 30.0

// --- Auto-pause / auto-resume (defaults; overridable in Settings later) ---
/** Below this speed (m/s ≈ 2 km/h) the ride is a candidate for auto-pause. */
const val AUTO_PAUSE_SPEED_MPS = 2.0 / MPS_TO_KMH
/** Speed must stay low this long before auto-pause actually triggers. */
const val AUTO_PAUSE_DEBOUNCE_MS = 5_000L
/** Above this speed (m/s ≈ 4 km/h) an auto-paused ride resumes; hysteresis avoids flapping. */
const val AUTO_RESUME_SPEED_MPS = 4.0 / MPS_TO_KMH

// --- Auto-save ---
/** A ride paused longer than this is finished and saved automatically (default 15 min). */
const val DEFAULT_AUTO_SAVE_MS = 15L * 60L * 1000L

// --- Time windows ---
const val DAY_MS = 24L * 60L * 60L * 1000L

// --- Preferences ---
/** Single SharedPreferences file for all app settings. */
const val PREFS_NAME = "biketracker_prefs"

/** A latitude/longitude pair; the live route and stored track are ordered lists of these. */
data class GeoPoint(val lat: Double, val lon: Double)

fun mpsToKmh(mps: Double): Double = mps * MPS_TO_KMH

fun metersToKm(meters: Double): Double = meters / METERS_PER_KM

/** Great-circle distance between two coordinates in meters (haversine). */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Average speed in m/s, derived from distance and moving time (0 if no time elapsed). */
fun avgSpeedMps(distanceMeters: Double, movingTimeMillis: Long): Double =
    if (movingTimeMillis > 0) distanceMeters / (movingTimeMillis / 1000.0) else 0.0

// --- Display formatting (numbers only; caller appends the localized unit label) ---

fun formatKm(meters: Double, decimals: Int = 1): String =
    String.format(Locale.getDefault(), "%.${decimals}f", metersToKm(meters))

fun formatSpeedKmh(mps: Double): String =
    String.format(Locale.getDefault(), "%.1f", mpsToKmh(mps))

/**
 * Total ascent from a sequence of GPS altitudes (meters), nulls skipped. GPS vertical noise is
 * large, so climbs are only counted once the rise past the last reference exceeds
 * [thresholdMeters]; descents lower the reference so the next climb is measured from the low point.
 */
fun elevationGainMeters(altitudes: List<Double?>, thresholdMeters: Double = 3.0): Double {
    var gain = 0.0
    var reference: Double? = null
    for (a in altitudes) {
        if (a == null) continue
        val ref = reference
        if (ref == null) {
            reference = a
        } else if (a - ref >= thresholdMeters) {
            gain += a - ref
            reference = a
        } else if (a < ref) {
            reference = a
        }
    }
    return gain
}

/** Riding pace as "M:SS" minutes per kilometer, or "—" when there is no distance. */
fun formatPace(distanceMeters: Double, movingTimeMillis: Long): String {
    if (distanceMeters <= 0) return "—"
    val secondsPerKm = (movingTimeMillis / 1000.0) / (distanceMeters / METERS_PER_KM)
    val total = secondsPerKm.roundToInt()
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

/** Localized calendar date, e.g. "9 Jul 2026" — medium style, respects device locale. */
fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(epochMillis))

/** Standalone month name for the date browser, e.g. "July" / "Липень" / "Июль". */
fun formatMonthName(epochMillis: Long): String =
    SimpleDateFormat("LLLL", Locale.getDefault()).format(Date(epochMillis))
        .replaceFirstChar { it.uppercase() }

/** Day-of-month then weekday for the date browser, e.g. "22 Saturday" / "22 суббота". */
fun formatDayLabel(epochMillis: Long): String =
    SimpleDateFormat("d EEEE", Locale.getDefault()).format(Date(epochMillis))

/** Wall-clock time of day, "HH:mm" or "HH:mm:ss". */
fun formatClock(epochMillis: Long, withSeconds: Boolean = false): String =
    SimpleDateFormat(if (withSeconds) "HH:mm:ss" else "HH:mm", Locale.getDefault())
        .format(Date(epochMillis))

/** "H:MM:SS" when there are hours, otherwise "MM:SS". */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
