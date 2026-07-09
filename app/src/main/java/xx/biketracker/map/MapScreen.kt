package xx.biketracker.map

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import xx.biketracker.R
import xx.biketracker.settings.AppSettings
import xx.biketracker.tracking.TrackingState
import xx.biketracker.ui.isDarkTheme
import java.io.File
import org.osmdroid.util.GeoPoint as OsmGeoPoint

// Map-only tuning; the shared tracking/units constants live in Common.kt.
private const val RIDE_ZOOM = 17.0
private const val ROUTE_STROKE_WIDTH_PX = 10f
private const val ROUTE_BOUNDS_PADDING_PX = 64

/**
 * The Map tab: the current ride's track as a polyline on an OpenStreetMap (osmdroid, no keys).
 * The map centers on the route once when it first appears and then stays put so it can be
 * panned freely; the FAB re-centers. Tiles are inverted in dark theme.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val snapshot by TrackingState.snapshot.collectAsState()
    val themeMode by AppSettings.themeMode.collectAsState()
    val dark = isDarkTheme(themeMode)
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()

    // osmdroid global config: identify the app to the OSM tile servers (required by their
    // usage policy) and keep the tile cache inside the app's own cache directory.
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(RIDE_ZOOM)
        }
    }
    val routeLine = remember {
        Polyline(mapView).apply {
            outlinePaint.strokeWidth = ROUTE_STROKE_WIDTH_PX
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }.also { mapView.overlays.add(it) }
    }

    fun centerOnRoute() {
        val points = routeLine.actualPoints
        when {
            points.isEmpty() -> return
            points.size == 1 -> {
                mapView.controller.setZoom(RIDE_ZOOM)
                mapView.controller.animateTo(points.first())
            }
            else -> mapView.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(points), true, ROUTE_BOUNDS_PADDING_PX,
            )
        }
    }

    // Pause/resume the tile engine with the screen and tear the view down on leave.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    LaunchedEffect(dark) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        mapView.invalidate()
    }

    // Redraw the track on every snapshot; center only the first time a route shows up so the
    // user can pan and zoom without the map snapping back every GPS fix.
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.route, routeColor) {
        routeLine.outlinePaint.color = routeColor
        routeLine.setPoints(snapshot.route.map { OsmGeoPoint(it.lat, it.lon) })
        mapView.invalidate()
        if (!centeredOnce && snapshot.route.isNotEmpty()) {
            centeredOnce = true
            centerOnRoute()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (snapshot.route.isEmpty()) {
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
        } else {
            SmallFloatingActionButton(
                onClick = ::centerOnRoute,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_center_route),
                )
            }
        }
    }
}
