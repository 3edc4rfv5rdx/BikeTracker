package xx.biketracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed ride.
 *
 * Stored in canonical SI units (meters, milliseconds, m/s). Conversion to the
 * displayed km / km/h happens at the UI layer, so the database never depends on
 * a unit preference. Average speed is intentionally NOT stored: it is fully
 * derived from [distanceMeters] / [movingTimeMillis] and computed in one shared
 * place at display time. [maxSpeedMps] is a genuine peak sample, so it is kept.
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,          // epoch millis, first recorded point
    val endTime: Long,            // epoch millis, last recorded point
    val distanceMeters: Double,
    val movingTimeMillis: Long,   // elapsed time excluding paused stretches
    val maxSpeedMps: Double,
)
