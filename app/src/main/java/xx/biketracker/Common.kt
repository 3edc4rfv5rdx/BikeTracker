package xx.biketracker

import xx.biketracker.data.TrackPoint
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
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

// --- Standby after a long-pause auto-save ---
// The ride is saved but the service stays alive, listening for the rider to set off again.
/**
 * How long the elapsed auto-save waits for the GPS to come back before closing the ride anyway.
 * Closing it is only defensible while the tracker can see that the rider really is standing still,
 * so a pause that began under a jammed signal waits for a fix to settle the question — but not
 * forever: an unbounded wait leaves the service and its location request running for as long as the
 * phone sits somewhere without a signal, which is the one place the wait can never end on its own.
 *
 * Giving up costs little. A fix stream this dead recorded nothing either, so the ride being closed
 * is already complete up to its last fix; the worst case is a trip stored as two, with standby
 * picking the second half up as soon as the signal returns.
 */
const val AUTO_SAVE_GPS_WAIT_MS = 15L * 60L * 1000L
/** How long standby waits for movement before shutting the service down for good. */
const val STANDBY_TIMEOUT_MS = 30L * 60L * 1000L
/** Movement must hold above the resume threshold this long to auto-start a ride from standby. */
const val STANDBY_RESUME_HOLD_MS = 3_000L
/**
 * Having left the standby anchor must hold this long — across fixes, not on the strength of one —
 * before it auto-starts a ride. A single fix far from the anchor is what a spoofed jump looks
 * like, and wherever the signal is jammed those arrive on a bike that never moved.
 */
const val STANDBY_DEPARTURE_HOLD_MS = 3_000L
/**
 * A ride the tracker closes on its own (the long-pause auto-save) is only stored once it has
 * covered this far. A jammed receiver wanders tens of metres while the bike stands still, and
 * every such stretch would otherwise be saved as a ride of its own; a ride the rider stops by
 * hand is stored whatever its length.
 */
const val MIN_AUTO_SAVED_DISTANCE_M = 200.0
/** Reduced GPS cadence while in standby — lighter on the battery, still catches movement quickly. */
const val STANDBY_GPS_INTERVAL_MS = 4_000L
const val STANDBY_GPS_MIN_INTERVAL_MS = 2_000L

// --- Point filtering ---
/** Above this reported horizontal accuracy (meters) a fix is recorded but flagged as weak: the
 *  position is rough, and the UI says so. The Kalman filter weights every fix by its accuracy,
 *  so a weak one nudges the track instead of yanking it. */
const val ACCURACY_THRESHOLD_M = 25f
/** Above this accuracy (meters) a fix is dropped outright — too vague to place a bike with.
 *  Kept well clear of [ACCURACY_THRESHOLD_M]: where the signal is jammed or blocked, a rough
 *  fix still beats recording nothing at all. */
const val ACCURACY_LIMIT_M = 50f
/** Drop a segment implying a speed above this (m/s ≈ 108 km/h) — almost surely a GPS jump. */
const val MAX_PLAUSIBLE_SPEED_MPS = 30.0
/**
 * The recorded track must cover at least this fraction of the ground a reported speed implies
 * before that speed can stand as the ride's maximum. A receiver's speed is Doppler-derived and
 * legitimately runs a little ahead of the smoothed track, hence well below 1; the speeds a jammed
 * receiver invents for a bike standing still fall far short of it.
 */
const val SPEED_CORROBORATION_FRACTION = 0.5

// --- Stopped-speed threshold ---
// Defaults for "the rider is stopped"; the live auto-pause thresholds are user settings in
// AppSettings, while these drive ride-stats stop detection and the heading gate.
/** Below this speed (m/s ≈ 2 km/h) the rider counts as stopped. */
const val AUTO_PAUSE_SPEED_MPS = 2.0 / MPS_TO_KMH
/** A stopped stretch must last at least this long to count. */
const val AUTO_PAUSE_DEBOUNCE_MS = 10_000L
/**
 * Distance from the spot where the rider was last seen standing that means they have left,
 * whatever speed the fixes claim. A jammed or obstructed receiver reports a speed of 0 — or no
 * speed at all — for a bike that is moving, so speed alone can leave a ride paused for the whole
 * trip; the position is the one signal that still means something. Cleared with the two fixes'
 * own error circles on top, so noise cannot fake it.
 */
const val LEFT_ANCHOR_DISTANCE_M = 60.0

// --- GPS signal quality ---
/** No fix for this long while tracking means the GPS signal is effectively lost — the UI says so
 *  and the live timer stops speculating. It does not by itself break the recorded segment: fixes
 *  this far apart are routine where the signal is jammed, and the ride goes on. See
 *  [RECORDING_OUTAGE_MS] for the rule that does decide a break. */
