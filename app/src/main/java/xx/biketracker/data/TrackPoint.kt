package xx.biketracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single GPS sample belonging to a trip. The route polyline is simply a trip's
 * points read back in [time] order. Deleting a trip cascades to its points.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("tripId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lon: Double,
    val time: Long,        // epoch millis
    val speedMps: Float,   // instantaneous speed reported by the GPS fix
)
