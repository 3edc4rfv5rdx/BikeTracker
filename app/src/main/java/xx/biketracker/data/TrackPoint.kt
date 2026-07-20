package xx.biketracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single GPS sample belonging to a trip. The route polyline uses insertion order,
 * independent of wall-clock corrections. Deleting a trip cascades to its points.
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
    val time: Long,               // epoch millis
    val speedMps: Float,          // instantaneous speed reported by the GPS fix
    val altitudeMeters: Double? = null, // GPS altitude if the fix had one, else null (added in schema v2)
    // True on the first fix after a pause or GPS outage — the recording-segment boundary, so a
    // short pause splits regardless of the wall-clock gap (added in schema v6). Old rows are 0
    // and fall back to the wall-time gap heuristic; see isSegmentBoundary.
    val segmentStart: Boolean = false,
)
