package xx.biketracker.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xx.biketracker.R
import xx.biketracker.avgSpeedMps
import xx.biketracker.data.Trip
import xx.biketracker.formatDate
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatSpeedKmh
import xx.biketracker.ui.DialogButton
import java.util.Calendar
import kotlin.math.roundToInt

/** A ride is too short for its average speed to be meaningful as a record below this distance. */
private const val MIN_AVG_RECORD_METERS = 1_000.0

/**
 * Personal bests over every finished ride. Each per-ride record keeps the [Trip] behind it so the
 * dialog can date it; the best day is a calendar-day distance total, dated by that day.
 */
class Records(
    val longestDistance: Trip?,
    val longestTime: Trip?,
    val fastestAverage: Trip?,
    val topSpeed: Trip?,
    val biggestClimb: Trip?,
    val bestDayMeters: Double,
    val bestDayStart: Long?,
)

fun computeRecords(trips: List<Trip>): Records {
    val cal = Calendar.getInstance()
    val perDayMeters = LinkedHashMap<String, Pair<Double, Long>>() // day key -> (summed meters, a start time that day)
    for (trip in trips) {
        cal.timeInMillis = trip.startTime
        val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        val current = perDayMeters[key]
        perDayMeters[key] =
            if (current == null) trip.distanceMeters to trip.startTime
            else (current.first + trip.distanceMeters) to current.second
    }
    val bestDay = perDayMeters.values.maxByOrNull { it.first }

    return Records(
        longestDistance = trips.maxByOrNull { it.distanceMeters },
        longestTime = trips.maxByOrNull { it.movingTimeMillis },
        fastestAverage = trips.filter { it.distanceMeters >= MIN_AVG_RECORD_METERS }
            .maxByOrNull { avgSpeedMps(it.distanceMeters, it.movingTimeMillis) },
        topSpeed = trips.maxByOrNull { it.maxSpeedMps },
        biggestClimb = trips.filter { it.elevationGainMeters != null }
            .maxByOrNull { it.elevationGainMeters!! },
        bestDayMeters = bestDay?.first ?: 0.0,
        bestDayStart = bestDay?.second,
    )
}

@Composable
fun RecordsDialog(trips: List<Trip>, onDismiss: () -> Unit) {
    val records = computeRecords(trips)
    val km = stringResource(R.string.unit_km)
    val kmh = stringResource(R.string.unit_kmh)
    val meters = stringResource(R.string.unit_m)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_records)) },
        text = {
            if (trips.isEmpty()) {
                Text(stringResource(R.string.history_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecordRow(
                        stringResource(R.string.record_longest_distance),
                        records.longestDistance?.let { "${formatKm(it.distanceMeters)} $km" },
                        records.longestDistance?.startTime,
                    )
                    RecordRow(
                        stringResource(R.string.record_longest_time),
                        records.longestTime?.let { formatDuration(it.movingTimeMillis) },
                        records.longestTime?.startTime,
                    )
                    RecordRow(
                        stringResource(R.string.record_fastest_avg),
                        records.fastestAverage?.let {
                            "${formatSpeedKmh(avgSpeedMps(it.distanceMeters, it.movingTimeMillis))} $kmh"
                        },
                        records.fastestAverage?.startTime,
                    )
                    RecordRow(
                        stringResource(R.string.record_top_speed),
                        records.topSpeed?.let { "${formatSpeedKmh(it.maxSpeedMps)} $kmh" },
                        records.topSpeed?.startTime,
                    )
                    RecordRow(
                        stringResource(R.string.record_biggest_climb),
                        records.biggestClimb?.elevationGainMeters?.let { "${it.roundToInt()} $meters" },
                        records.biggestClimb?.startTime,
                    )
                    RecordRow(
                        stringResource(R.string.record_best_day),
                        records.bestDayStart?.let { "${formatKm(records.bestDayMeters)} $km" },
                        records.bestDayStart,
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_ok), onClick = onDismiss)
        },
    )
}

/** One record line: its name on the left, the value with the ride's date beneath it on the right. */
@Composable
private fun RecordRow(label: String, value: String?, dateMillis: Long?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value ?: "—", fontWeight = FontWeight.Bold)
            if (value != null && dateMillis != null) {
                Text(
                    text = formatDate(dateMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
