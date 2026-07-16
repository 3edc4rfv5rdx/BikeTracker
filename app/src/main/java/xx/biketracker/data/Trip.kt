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
    // Reductions over the track points, computed once at save (like maxSpeedMps) so the detail
    // and any future period summaries never reload points. Null when the source had no such data
    // (e.g. imported/older rides): avg over no GPS speeds, or a route with no altitude fixes.
    val avgGpsSpeedMps: Double? = null,
    val elevationGainMeters: Double? = null,
    // False while the ride is still being recorded: points and running aggregates are flushed
    // into this draft row incrementally, so a process death loses at most the last batch.
    // History/totals queries only show finished trips; finalizeAbandonedTrips() rescues drafts.
    val finished: Boolean = true,
    // Optional rider-supplied name and free-form comment. Null (or blank) means unset: the UI then
    // falls back to the start time as the ride's label. Never used in any computation.
    val title: String? = null,
    val note: String? = null,
)
