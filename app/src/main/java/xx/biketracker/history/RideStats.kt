package xx.biketracker.history

import xx.biketracker.AUTO_PAUSE_DEBOUNCE_MS
import xx.biketracker.AUTO_PAUSE_SPEED_MPS
import xx.biketracker.MPS_TO_KMH
import xx.biketracker.data.TrackPoint
import xx.biketracker.elevationGainMeters
import xx.biketracker.haversineMeters
import xx.biketracker.isSegmentBoundary
import xx.biketracker.monotonicStepMillis

/** Upper bounds (km/h) of the speed-histogram buckets; the last bucket is open-ended, so these
 *  three bounds make four buckets: 0-10, 10-20, 20-30, 30+. */
val SPEED_ZONE_BOUNDS_KMH = doubleArrayOf(10.0, 20.0, 30.0)

/** One altitude reading placed along the ride, for the elevation profile (x = distance so far). */
class ElevationPoint(val distanceMeters: Double, val altitudeMeters: Double)

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
 * A stop is a stretch of recorded motion below [AUTO_PAUSE_SPEED_MPS] lasting at least
 * [AUTO_PAUSE_DEBOUNCE_MS] — the same signal auto-pause reacts to, so a red-light crawl counts but
 * a brief coast does not. A recording boundary (pause or GPS outage) is conservatively never
 * counted as a stop: a boolean boundary can't tell a café pause from a tunnel, so its gap adds to
 * neither stopped time nor the buckets. Time comes from the monotonic
 * [xx.biketracker.data.TrackPoint.elapsedMillis] so a mid-ride clock change can't distort it.
 * Every non-boundary interval lands in a speed bucket, so the buckets sum to the ride's moving
 * time; distance and buckets ignore recording gaps like the trip totals.
 */
fun computeRideStats(points: List<TrackPoint>): RideStats {
    val profile = ArrayList<ElevationPoint>()
    val zones = LongArray(SPEED_ZONE_BOUNDS_KMH.size + 1)
    var distance = 0.0
    var stopCount = 0
    var stoppedMillis = 0L
    var runMillis = 0L // length of the current below-threshold run, still to be judged a stop or not

    // Close the pending low-speed run: a real stop once it reaches the debounce, otherwise brief
    // low-speed motion that still belongs to the ride's moving time, so it enters the slowest bucket.
    fun closeRun() {
        if (runMillis >= AUTO_PAUSE_DEBOUNCE_MS) {
            stopCount++
            stoppedMillis += runMillis
        } else {
            zones[0] += runMillis
        }
        runMillis = 0L
    }

    for (i in points.indices) {
        val p = points[i]
        if (i > 0) {
            val prev = points[i - 1]
            val step = monotonicStepMillis(prev.elapsedMillis, p.elapsedMillis, prev.time, p.time)
            if (isSegmentBoundary(prev.time, p.time, p.segmentStart, p.elapsedMillis != null)) {
                // A pause/outage gap is added to neither the run nor any bucket; only recorded
                // low-speed motion counts as a stop.
                closeRun()
            } else {
                distance += haversineMeters(prev.lat, prev.lon, p.lat, p.lon)
                if (p.speedMps < AUTO_PAUSE_SPEED_MPS) {
                    runMillis += step // slow enough to be stopping; the run decides if it's a real stop
                } else {
                    closeRun()
                    zones[zoneIndexFor(p.speedMps)] += step
                }
            }
        }
        p.altitudeMeters?.let { profile.add(ElevationPoint(distance, it)) }
    }
    closeRun()

    val descent = if (profile.isEmpty()) null
    else elevationGainMeters(points.map { pt -> pt.altitudeMeters?.let { -it } })

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

/** Index of the speed bucket a fix falls in; the open top bucket catches everything past the last bound. */
private fun zoneIndexFor(speedMps: Float): Int {
    val kmh = speedMps * MPS_TO_KMH
    for (i in SPEED_ZONE_BOUNDS_KMH.indices) if (kmh < SPEED_ZONE_BOUNDS_KMH[i]) return i
    return SPEED_ZONE_BOUNDS_KMH.size
}
