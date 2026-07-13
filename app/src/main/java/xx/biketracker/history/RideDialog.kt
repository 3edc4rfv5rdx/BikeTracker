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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import android.widget.Toast
import xx.biketracker.R
import xx.biketracker.avgSpeedMps
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.DatabaseMaintenance
import xx.biketracker.data.Trip
import xx.biketracker.formatClock
import xx.biketracker.formatDate
import xx.biketracker.formatDuration
import xx.biketracker.formatKm
import xx.biketracker.formatPace
import xx.biketracker.formatSpeedKmh
import xx.biketracker.map.MapSelection
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.DialogButtonRow
import kotlin.math.roundToInt

/**
 * Ride details shown as a dialog over the History screen. All figures come from the [Trip] row
 * (point reductions are stored at save), so nothing is loaded here. Back simply dismisses.
 */
@Composable
fun RideDialog(trip: Trip, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val databaseBusyMessage = stringResource(R.string.database_busy)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(formatDate(trip.startTime), fontWeight = FontWeight.Bold)
                Text(
                    text = "${formatClock(trip.startTime)} – ${formatClock(trip.endTime)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow(
                    Stat(stringResource(R.string.stat_distance), formatKm(trip.distanceMeters), stringResource(R.string.unit_km)),
                    Stat(stringResource(R.string.stat_time), formatDuration(trip.movingTimeMillis)),
                )
                StatRow(
                    Stat(
                        stringResource(R.string.stat_avg_speed),
                        formatSpeedKmh(avgSpeedMps(trip.distanceMeters, trip.movingTimeMillis)),
                        stringResource(R.string.unit_kmh),
                    ),
                    Stat(stringResource(R.string.stat_max_speed), formatSpeedKmh(trip.maxSpeedMps), stringResource(R.string.unit_kmh)),
                )
                StatRow(
                    Stat(stringResource(R.string.stat_pace), formatPace(trip.distanceMeters, trip.movingTimeMillis), stringResource(R.string.unit_pace)),
                    Stat(
                        stringResource(R.string.stat_elevation_gain),
                        trip.elevationGainMeters?.roundToInt()?.toString() ?: "—",
                        stringResource(R.string.unit_m),
                    ),
                )
            }
        },
        confirmButton = {
            DialogButtonRow(
                start = {
                    DialogButton(
                        text = stringResource(R.string.action_delete),
                        onClick = { confirmDelete = true },
                        destructive = true,
                    )
                },
                end = { DialogButton(stringResource(R.string.action_ok), onClick = onDismiss) },
            )
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.action_delete),
                    destructive = true,
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            val deleted = DatabaseMaintenance.tryWrite {
                                AppDatabase.get(context).tripDao().deleteTrip(trip)
                            }
                            if (deleted) {
                                MapSelection.clearIf(trip.id) // the Map tab must not keep a deleted ride
                                onDeleted()
                            } else {
                                Toast.makeText(context, databaseBusyMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            },
            dismissButton = {
                DialogButton(stringResource(R.string.action_cancel), onClick = { confirmDelete = false })
            },
        )
    }
}

private data class Stat(val label: String, val value: String, val unit: String? = null)

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

// Label, big value, and the unit as a small caption below — keeps the label short so it doesn't
// wrap into "Макс. / скорость, / км/ч" in the narrow dialog cell.
@Composable
private fun StatCell(stat: Stat, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stat.value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (stat.unit != null) {
                Text(
                    text = stat.unit,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