const val GPS_STALE_MS = 10_000L
/**
 * Silence longer than this between two consecutive fixes is a recording outage whatever the step
 * between them looks like: nothing the tracker observed connects the two points, so the stretch
 * adds neither distance nor moving time and no line may be drawn across it. Deliberately far above
 * [GPS_STALE_MS] — a receiver that is jammed, indoors, or under trees still delivers usable fixes,
 * just tens of seconds apart, and calling every one of those a break is what would record a ride
 * that covered kilometres as zero distance in zero time, drawn as nothing at all.
 */
const val RECORDING_OUTAGE_MS = 120_000L
/**
 * How long the reference fix may go without a single accepted successor before a fix that
 * disagrees with it is trusted over it instead. The plausible-speed test compares every fix
 * against the last accepted one, and a rejected fix never replaces that reference — so a bogus
 * reference (a spoofed fix, common wherever GPS is jammed) would reject every genuine fix for as
 * long as it takes the allowed distance to catch up, hours for a spoof that lands far away. Past
 * this age the reference has no claim to be current and the tracker re-anchors on the new fix.
 */
const val FIX_REANCHOR_MS = 30_000L

// --- Draft persistence ---
/** Flush recorded points to the draft trip every this many points (~30 s at GPS cadence). */
const val DRAFT_FLUSH_EVERY_POINTS = 20

// --- Route display smoothing ---
/** Centered moving-average window (points) applied before drawing a track. */
const val ROUTE_SMOOTH_WINDOW = 5
/** Douglas-Peucker tolerance (meters): detail below this is GPS noise, not geometry. */
const val ROUTE_SIMPLIFY_TOLERANCE_M = 2.0
/** How far from its first point a track must get before that stretch is read as the direction the
 *  ride set off in; see [routeStartHeading]. */
const val ROUTE_START_SPAN_M = 20.0
/** Meters per degree of latitude — good enough for the local planar math below. */
private const val METERS_PER_DEGREE = 111_320.0

// --- Time windows ---
const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * Milliseconds from [nowMillis] to the next local midnight in [timeZone] — the boundary at
 * which day-granular UI (the calendar week/month/year History windows) re-anchors itself.
 * Calendar-based, so DST days of 23/25 hours land on the true midnight; always at least 1 ms to
 * keep timer loops safe.
 */
fun millisUntilNextMidnight(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (calendar.timeInMillis - nowMillis).coerceAtLeast(1L)
}

/** Zeroes the time-of-day fields of [calendar] to local midnight, leaving the date untouched. */
private fun Calendar.atStartOfDay() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/**
 * Epoch millis of the local-midnight start of the calendar week containing [nowMillis], in
 * [timeZone]. The week's first day follows the locale (Monday in most, Sunday in some), so the
 * History "Week" total covers this calendar week rather than a rolling seven days.
 */
