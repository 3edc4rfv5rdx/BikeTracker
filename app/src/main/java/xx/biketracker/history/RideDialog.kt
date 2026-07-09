package xx.biketracker.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xx.biketracker.R
import xx.biketracker.avgSpeedMps
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.Trip
import xx.biketracker.formatClock
import xx.biketracker.formatDate
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatPace
import xx.biketracker.formatSpeedKmh
import kotlin.math.roundToInt

/**
 * Ride details shown as a dialog over the History screen. All figures come from the [Trip] row
 * (point reductions are stored at save), so nothing is loaded here. Back simply dismisses.
 */
@Composable
fun RideDialog(trip: Trip, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).tripDao() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(formatDate(trip.startTime), fontWeight = FontWeight.Bold)
                Text(
                    text = "${formatClock(trip.startTime)} – ${formatClock(trip.endTime)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow(
                    Stat(withUnit(R.string.stat_distance, R.string.unit_km), formatKm(trip.distanceMeters)),
                    Stat(stringResource(R.string.stat_time), formatDuration(trip.movingTimeMillis)),
                )
                StatRow(
                    Stat(
                        withUnit(R.string.stat_avg_speed, R.string.unit_kmh),
                        formatSpeedKmh(avgSpeedMps(trip.distanceMeters, trip.movingTimeMillis)),
                    ),
                    Stat(withUnit(R.string.stat_max_speed, R.string.unit_kmh), formatSpeedKmh(trip.maxSpeedMps)),
                )
                StatRow(
                    Stat(withUnit(R.string.stat_pace, R.string.unit_pace), formatPace(trip.distanceMeters, trip.movingTimeMillis)),
                    Stat(
                        withUnit(R.string.stat_avg_speed_gps, R.string.unit_kmh),
                        trip.avgGpsSpeedMps?.let { formatSpeedKmh(it) } ?: "—",
                    ),
                )
                StatRow(
                    Stat(
                        withUnit(R.string.stat_elevation_gain, R.string.unit_m),
                        trip.elevationGainMeters?.roundToInt()?.toString() ?: "—",
                    ),
                    null,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        dao.deleteTrip(trip)
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private data class Stat(val label: String, val value: String)

/** "Distance, km" — comma joined here so string resources stay punctuation-free. */
@Composable
private fun withUnit(labelRes: Int, unitRes: Int): String =
    "${stringResource(labelRes)}, ${stringResource(unitRes)}"

@Composable
private fun StatRow(left: Stat, right: Stat?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCell(left, Modifier.weight(1f))
        if (right != null) {
            StatCell(right, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stat.value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
