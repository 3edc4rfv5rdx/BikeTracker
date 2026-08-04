package xx.biketracker.history

import xx.biketracker.AUTO_PAUSE_DEBOUNCE_MS
import xx.biketracker.AUTO_PAUSE_SPEED_MPS
import xx.biketracker.LEFT_ANCHOR_DISTANCE_M
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.data.TrackPoint
import xx.biketracker.elevationGainBySegment
import xx.biketracker.haversineMeters
import xx.biketracker.isSegmentBoundary
import xx.biketracker.monotonicStepMillis
import kotlin.math.max

/** Upper bounds (km/h) of the speed-histogram buckets; the last bucket is open-ended, so these
 *  three bounds make four buckets: 0-10, 10-20, 20-30, 30+. */
val SPEED_ZONE_BOUNDS_KMH = doubleArrayOf(10.0, 20.0, 30.0)

/** One altitude reading placed along the ride, for the elevation profile (x = distance so far).
 *  [segmentStart] marks the first reading after a recording boundary, where the profile must break
 *  rather than draw a vertical cliff across the gap. */
class ElevationPoint(
    val distanceMeters: Double,
    val altitudeMeters: Double,
    val segmentStart: Boolean = false,
)

/**
 * Extended per-ride figures derived from the track points in one pass — none are stored on the
 * [xx.biketracker.data.Trip] row. Altitude-based fields are null for rides recorded without GPS
 * altitude (older or imported data), so the screen shows a dash instead of a wrong zero.
 */
class RideStats(
    val elevationProfile: List<ElevationPoint>,
    val minAltitudeMeters: Double?,
    val maxAltitudeMeters: Double?,
    val descentMeters: Double?,
    /** Moving time spent in each speed bucket; indexed like [SPEED_ZONE_BOUNDS_KMH] plus the open top. */
    val speedZoneMillis: LongArray,
    val stopCount: Int,
    val stoppedMillis: Long,
) {
    val hasAltitude: Boolean get() = elevationProfile.isNotEmpty()
}

/**
 * A stop is a stretch of recorded motion below [AUTO_PAUSE_SPEED_MPS] that stayed where it was for
 * at least [AUTO_PAUSE_DEBOUNCE_MS] — the same signal auto-pause reacts to, so a red-light crawl
 * counts but a brief coast does not.
 *
 * Slow fixes on their own do not make a stop. Where the signal is jammed the receiver reports 0 for
 * a bike that is moving, and the positions are the one thing that still means something — the
 * reasoning the tracker itself follows in [xx.biketracker.tracking.hasLeftAnchor]. A run that got
 * further than [LEFT_ANCHOR_DISTANCE_M] from where it began was therefore motion the fixes failed
 * to report; its time stays in the ride, bucketed at the pace its own positions imply. Distance
 * from that spot, not the path walked: at a true standstill the jitter of a long stop adds up to a
 * journey, while the spot it jitters around does not move. Per-point accuracy is not persisted, so
 * the threshold cannot be widened by the fixes' own error circles the way the live tracker widens
 * it — the plain distance has to carry it.
 *
 * A recording boundary (pause or GPS outage) is conservatively never counted as a stop: a boolean
 * boundary can't tell a café pause from a tunnel, so its gap adds to neither stopped time nor the
 * buckets. Time comes from the monotonic [xx.biketracker.data.TrackPoint.elapsedMillis] so a
 * mid-ride clock change can't distort it. Every non-boundary interval lands either in a speed
 * bucket or in stopped time, so the two together account for the ride's moving time; distance and
 * buckets ignore recording gaps like the trip totals.
 */
fun computeRideStats(points: List<TrackPoint>): RideStats {
    val profile = ArrayList<ElevationPoint>()
    val zones = LongArray(SPEED_ZONE_BOUNDS_KMH.size + 1)
    var distance = 0.0
    var stopCount = 0
    var stoppedMillis = 0L
    var pendingProfileBreak = false // a boundary was crossed; the next altitude opens a new segment
    // The current below-threshold run, still to be judged a stop or motion the fixes missed: how
    // long it has lasted, the spot it began at, the furthest it ever got from there, and the ground
    // it covered getting about.
    var runMillis = 0L
    var runAnchor: TrackPoint? = null
    var runAwayMeters = 0.0
    var runPathMeters = 0.0

    // Close the pending run. One that stayed put long enough is a stop; one that got away from
    // where it began was movement the fixes missed, and one too brief to judge is a coast — both of
    // those keep their time in the ride, in the bucket their own positions earn.
    fun closeRun() {
        if (runMillis > 0L) {
            if (runMillis >= AUTO_PAUSE_DEBOUNCE_MS && runAwayMeters <= LEFT_ANCHOR_DISTANCE_M) {
                stopCount++
                stoppedMillis += runMillis
            } else {
                zones[zoneIndexFor(runPathMeters / (runMillis / 1000.0))] += runMillis
            }
        }
        runMillis = 0L
        runAnchor = null
        runAwayMeters = 0.0
        runPathMeters = 0.0
    }

    for (i in points.indices) {
        val p = points[i]
        if (i > 0) {
            val prev = points[i - 1]
            val step = monotonicStepMillis(prev.elapsedMillis, p.elapsedMillis, prev.time, p.time)
            val stepMeters = haversineMeters(prev.lat, prev.lon, p.lat, p.lon)
            val boundary = isSegmentBoundary(
                prev.time, p.time, p.segmentStart, p.elapsedMillis != null,
            ) { stepMeters }
            if (boundary) {
                // A pause/outage gap is added to neither the run nor any bucket; only recorded
                // low-speed motion counts as a stop. The profile breaks here too.
                closeRun()
                pendingProfileBreak = true
            } else {
                distance += stepMeters
                if (p.speedMps < AUTO_PAUSE_SPEED_MPS) {
                    // Slow enough to be stopping; the run decides whether it really was. It is
                    // anchored to the spot the bike was last seen moving from.
                    if (runMillis == 0L) runAnchor = prev
                    runMillis += step
                    runPathMeters += stepMeters
                    runAnchor?.let {
                        runAwayMeters =
                            max(runAwayMeters, haversineMeters(it.lat, it.lon, p.lat, p.lon))
                    }
                } else {
                    closeRun()
                    zones[zoneIndexFor(p.speedMps.toDouble())] += step
                }
            }
        }
        p.altitudeMeters?.let {
            profile.add(ElevationPoint(distance, it, segmentStart = pendingProfileBreak))
            pendingProfileBreak = false
        }
    }
    closeRun()

    val descent = if (profile.isEmpty()) null else elevationGainBySegment(points, descent = true)

    return RideStats(
        elevationProfile = profile,
        minAltitudeMeters = profile.minOfOrNull { it.altitudeMeters },
        maxAltitudeMeters = profile.maxOfOrNull { it.altitudeMeters },
        descentMeters = descent,
        speedZoneMillis = zones,
        stopCount = stopCount,
        stoppedMillis = stoppedMillis,
    )
}

/** Index of the speed bucket a pace falls in; the open top bucket catches everything past the last
 *  bound. Fed either a fix's reported speed or the pace a run's own positions imply. */
private fun zoneIndexFor(speedMps: Double): Int {
    val kmh = speedMps * MPS_TO_KMH
    for (i in SPEED_ZONE_BOUNDS_KMH.indices) if (kmh < SPEED_ZONE_BOUNDS_KMH[i]) return i
    return SPEED_ZONE_BOUNDS_KMH.size
}
