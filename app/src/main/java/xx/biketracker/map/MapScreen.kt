package xx.biketracker.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.biketracker.GeoPoint
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.formatClock
import xx.biketracker.formatDate
import xx.biketracker.tracking.TrackingState
import xx.biketracker.tracking.TrackingStatus
import xx.biketracker.ui.KeepScreenOnWhile

/**
 * The Map tab. Shows the current ride's live track, or — when a ride was sent here from the
 * History tree — that ride's stored track with a chip naming it; closing the chip returns to
 * the live view.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val snapshot by TrackingState.snapshot.collectAsState()
    val selected by MapSelection.trip.collectAsState()

    // Stored track of the selected ride, loaded when the selection changes.
    var selectedRoute by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(selected?.id) {
        selectedRoute = selected?.let { trip ->
            AppDatabase.get(context).tripDao().getPoints(trip.id).map { GeoPoint(it.lat, it.lon) }
        } ?: emptyList()
    }

    val route = if (selected != null) selectedRoute else snapshot.route

    // The map is watched mid-ride too; don't let the screen dim while one is active.
    KeepScreenOnWhile(snapshot.status != TrackingStatus.IDLE)

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            modifier = Modifier.fillMaxSize(),
            recenterKey = selected?.id,
        )

        selected?.let { trip ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatDate(trip.startTime)} · ${formatClock(trip.startTime)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    IconButton(onClick = { MapSelection.clear() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.map_close_ride),
                        )
                    }
                }
            }
        }

        if (selected == null && snapshot.route.isEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_no_ride),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
