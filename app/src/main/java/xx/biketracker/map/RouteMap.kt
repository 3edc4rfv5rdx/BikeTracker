package xx.biketracker.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import xx.biketracker.GeoPoint
import xx.biketracker.R
import xx.biketracker.settings.AppSettings
import xx.biketracker.ui.isDarkTheme

/** Keyless vector styles (OpenFreeMap). Offline downloads reference the light one — the vector
 *  tiles (the bulk of a download) are shared between both styles. */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val MAP_STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

// Map-only tuning; the shared tracking/units constants live in Common.kt.
private const val RIDE_ZOOM = 16.0
private const val ROUTE_SOURCE_ID = "ride-route"
private const val ROUTE_LAYER_ID = "ride-route-line"
private const val ROUTE_LINE_WIDTH = 4f
private const val ROUTE_BOUNDS_PADDING_PX = 64

/**
 * Reusable MapLibre vector map with one route polyline — the Map tab shows the live ride or a
 * ride selected in History. Labels render on the device, so their size is constant across zooms.
 * Centers on the route once when it first appears (again whenever [recenterKey] changes) and
 * then stays put so it can be panned freely; the FAB re-centers. Every camera stop is recorded
 * in [MapViewport] as the download area for the offline map (Settings).
 */
@Composable
fun RouteMap(route: List<GeoPoint>, modifier: Modifier = Modifier, recenterKey: Any? = null) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val themeMode by AppSettings.themeMode.collectAsState()
    val dark = isDarkTheme(themeMode)

    // Must run once before the first MapView is constructed.
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped after every style (re)load — a style swap drops all layers, so the route effect
    // below must re-add its data once the new style is ready.
    var styleEpoch by remember { mutableStateOf(0) }

    // Forward the lifecycle to the MapView (adding the observer replays CREATE..RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            // Remember the viewed area; Settings offers it as the offline download region.
            map.addOnCameraIdleListener {
                MapViewport.update(map.projection.visibleRegion.latLngBounds, map.cameraPosition.zoom)
            }
            mapInstance = map
        }
    }

    // (Re)load the style matching the theme; the camera position survives the swap.
    LaunchedEffect(mapInstance, dark) {
        val map = mapInstance ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(if (dark) MAP_STYLE_URL_DARK else MAP_STYLE_URL)) { style ->
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(routeColor),
                    PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            styleEpoch++
        }
    }

    fun centerOnRoute() {
        val map = mapInstance ?: return
        when {
            route.isEmpty() -> return
            route.size == 1 -> map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(route.first().lat, route.first().lon), RIDE_ZOOM)
            )
            else -> {
                val bounds = LatLngBounds.Builder()
                    .apply { route.forEach { include(LatLng(it.lat, it.lon)) } }
                    .build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, ROUTE_BOUNDS_PADDING_PX))
            }
        }
    }

    // Redraw the track on every route change; center only the first time a route shows up so
    // the user can pan and zoom without the map snapping back every update. A recenterKey
    // change (another ride selected) re-arms the one-time centering.
    var centeredOnce by remember(recenterKey) { mutableStateOf(false) }
    LaunchedEffect(route, styleEpoch, routeColor) {
        val style = mapInstance?.style ?: return@LaunchedEffect
        style.getLayerAs<LineLayer>(ROUTE_LAYER_ID)?.setProperties(PropertyFactory.lineColor(routeColor))
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(
            LineString.fromLngLats(route.map { Point.fromLngLat(it.lon, it.lat) })
        )
        if (!centeredOnce && route.isNotEmpty()) {
            centeredOnce = true
            centerOnRoute()
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Zoom buttons: pinch works on device, but the emulator (and gloves) want plain taps.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomBy(1.0)) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.map_zoom_in))
            }
            SmallFloatingActionButton(onClick = { mapInstance?.animateCamera(CameraUpdateFactory.zoomBy(-1.0)) }) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.map_zoom_out))
            }
            if (route.isNotEmpty()) {
                SmallFloatingActionButton(onClick = ::centerOnRoute) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.map_center_route),
                    )
                }
            }
        }
    }
}
