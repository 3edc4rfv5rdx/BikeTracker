package xx.biketracker.history

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.GPX_MIME
import xx.biketracker.data.Trip
import xx.biketracker.data.exportRideGpx

/**
 * Export [trip] to GPX and hand it to the system share sheet, off the main thread. Shared by every
 * entry point that offers "Export GPX"; [failedMessage] is toasted on any failure (including an
 * empty ride). Strings are passed in already resolved, so this stays free of Compose.
 */
fun launchGpxShare(
    context: Context,
    scope: CoroutineScope,
    trip: Trip,
    shareTitle: String,
    failedMessage: String,
) {
    scope.launch {
        try {
            val points = AppDatabase.get(context).tripDao().getPoints(trip.id)
            check(points.isNotEmpty()) { "Ride has no points" }
            val uri = exportRideGpx(context, trip, points)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = GPX_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, shareTitle))
        } catch (_: Exception) {
            Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
        }
    }
}