fun startOfWeekMillis(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(timeZone).run {
        timeInMillis = nowMillis
        atStartOfDay()
        var daysSinceWeekStart = get(Calendar.DAY_OF_WEEK) - firstDayOfWeek
        if (daysSinceWeekStart < 0) daysSinceWeekStart += 7
        add(Calendar.DAY_OF_YEAR, -daysSinceWeekStart)
        timeInMillis
    }

/** Epoch millis of the local-midnight first day of the calendar month containing [nowMillis]. */
fun startOfMonthMillis(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(timeZone).run {
        timeInMillis = nowMillis
        atStartOfDay()
        set(Calendar.DAY_OF_MONTH, 1)
        timeInMillis
    }

/** Epoch millis of the local-midnight first day of the calendar year containing [nowMillis]. */
fun startOfYearMillis(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(timeZone).run {
        timeInMillis = nowMillis
        atStartOfDay()
        set(Calendar.DAY_OF_YEAR, 1)
        timeInMillis
    }

// --- Preferences ---
/** Single SharedPreferences file for all app settings. */
const val PREFS_NAME = "biketracker_prefs"

/** A latitude/longitude pair; the live route and stored track are ordered lists of these.
 *  [timeMillis] is the recording wall time (epoch), 0 when unknown — kept for clock labels only.
 *  [speedMps] is the GPS speed of the fix (0 when the fix had none), carried so the speed chart
 *  can plot live and stored tracks alike. [segmentStart] is true on the first fix after a
 *  manual/auto pause or a GPS outage — the explicit recording-segment boundary
 *  ([isSegmentBoundary]); it is persisted, so short pauses split correctly regardless of the
 *  wall-clock gap. [elapsedMillis] is monotonic time since ride start (from elapsed-realtime),
 *  the wall-clock-safe basis for the chart's time axis; null on rides recorded before it existed. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val timeMillis: Long = 0L,
    val speedMps: Float = 0f,
    val segmentStart: Boolean = false,
    val elapsedMillis: Long? = null,
)

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

/** Compass bearing from one coordinate to another, in degrees clockwise from north. */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * The direction a ride set off in: the bearing from its first point to the first point at least
 * [ROUTE_START_SPAN_M] away, so the jitter of a bike still standing at the start doesn't decide
 * it. Null when the track never gets that far from where it began — there is no direction to show.
 */
fun routeStartHeading(route: List<GeoPoint>): Double? {
    val start = route.firstOrNull() ?: return null
    val away = route.firstOrNull {
        haversineMeters(start.lat, start.lon, it.lat, it.lon) >= ROUTE_START_SPAN_M
    } ?: return null
    return bearingDegrees(start.lat, start.lon, away.lat, away.lon)
}

/** Average speed in m/s, derived from distance and moving time (0 if no time elapsed). */
fun avgSpeedMps(distanceMeters: Double, movingTimeMillis: Long): Double =
    if (movingTimeMillis > 0) distanceMeters / (movingTimeMillis / 1000.0) else 0.0

/** Metabolic equivalent (MET) of cycling at [speedKmh], from the Compendium of Physical
 *  Activities' effort bands. Assumes the rider provides the power, so it is not meaningful for a
 *  motor-assisted ride. */
fun cyclingMet(speedKmh: Double): Double = when {
    speedKmh < 16 -> 4.0
    speedKmh < 19 -> 6.8
    speedKmh < 22 -> 8.0
    speedKmh < 25 -> 10.0
    speedKmh < 30 -> 12.0
    else -> 15.8
}

/** Rough energy burned (kcal) over a ride: the MET at its average moving speed times body weight
 *  times hours in motion (1 MET ≈ 1 kcal/kg/h). 0 when weight or moving time is unknown. */
fun caloriesKcal(distanceMeters: Double, movingTimeMillis: Long, weightKg: Int): Double {
    if (weightKg <= 0 || movingTimeMillis <= 0) return 0.0
    val hours = movingTimeMillis / 3_600_000.0
    val speedKmh = mpsToKmh(avgSpeedMps(distanceMeters, movingTimeMillis))
    return cyclingMet(speedKmh) * weightKg * hours
}

/**
 * Smallest "1-2-5 × 10^n" value that is ≥ [rawStep]. Unbounded above, so any finite positive
 * [rawStep] gets a round step and a tick count that stays within budget; non-finite or
 * non-positive input returns 1.0. Used to extend the fixed tick ladders below past their top
 * entry, so an arbitrarily long ride can't overflow its tick budget with the ladder's last step.
 */
fun niceTickStep(rawStep: Double): Double {
    if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val mantissa = rawStep / magnitude
    val nice = when {
        mantissa <= 1.0 -> 1.0
        mantissa <= 2.0 -> 2.0
        mantissa <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/** Round "1-2-5" distance step (meters) so a span of [spanMeters] gets at most [maxTicks]
 *  gridlines — shared by the speed chart's X axis and the elevation profile. Past the ladder's
 *  100 km top the 1-2-5 scale continues via [niceTickStep], so very long rides stay bounded. */
private val DISTANCE_TICK_STEPS_KM =
    doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)

fun distanceTickStepMeters(spanMeters: Double, maxTicks: Int): Double {
    val ticks = maxTicks.coerceAtLeast(1)
    val spanKm = spanMeters / METERS_PER_KM
    val stepKm = DISTANCE_TICK_STEPS_KM.firstOrNull { spanKm / it <= ticks }
        ?: niceTickStep(spanKm / ticks)
    return stepKm * METERS_PER_KM
}

/** X-axis time tick ladder (clean clock steps up to 4 h). Past the top the scale continues as a
 *  whole-hour 1-2-5 progression via [niceTickStep], so a multi-day ride keeps ≤ maxTicks ticks. */
private val TIME_TICK_STEPS_MIN =
    doubleArrayOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 240.0)

/** Round time tick step (millis) so a span of [spanMillis] gets at most [maxTicks] gridlines. */
fun timeTickStepMillis(spanMillis: Double, maxTicks: Int): Double {
    val ticks = maxTicks.coerceAtLeast(1)
    val spanMin = spanMillis / 60_000.0
    val stepMin = TIME_TICK_STEPS_MIN.firstOrNull { spanMin / it <= ticks }
        ?: niceTickStep(spanMin / ticks / 60.0) * 60.0
    return stepMin * 60_000.0
}

/**
 * Whether a recorded ride is worth storing. Any ride needs a track to draw and some ground to
 * show for itself. One nobody asked to close — the long-pause auto-save, or the recovery pass
 * finalizing a draft left behind by a dead process, [automatic] — must also have covered
 * [MIN_AUTO_SAVED_DISTANCE_M]: the alternative is a history littered with the few-dozen-metre
 * stretches a jammed receiver records for a bike standing still. The rider's own Stop is never
 * second-guessed, however short the ride.
 */
fun isRideWorthSaving(
    pointCount: Int,
    distanceMeters: Double,
    automatic: Boolean,
): Boolean = pointCount >= 2 &&
    distanceMeters > 0.0 &&
    (!automatic || distanceMeters >= MIN_AUTO_SAVED_DISTANCE_M)

/**
 * Whether the stretch between two consecutive recorded fixes is a recording discontinuity — a
 * pause or a GPS outage — rather than a coarsely sampled piece of the ride. [dtMillis] is the time
 * between the fixes, [stepMeters] the ground between them.
 *
 * Time alone cannot tell the two apart. Where the signal is jammed or obstructed the receiver keeps
 * delivering usable fixes, just tens of seconds apart, and treating every such step as a break is
 * what would record a real ride as zero distance in zero time. What separates the cases is whether
 * the step is explicable: ground a bike could actually have covered in the time is a real, if
 * coarsely sampled, movement however long the interval. A step nobody could have ridden
 * ([MAX_PLAUSIBLE_SPEED_MPS]), or a silence past [RECORDING_OUTAGE_MS] that no sampling interval
 * accounts for, is the true break — and only then does the stretch add nothing.
 *
 * A backward or zero step in time is never a gap: there is no stretch to judge.
 */
fun isRecordingGap(dtMillis: Long, stepMeters: Double): Boolean {
    if (dtMillis <= 0L) return false
    if (dtMillis > RECORDING_OUTAGE_MS) return true
    return stepMeters / (dtMillis / 1000.0) > MAX_PLAUSIBLE_SPEED_MPS
}

/**
 * The boundary between two consecutive recorded fixes. A recording segment ends and a new one
 * begins at the first fix after a manual/auto pause or a GPS outage.
 *
 * [hasElapsedMetadata] says whether the track knows its own boundaries. New rides do, and so does
 * an imported GPX (its `<trkseg>` divisions, resolved by [xx.biketracker.data.parseGpx]): their
 * [segmentStart] flags are then the whole answer and the wall time is ignored entirely, so neither
 * a forward clock change nor a sampling cadence unlike this app's can be misread as a pause. Old
 * rides, recorded before the flag existed, have only their epoch times to go on and fall back to
 * [isRecordingGap]; points without a timestamp (0, old data recorded before times reached the
 * route) can't be measured against each other at all and never split.
 *
 * [stepMeters] is evaluated only on that legacy path, so callers may compute the distance lazily.
 */
inline fun isSegmentBoundary(
    prevTimeMillis: Long,
    timeMillis: Long,
    segmentStart: Boolean,
    hasElapsedMetadata: Boolean,
    stepMeters: () -> Double,
): Boolean {
    if (segmentStart) return true
    if (hasElapsedMetadata) return false
    if (prevTimeMillis <= 0L || timeMillis <= 0L) return false
    return isRecordingGap(timeMillis - prevTimeMillis, stepMeters())
}

/**
 * Monotonic time step (ms) between two consecutive recorded points: the persisted elapsed-realtime
 * delta when both points carry it, otherwise the wall-clock delta for legacy rows. Clamped to ≥ 0
 * so a backward clock correction can never make cumulative time or a time axis run backward.
 */
fun monotonicStepMillis(
    prevElapsedMillis: Long?,
    elapsedMillis: Long?,
    prevTimeMillis: Long,
    timeMillis: Long,
): Long {
    val delta = if (prevElapsedMillis != null && elapsedMillis != null) {
        elapsedMillis - prevElapsedMillis
    } else {
        timeMillis - prevTimeMillis
    }
    return delta.coerceAtLeast(0L)
}

/**
 * Split a route into the segments that were actually recorded: drawing across a boundary
 * (see [isSegmentBoundary]) would show travel the tracker never saw.
 */
fun splitRouteSegments(route: List<GeoPoint>): List<List<GeoPoint>> {
    if (route.isEmpty()) return emptyList()
    val segments = mutableListOf(mutableListOf(route.first()))
    for (i in 1 until route.size) {
        val prev = route[i - 1]
        val point = route[i]
        val boundary = isSegmentBoundary(
            prev.timeMillis, point.timeMillis, point.segmentStart, point.elapsedMillis != null,
        ) { haversineMeters(prev.lat, prev.lon, point.lat, point.lon) }
        if (boundary) {
            segments += mutableListOf(point)
        } else {
            segments.last() += point
        }
    }
    return segments
}

/**
 * Display-only track smoothing: a centered moving average irons out per-fix GPS scatter,
 * then Douglas-Peucker drops the points that no longer add geometry. Stored points are
 * untouched, so this also benefits every previously recorded ride.
 */
fun smoothRoute(route: List<GeoPoint>): List<GeoPoint> {
    if (route.size < 3) return route
    return simplifyRoute(movingAverage(route, ROUTE_SMOOTH_WINDOW), ROUTE_SIMPLIFY_TOLERANCE_M)
}

/** Centered moving average; the very first and last points are kept raw, so the track stays
 *  anchored to the true start/finish and the live puck sits on the drawn line's end. */
private fun movingAverage(points: List<GeoPoint>, window: Int): List<GeoPoint> {
    val half = window / 2
    return List(points.size) { i ->
        if (i == 0 || i == points.lastIndex) return@List points[i]
        val from = max(0, i - half)
        val to = min(points.lastIndex, i + half)
        var lat = 0.0
        var lon = 0.0
        for (j in from..to) {
            lat += points[j].lat
            lon += points[j].lon
        }
        val n = to - from + 1
        GeoPoint(lat / n, lon / n)
    }
}

/** Douglas-Peucker on a local planar projection (meters), iterative to spare the stack. */
private fun simplifyRoute(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (points.size < 3) return points
    val cosLat = cos(Math.toRadians(points.first().lat))
    val xs = DoubleArray(points.size) { points[it].lon * cosLat * METERS_PER_DEGREE }
    val ys = DoubleArray(points.size) { points[it].lat * METERS_PER_DEGREE }
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val ranges = ArrayDeque<Pair<Int, Int>>()
    ranges.addLast(0 to points.lastIndex)
    while (ranges.isNotEmpty()) {
        val (first, last) = ranges.removeLast()
        if (last - first < 2) continue
        var maxDist = 0.0
        var farthest = -1
        for (i in first + 1 until last) {
            val d = pointToSegmentMeters(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
            if (d > maxDist) {
                maxDist = d
                farthest = i
            }
        }
        if (maxDist > toleranceM) {
            keep[farthest] = true
            ranges.addLast(first to farthest)
            ranges.addLast(farthest to last)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

private fun pointToSegmentMeters(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val lengthSq = dx * dx + dy * dy
    if (lengthSq == 0.0) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

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

/**
 * Total ascent that starts a fresh altitude reference at each recording boundary, so a height
 * change across a pause or GPS outage — points the rider never connected — is not counted as a
 * climb. [descent] negates altitudes to measure drops instead. Boundaries are detected with
 * [isSegmentBoundary], so legacy rows fall back to the wall-time gap heuristic.
 */
fun elevationGainBySegment(
    points: List<TrackPoint>,
    descent: Boolean = false,
    thresholdMeters: Double = 3.0,
): Double {
    var gain = 0.0
    val segment = ArrayList<Double?>()
    fun flush() {
        if (segment.isNotEmpty()) gain += elevationGainMeters(segment, thresholdMeters)
        segment.clear()
    }
    for (i in points.indices) {
        val p = points[i]
        if (i > 0) {
            val prev = points[i - 1]
            val boundary = isSegmentBoundary(
                prev.time, p.time, p.segmentStart, p.elapsedMillis != null,
            ) { haversineMeters(prev.lat, prev.lon, p.lat, p.lon) }
            if (boundary) flush()
        }
        val a = p.altitudeMeters
        segment += if (a != null && descent) -a else a
    }
    flush()
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

/** Day-of-month then the weekday's abbreviation for the date browser, e.g. "22 Sat" / "22 суб".
 *  The names come from the caller (R.array.weekday_short) rather than the locale's own, which
 *  runs to two letters in ru/uk; they are indexed Sunday-first, like Calendar.DAY_OF_WEEK. */
fun formatDayLabel(epochMillis: Long, weekdayNames: List<String>): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "${cal.get(Calendar.DAY_OF_MONTH)} ${weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
}

/** Sortable date-time stamp "yyyy-MM-dd HH:mm" — a ride's default label and its GPX track name.
 *  Locale.US keeps it identical to the exported/imported GPX name (the digits are locale-neutral). */
fun formatRideStamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

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
