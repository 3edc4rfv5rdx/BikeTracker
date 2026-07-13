package xx.biketracker.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xx.biketracker.data.TrackPoint
import xx.biketracker.data.Trip

internal data class DraftCheckpoint(
    val points: List<TrackPoint>,
    val trip: Trip,
)

internal interface DraftPersistenceGateway {
    suspend fun insertDraft(trip: Trip): Long
    suspend fun pointCount(tripId: Long): Int
    suspend fun commit(tripId: Long, newPoints: List<TrackPoint>, trip: Trip)
    suspend fun delete(tripId: Long)
}

/** Serializes draft writes and reconciles retries against durable database state. */
internal class DraftPersistence(
    private val initialTrip: Trip,
    private val gateway: DraftPersistenceGateway,
) {
    private val mutex = Mutex()
    private var draftId: Long? = null

    var durablePointCount: Int = 0
        private set

    suspend fun ensureDraft(): Result<Long> = operation {
        ensureDraftLocked()
    }

    suspend fun persist(checkpoint: DraftCheckpoint): Result<Unit> = operation {
        val id = ensureDraftLocked()
        val storedCount = gateway.pointCount(id)
        check(storedCount in 0..checkpoint.points.size) {
            "Draft contains $storedCount points, checkpoint contains ${checkpoint.points.size}"
        }
        val missing = checkpoint.points
            .subList(storedCount, checkpoint.points.size)
            .map { it.copy(tripId = id) }
        gateway.commit(id, missing, checkpoint.trip.copy(id = id))
        durablePointCount = checkpoint.points.size
    }

    suspend fun discard(): Result<Unit> = operation {
        draftId?.let { gateway.delete(it) }
        draftId = null
        durablePointCount = 0
    }

    private suspend fun ensureDraftLocked(): Long {
        draftId?.let { return it }
        return gateway.insertDraft(initialTrip).also { draftId = it }
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = mutex.withLock {
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
