package xx.biketracker.tracking

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.data.TrackPoint
import xx.biketracker.data.Trip

class DraftPersistenceTest {

    private val initialTrip = trip(finished = false)

    @Test
    fun initialInsertFailureCanBeRetried() = runBlocking {
        val gateway = FakeGateway().apply { failNextInsert = true }
        val persistence = DraftPersistence(initialTrip, gateway)

        assertTrue(persistence.ensureDraft().isFailure)
        assertTrue(persistence.ensureDraft().isSuccess)
        assertEquals(2, gateway.insertCalls)
    }

    @Test
    fun failedMiddleFlushRetainsBatchForRetry() = runBlocking {
        val gateway = FakeGateway()
        val persistence = DraftPersistence(initialTrip, gateway)

        assertTrue(persistence.persist(checkpoint(2)).isSuccess)
        gateway.failNextCommitBeforeWrite = true
        assertTrue(persistence.persist(checkpoint(4)).isFailure)
        assertEquals(2, persistence.durablePointCount)
        assertEquals(2, gateway.points.size)

        assertTrue(persistence.persist(checkpoint(4)).isSuccess)
        assertEquals(listOf(1L, 2L, 3L, 4L), gateway.points.map { it.time })
        assertEquals(4, persistence.durablePointCount)
    }

    @Test
    fun finalTransactionFailureLeavesRecoverableDraftAndRetries() = runBlocking {
        val gateway = FakeGateway()
        val persistence = DraftPersistence(initialTrip, gateway)
        assertTrue(persistence.persist(checkpoint(2)).isSuccess)

        gateway.failNextCommitBeforeWrite = true
        assertTrue(persistence.persist(checkpoint(3, finished = true)).isFailure)
        assertFalse(gateway.trip!!.finished)
        assertEquals(2, gateway.points.size) // process death can recover this durable prefix

        assertTrue(persistence.persist(checkpoint(3, finished = true)).isSuccess)
        assertTrue(gateway.trip!!.finished)
        assertEquals(3, gateway.points.size)
    }

    @Test
    fun diskFullDoesNotAdvanceDurableCursor() = runBlocking {
        val gateway = FakeGateway().apply { commitFailure = IOException("disk full") }
        val persistence = DraftPersistence(initialTrip, gateway)

        val result = persistence.persist(checkpoint(3))

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, persistence.durablePointCount)
        assertTrue(gateway.points.isEmpty())
    }

    @Test
    fun ambiguousCommitIsExactlyOnceOnRetry() = runBlocking {
        val gateway = FakeGateway().apply { failNextCommitAfterWrite = true }
        val persistence = DraftPersistence(initialTrip, gateway)

        assertTrue(persistence.persist(checkpoint(3)).isFailure)
        assertEquals(3, gateway.points.size)
        assertEquals(0, persistence.durablePointCount)

        assertTrue(persistence.persist(checkpoint(3)).isSuccess)
        assertEquals(listOf(1L, 2L, 3L), gateway.points.map { it.time })
        assertEquals(3, persistence.durablePointCount)
    }

    @Test
    fun aCheckpointOvertakenByANewerOneIsNotAFailedSave() = runBlocking {
        // Two flushes in flight and the newer one commits first: the older then finds more points
        // stored than it holds. Nothing is wrong — everything it carries is durable already — and
        // calling that a failure puts "save failed" in front of a rider whose ride is fully stored.
        val gateway = FakeGateway()
        val persistence = DraftPersistence(initialTrip, gateway)
        assertTrue(persistence.persist(checkpoint(4)).isSuccess)

        assertTrue(persistence.persist(checkpoint(2)).isSuccess)
        assertEquals(listOf(1L, 2L, 3L, 4L), gateway.points.map { it.time })
        assertEquals(4, persistence.durablePointCount)
        assertEquals(1, gateway.commitCalls) // the overtaken checkpoint wrote nothing
    }

    @Test
    fun aDraftCountThatCannotBeExplainedAtAllStillFails() = runBlocking {
        // Being tolerant of a newer checkpoint is not being tolerant of nonsense.
        val gateway = FakeGateway().apply { pointCountOverride = -1 }
        val persistence = DraftPersistence(initialTrip, gateway)

        assertTrue(persistence.persist(checkpoint(3)).isFailure)
        assertTrue(gateway.points.isEmpty())
    }

    @Test
    fun aStormOfCheckpointsLeavesTheDraftWhole() = runBlocking {
        // Every flush of a ride submitted at once, reaching the writer in whatever order. However
        // they interleave, each writes only what is missing and none reports a failure.
        val gateway = FakeGateway()
        val persistence = DraftPersistence(initialTrip, gateway)

        val results = listOf(6, 2, 8, 4)
            .map { count -> async(Dispatchers.Default) { persistence.persist(checkpoint(count)) } }
            .awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals((1L..8L).toList(), gateway.points.map { it.time })
        assertEquals(8, persistence.durablePointCount)
    }

    @Test
    fun discardFailureCanBeRetriedWithoutCreatingAnotherDraft() = runBlocking {
        val gateway = FakeGateway()
        val persistence = DraftPersistence(initialTrip, gateway)
        assertTrue(persistence.persist(checkpoint(2)).isSuccess)
        gateway.failNextDelete = true

        assertTrue(persistence.discard().isFailure)
        assertTrue(persistence.discard().isSuccess)
        assertEquals(1, gateway.insertCalls)
        assertTrue(gateway.points.isEmpty())
    }

    private fun checkpoint(count: Int, finished: Boolean = false) = DraftCheckpoint(
        points = (1..count).map { index ->
            TrackPoint(
                tripId = 0,
                lat = 50.0,
                lon = 30.0,
                time = index.toLong(),
                speedMps = 3f,
            )
        },
        trip = trip(finished),
    )

    private class FakeGateway : DraftPersistenceGateway {
        var failNextInsert = false
        var failNextCommitBeforeWrite = false
        var failNextCommitAfterWrite = false
        var failNextDelete = false
        var commitFailure: Throwable? = null
        var pointCountOverride: Int? = null
        var insertCalls = 0
        var commitCalls = 0
        var trip: Trip? = null
        val points = mutableListOf<TrackPoint>()

        override suspend fun insertDraft(trip: Trip): Long {
            insertCalls++
            if (failNextInsert) {
                failNextInsert = false
                throw IOException("insert failed")
            }
            this.trip = trip.copy(id = 1)
            return 1
        }

        override suspend fun pointCount(tripId: Long): Int = pointCountOverride ?: points.size

        override suspend fun commit(tripId: Long, newPoints: List<TrackPoint>, trip: Trip) {
            commitCalls++
            commitFailure?.let { throw it }
            if (failNextCommitBeforeWrite) {
                failNextCommitBeforeWrite = false
                throw IOException("commit failed")
            }
            points += newPoints
            this.trip = trip
            if (failNextCommitAfterWrite) {
                failNextCommitAfterWrite = false
                throw IOException("commit result unknown")
            }
        }

        override suspend fun delete(tripId: Long) {
            if (failNextDelete) {
                failNextDelete = false
                throw IOException("delete failed")
            }
            trip = null
            points.clear()
        }
    }

    companion object {
        private fun trip(finished: Boolean) = Trip(
            startTime = 1,
            endTime = 4,
            distanceMeters = 10.0,
            movingTimeMillis = 3_000,
            maxSpeedMps = 3.0,
            finished = finished,
        )
    }
}
