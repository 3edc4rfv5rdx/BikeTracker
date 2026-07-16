package xx.biketracker.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import xx.biketracker.ui.Stat
import xx.biketracker.ui.StatRow
import kotlin.math.roundToInt

/**
 * Ride details shown as a dialog over the History screen. Every figure comes from the [Trip] row
 * (point reductions are stored at save), so nothing is loaded here. Back simply dismisses. The
 * per-ride actions (statistics, export, map) live on the History row's menu, not in this dialog.
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
