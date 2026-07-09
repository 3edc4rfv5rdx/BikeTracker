package xx.biketracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPoint>)

    @Update
    suspend fun updateTrip(trip: Trip)

    @Delete
    suspend fun deleteTrip(trip: Trip)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: Long)

    /** Draft rows of rides interrupted by a process death; see finalizeAbandonedTrips(). */
    @Query("SELECT * FROM trips WHERE finished = 0")
    suspend fun getUnfinishedTrips(): List<Trip>

    /** History list, newest first; updates live as trips are added or removed. */
    @Query("SELECT * FROM trips WHERE finished = 1 ORDER BY startTime DESC")
    fun observeTrips(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTrip(tripId: Long): Trip?

    /** Route points of one trip in recorded order — used for the map, stats, and GPX export. */
    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY time ASC")
    suspend fun getPoints(tripId: Long): List<TrackPoint>

    /** Total distance and moving time over trips that started at or after [sinceEpochMillis]. */
    @Query(
        "SELECT COALESCE(SUM(distanceMeters), 0) AS distanceMeters, " +
            "COALESCE(SUM(movingTimeMillis), 0) AS movingTimeMillis, " +
            "COUNT(*) AS rideCount " +
            "FROM trips WHERE finished = 1 AND startTime >= :sinceEpochMillis"
    )
    fun observeTotals(sinceEpochMillis: Long): Flow<RideTotals>
}

/** Aggregate row for the History summary (week / month / year / all time). */
data class RideTotals(
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val rideCount: Int,
)
