package xx.biketracker.history

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.DatabaseMaintenance
import xx.biketracker.data.Trip
import xx.biketracker.ui.DialogButton
import xx.biketracker.ui.DialogButtonRow

/**
 * Edit a ride's rider-supplied name and comment. Both fields are optional: a blank one is stored as
 * NULL so the History row falls back to the start time. Saving writes only these two columns
 * ([xx.biketracker.data.TripDao.updateTripMeta]); the ride's figures are untouched and the History
 * flow refreshes on its own.
 */
@Composable
fun RideEditDialog(trip: Trip, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val databaseBusyMessage = stringResource(R.string.database_busy)

    var title by remember(trip.id) { mutableStateOf(trip.title.orEmpty()) }
    var note by remember(trip.id) { mutableStateOf(trip.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.ride_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.ride_note_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                )
            }
        },
        confirmButton = {
            DialogButtonRow(
                start = { DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss) },
                end = {
                    DialogButton(
                        text = stringResource(R.string.action_save),
                        onClick = {
                            scope.launch {
                                val saved = DatabaseMaintenance.tryWrite {
                                    AppDatabase.get(context).tripDao().updateTripMeta(
                                        trip.id,
                                        title.trim().ifBlank { null },
                                        note.trim().ifBlank { null },
                                    )
                                }
                                if (saved) onSaved()
                                else Toast.makeText(context, databaseBusyMessage, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                },
            )
        },
    )
}
