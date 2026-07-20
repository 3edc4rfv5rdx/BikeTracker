package xx.biketracker.map

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
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
 * One-time-centering identity of what the map shows. A stored trip is its row id; a live ride
 * is its service start timestamp, which the service sets once per ride and which strictly grows
 * between rides — so every new ride re-arms centering even after an earlier one was centered in
 * the same composition. The prefixes keep a trip id from colliding with a timestamp when
 * switching between a stored trip and the active ride. Deliberately NOT route size or the
 * latest fix: the key must stay constant within one ride or it would fight user panning.
 */
internal fun mapRecenterKey(selectedTripId: Long?, liveRideStartElapsedRealtime: Long): String =
    if (selectedTripId != null) "trip-$selectedTripId" else "live-$liveRideStartElapsedRealtime"

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

    // Stored track of the selected ride, loaded when the selection changes. The GPS speed
    // rides along so the chart panel can plot stored rides exactly like the live one.
    var selectedRoute by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(selected?.id) {
        selectedRoute = selected?.let { trip ->
            AppDatabase.get(context).tripDao().getPoints(trip.id)
                .map { GeoPoint(it.lat, it.lon, it.time, it.speedMps) }
        } ?: emptyList()
    }

    val route = if (selected != null) selectedRoute else snapshot.route

    // Standby is between rides — treat it like idle here so the map neither follows an empty
    // live track nor keeps the screen awake for the length of the standby window.
    val rideActive = snapshot.status == TrackingStatus.RECORDING ||
        snapshot.status == TrackingStatus.PAUSED

    // The map is watched mid-ride too; don't let the screen dim while one is active.
    KeepScreenOnWhile(rideActive)

    // The heading puck belongs to the live ride only, not to a stored ride opened from History.
    val live = selected == null && rideActive
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

    val recenterKey = mapRecenterKey(selected?.id, snapshot.startElapsedRealtime)
    // Scrub selection from the chart, as an index into the route (samples are one per point).
    // Keyed to the shown track's identity, so another ride never inherits a stale marker.
    var scrubIndex by remember(recenterKey) { mutableStateOf<Int?>(null) }
    // Chart visibility survives tab switches; the panel's handle strip toggles it.
    var chartExpanded by rememberSaveable { mutableStateOf(true) }

    // The chart panel takes its slice from the map, never overlays it: a scrubbed marker (or
    // the live puck) must stay visible on the remaining map area.
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            RouteMap(
                route = route,
                modifier = Modifier.fillMaxSize(),
                recenterKey = recenterKey,
                position = puckPosition,
                bearingDegrees = if (live) snapshot.bearingDegrees else null,
                puckState = puckState,
                marker = scrubIndex?.let { route.getOrNull(it) },
                onTrackTap = { scrubIndex = it },
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

        if (route.size >= 2) {
            SpeedChartPanel(
                route = route,
                expanded = chartExpanded,
                onToggle = { chartExpanded = !chartExpanded },
                scrubIndex = scrubIndex,
                onScrub = { scrubIndex = it },
            )
        }
    }
}
