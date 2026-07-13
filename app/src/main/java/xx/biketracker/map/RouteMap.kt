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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import xx.biketracker.GeoPoint
import xx.biketracker.R
import xx.biketracker.smoothRoute
import xx.biketracker.ui.AccentOrange

/** Keyless vector style (OpenFreeMap), used in both themes — the dark style was unreadable. */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

// Map-only tuning; the shared tracking/units constants live in Common.kt.
private const val RIDE_ZOOM = 16.0
private const val MAX_ZOOM = 19.0
private const val ROUTE_SOURCE_ID = "ride-route"
private const val ROUTE_LAYER_ID = "ride-route-line"
private const val ROUTE_LINE_WIDTH = 4f
// Fixed accent color (not theme-derived): high contrast against both map styles.
private val ROUTE_LINE_COLOR = AccentOrange.toArgb()
private const val ROUTE_BOUNDS_PADDING_PX = 64

// Live-position puck: an arrow at the current fix, rotated to the heading of travel.
private const val PUCK_SOURCE_ID = "ride-puck"
private const val PUCK_LAYER_ID = "ride-puck-symbol"
private const val PUCK_BEARING_KEY = "bearing"

/** Tint of the live-position arrow: it flags a paused ride and GPS trouble by color. */
enum class PuckState(internal val imageId: String, internal val drawableRes: Int) {
    NORMAL("ride-puck-arrow", R.drawable.ic_map_puck),
    PAUSED("ride-puck-arrow-paused", R.drawable.ic_map_puck_paused),
    GPS_TROUBLE("ride-puck-arrow-trouble", R.drawable.ic_map_puck_trouble),
}

/**
 * Reusable MapLibre vector map with one route polyline — the Map tab shows the live ride or a
 * ride selected in History. Labels render on the device, so their size is constant across zooms.
 * Centers on the route once when it first appears (again whenever [recenterKey] changes) and
 * then stays put so it can be panned freely; the FAB re-centers. Every camera stop is recorded
 * in [MapViewport] as the download area for the offline map (Settings).
 */
@Composable
fun RouteMap(
    route: List<GeoPoint>,
    modifier: Modifier = Modifier,
    recenterKey: Any? = null,
    position: GeoPoint? = null,
    bearingDegrees: Float? = null,
    puckState: PuckState = PuckState.NORMAL,
) {
    val context = LocalContext.current

    // Must run once before the first MapView is constructed.
    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    // True while the user is dragging the map; the arrow-follow logic must not fight the gesture.
    var gestureInProgress by remember { mutableStateOf(false) }
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
            // Hard ceiling for hand zooming: past this the overscale factor over maxzoom-14
            // vector tiles starts multiplying symbol-layout anchors (see centerOnRoute).
            map.setMaxZoomPreference(MAX_ZOOM)
            // Remember the viewed area; Settings offers it as the offline download region.
            map.addOnCameraIdleListener {
                gestureInProgress = false
                MapViewport.update(map.projection.visibleRegion.latLngBounds, map.cameraPosition.zoom)
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    gestureInProgress = true
                }
            }
            mapInstance = map
        }
    }

    // Load the style once the map is ready.
    LaunchedEffect(mapInstance) {
        val map = mapInstance ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(ROUTE_LINE_COLOR),
                    PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            // Puck on top of the track: an arrow image rotated per-feature to the heading, with
            // one registered image per tint. The active image is a plain layer property switched
            // on state changes — a data-driven iconImage expression made MapLibre re-layout the
            // symbol every animation frame and leak native memory at ~500 MB/s until the OS
            // killed the app.
            PuckState.entries.forEach { state ->
                puckBitmap(context, state.drawableRes)?.let { style.addImage(state.imageId, it) }
            }
            style.addSource(GeoJsonSource(PUCK_SOURCE_ID))
            style.addLayer(
                SymbolLayer(PUCK_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                    PropertyFactory.iconImage(PuckState.NORMAL.imageId),
                    PropertyFactory.iconRotate(Expression.get(PUCK_BEARING_KEY)),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
            styleEpoch++
        }
    }

    // Never zoom in past RIDE_ZOOM when framing a route: a short (young) ride makes
    // newLatLngBounds pick z20+, where vector tiles (source maxzoom 14) get laid out with a
    // 2^(zoom-14) overscale factor that divides the symbol spacing — street labels of a dense
    // tile then explode into millions of anchors, allocating gigabytes of native heap within
    // seconds (LOW_MEMORY kill on 2 GB devices, minutes of grey unrendered map elsewhere).
    fun centerOnRoute() {
        val map = mapInstance ?: return
        when {
            route.isEmpty() -> return
            route.size == 1 -> map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(route.first().lat, route.first().lon), RIDE_ZOOM)
            )
            else -> {
                val bounds = LatLngBounds.Builder()
                    .apply { route.forEach { include(LatLng(it.lat, it.lon)) } }
                    .build()
                val pad = ROUTE_BOUNDS_PADDING_PX
                val fitted = map.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad))
                if (fitted == null || fitted.zoom > RIDE_ZOOM) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, RIDE_ZOOM))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad))
                }
            }
        }
    }

    // Redraw the track on every route change; center only the first time a route shows up so
    // the user can pan and zoom without the map snapping back every update. A recenterKey
    // change (another ride selected) re-arms the one-time centering.
    var centeredOnce by remember(recenterKey) { mutableStateOf(false) }
    LaunchedEffect(route, styleEpoch) {
        val style = mapInstance?.style ?: return@LaunchedEffect
        // Smoothing re-runs over the whole track on every fix; off the main thread so a
        // multi-hour ride (thousands of points) can't jank the map.
        val line = withContext(Dispatchers.Default) {
            LineString.fromLngLats(smoothRoute(route).map { Point.fromLngLat(it.lon, it.lat) })
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(line)
        if (!centeredOnce && route.isNotEmpty()) {
            centeredOnce = true
            centerOnRoute()
        }
    }

    // Recolor the arrow on state changes by swapping the layer's icon image.
    LaunchedEffect(puckState, styleEpoch) {
        val layer = mapInstance?.style?.getLayer(PUCK_LAYER_ID) as? SymbolLayer ?: return@LaunchedEffect
        layer.setProperties(PropertyFactory.iconImage(puckState.imageId))
    }

    // Move the puck to the current fix (empty when there is no live position).
    LaunchedEffect(position, bearingDegrees, styleEpoch) {
        val map = mapInstance ?: return@LaunchedEffect
        val source = map.style?.getSourceAs<GeoJsonSource>(PUCK_SOURCE_ID) ?: return@LaunchedEffect
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(listOf<Feature>()))
        } else {
            val feature = Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat))
            feature.addNumberProperty(PUCK_BEARING_KEY, bearingDegrees ?: 0f)
            source.setGeoJson(feature)
            // Keep the arrow on screen: a fix outside the viewed area shifts the map to it at
            // the current zoom. A hand-panned map is left alone while the arrow stays visible.
            val latLng = LatLng(position.lat, position.lon)
            if (!gestureInProgress && !map.projection.visibleRegion.latLngBounds.contains(latLng)) {
                map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            }
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

/** Rasterize a puck vector drawable into a bitmap the MapLibre style can register as an image. */
private fun puckBitmap(context: android.content.Context, drawableRes: Int): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth,
        drawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
