package xx.biketracker.data

import android.content.Context
import kotlinx.coroutines.Job
import xx.biketracker.elevationGainMeters
import xx.biketracker.tracking.TrackingState
import xx.biketracker.tracking.TrackingStatus

/**
 * The launch-time recovery pass started by MainActivity. Restore joins it before closing the
 * database: closing mid-pass would crash the queries still running here.
 */
@Volatile
var recoveryJob: Job? = null

/**
 * Finalize trips left unfinished by a process death mid-ride. The service flushes points and
 * running aggregates into a draft row as it records, so everything up to the last flush is
 * recoverable: drafts with real data are marked finished (with the point reductions computed as
 * at a normal save), empty ones are deleted. Trips started at or after [startedBefore] are
 * skipped — they belong to a ride that may have just begun, not to a dead process.
 */
suspend fun finalizeAbandonedTrips(context: Context, startedBefore: Long) {
    // A live service in this process owns its draft; never touch it.
    if (TrackingState.snapshot.value.status != TrackingStatus.IDLE) return
    DatabaseMaintenance.withWrite {
        val dao = AppDatabase.get(context).tripDao()
        for (trip in dao.getUnfinishedTrips()) {
            if (trip.startTime >= startedBefore) continue
            val points = dao.getPoints(trip.id)
            if (points.size >= 2 && trip.distanceMeters > 0) {
                val altitudes = points.map { it.altitudeMeters }
                dao.updateTrip(
                    trip.copy(
                        endTime = points.last().time,
                        avgGpsSpeedMps = points.map { it.speedMps.toDouble() }.average(),
                        elevationGainMeters = if (altitudes.any { it != null }) elevationGainMeters(altitudes) else null,
                        finished = true,
                    )
                )
            } else {
                dao.deleteTripById(trip.id) // cascade removes any flushed points
            }
        }
    }
}
