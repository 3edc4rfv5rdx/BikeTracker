package xx.biketracker.history

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.DatabaseMaintenance
import xx.biketracker.data.Trip
import xx.biketracker.map.MapSelection

/**
 * Delete [trip] off the main thread, guarding against a busy database. On success the Map tab
 * selection is dropped if it pointed at this ride and [onDeleted] runs; a busy database toasts
 * [busyMessage] and leaves the ride in place. Shared by the summary dialog and the History menu so
 * both delete a ride the same way. Strings are passed in already resolved, so this stays Compose-free.
 */
fun launchTripDelete(
    context: Context,
    scope: CoroutineScope,
    trip: Trip,
    busyMessage: String,
    onDeleted: () -> Unit,
) {
    scope.launch {
        val deleted = DatabaseMaintenance.tryWrite {
            AppDatabase.get(context).tripDao().deleteTrip(trip)
        }
        if (deleted) {
            MapSelection.clearIf(trip.id) // the Map tab must not keep a deleted ride
            onDeleted()
        } else {
            Toast.makeText(context, busyMessage, Toast.LENGTH_LONG).show()
        }
    }
}
