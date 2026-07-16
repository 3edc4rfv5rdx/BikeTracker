package xx.biketracker.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
 * dialog can date it and open its statistics; the best day is a calendar-day distance total, dated
 * by that day.
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

/**
 * Personal-bests dialog. Each row taps through to the ride behind it via [onOpenRide]; the best
 * day, which is not a single ride, opens History on that day via [onOpenDay].
 */
@Composable
fun RecordsDialog(
    trips: List<Trip>,
    onOpenRide: (Trip) -> Unit,
    onOpenDay: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RecordRow(
                        stringResource(R.string.record_longest_distance),
                        records.longestDistance?.let { "${formatKm(it.distanceMeters)} $km" },
                        records.longestDistance?.startTime,
                        records.longestDistance?.let { t -> { onOpenRide(t) } },
                    )
                    RecordRow(
                        stringResource(R.string.record_longest_time),
                        records.longestTime?.let { formatDuration(it.movingTimeMillis) },
                        records.longestTime?.startTime,
                        records.longestTime?.let { t -> { onOpenRide(t) } },
                    )
                    RecordRow(
                        stringResource(R.string.record_fastest_avg),
                        records.fastestAverage?.let {
                            "${formatSpeedKmh(avgSpeedMps(it.distanceMeters, it.movingTimeMillis))} $kmh"
                        },
                        records.fastestAverage?.startTime,
                        records.fastestAverage?.let { t -> { onOpenRide(t) } },
                    )
                    RecordRow(
                        stringResource(R.string.record_top_speed),
                        records.topSpeed?.let { "${formatSpeedKmh(it.maxSpeedMps)} $kmh" },
                        records.topSpeed?.startTime,
                        records.topSpeed?.let { t -> { onOpenRide(t) } },
                    )
                    RecordRow(
                        stringResource(R.string.record_biggest_climb),
                        records.biggestClimb?.elevationGainMeters?.let { "${it.roundToInt()} $meters" },
                        records.biggestClimb?.startTime,
                        records.biggestClimb?.let { t -> { onOpenRide(t) } },
                    )
                    RecordRow(
                        stringResource(R.string.record_best_day),
                        records.bestDayStart?.let { "${formatKm(records.bestDayMeters)} $km" },
                        records.bestDayStart,
                        records.bestDayStart?.let { d -> { onOpenDay(d) } },
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_ok), onClick = onDismiss)
        },
    )
}

/**
 * One record line on a single row: name on the left, then its value, date, and a chevron. Tapping
 * it runs [onClick]; a record with no ride to open ([onClick] null, e.g. no climb data yet) shows
 * a dash, no date and no chevron, and a spacer keeps the values aligned with the clickable rows.
 */
@Composable
private fun RecordRow(label: String, value: String?, dateMillis: Long?, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value ?: "—", fontWeight = FontWeight.Bold)
        if (value != null && dateMillis != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDate(dateMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(24.dp)) // keep values lined up when a row has no chevron
        }
    }
}
