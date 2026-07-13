package xx.biketracker.map

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xx.biketracker.GeoPoint
import xx.biketracker.R
import xx.biketracker.data.AppDatabase
import xx.biketracker.tracking.TrackingState
import xx.biketracker.tracking.TrackingStatus
import xx.biketracker.tracking.hasGpsTrouble
import xx.biketracker.ui.KeepScreenOnWhile

/**
 * The Map tab. Shows the current ride's live track, or — when a ride was sent here from the
 * History tree — that ride's stored track; the ride is named in the top bar, where closing it
 * returns to the live view.
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
            AppDatabase.get(context).tripDao().getPoints(trip.id).map { GeoPoint(it.lat, it.lon, it.time) }
        } ?: emptyList()
    }

    val route = if (selected != null) selectedRoute else snapshot.route

    // The map is watched mid-ride too; don't let the screen dim while one is active.
    KeepScreenOnWhile(snapshot.status != TrackingStatus.IDLE)

    // The heading puck belongs to the live ride only, not to a stored ride opened from History.
    val live = selected == null && snapshot.status != TrackingStatus.IDLE
    val puckPosition = if (live) snapshot.route.lastOrNull() else null

    // GPS staleness must surface even when no fixes arrive to recompose us, so tick locally.
    var nowElapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(live) {
        while (live) {
            nowElapsedRealtime = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val puckState = when {
        !live -> PuckState.NORMAL
        snapshot.hasGpsTrouble(nowElapsedRealtime) -> PuckState.GPS_TROUBLE
        snapshot.status == TrackingStatus.PAUSED -> PuckState.PAUSED
        else -> PuckState.NORMAL
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMap(
            route = route,
            modifier = Modifier.fillMaxSize(),
            recenterKey = selected?.id,
            position = puckPosition,
            bearingDegrees = if (live) snapshot.bearingDegrees else null,
            puckState = puckState,
        )

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
