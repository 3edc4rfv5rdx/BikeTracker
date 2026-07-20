package xx.biketracker.map

import android.annotation.SuppressLint
import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.floor

/** Extra detail levels fetched below the viewed zoom; bounds the tile count of a download. */
private const val OFFLINE_EXTRA_ZOOM = 4.0
private const val OFFLINE_MAX_ZOOM = 16.0

/**
 * OfflineManager requires MapLibre to be initialized, which otherwise happens only when the
 * first MapView is built. The offline dialog can be opened straight after launch without ever
 * visiting the Map tab — going to OfflineManager directly then crashed the app.
 */
private fun offlineManager(context: Context): OfflineManager {
    MapLibre.getInstance(context)
    return OfflineManager.getInstance(context)
}

/**
 * Last camera rest position of the map, published by [RouteMap] on every camera stop. Settings
 * uses it as the area for an offline download, so "what you last looked at is what you get".
 */
object MapViewport {
    @Volatile
    var bounds: LatLngBounds? = null
        private set

    @Volatile
    var zoom: Double = 0.0
        private set

    fun update(bounds: LatLngBounds, zoom: Double) {
        this.bounds = bounds
        this.zoom = zoom
    }
}

/** Stored-region tally: fully downloaded areas and retained partial ones. */
data class RegionCounts(val complete: Int, val partial: Int)

/**
 * Identity of a download area, stored as the region's metadata so a later request for the same
 * area resumes its incomplete region instead of stacking up duplicates. Doubles are printed with
 * fixed precision to keep the key byte-stable across runs.
 */
internal fun offlineRegionKey(
    styleUrl: String,
    latSouth: Double,
    lonWest: Double,
    latNorth: Double,
    lonEast: Double,
    minZoom: Double,
    maxZoom: Double,
): String = String.format(
    Locale.US,
    "%s|%.6f|%.6f|%.6f|%.6f|%.1f|%.1f",
    styleUrl, latSouth, lonWest, latNorth, lonEast, minZoom, maxZoom,
)

/**
 * Index of the stored region to resume for the wanted area, or null to create a new one. A null
 * key is a legacy region created before metadata identity — it never matches. Re-activating an
 * already complete match is harmless: MapLibre verifies it and reports completion immediately.
 */
internal fun findResumableRegion(keys: List<String?>, wantedKey: String): Int? {
    val index = keys.indexOf(wantedKey)
    return if (index >= 0) index else null
}

/** Percentage for the progress bar; the required count is 0 until the manifest is known. */
internal fun downloadPercent(completed: Long, required: Long): Int =
    if (required > 0) (100L * completed / required).toInt().coerceIn(0, 100) else 0

/**
 * Process-wide owner of offline-map downloads. The dialog is disposable; this object holds the
 * operation, so closing and reopening the dialog reconnects to live progress, a result that
 * arrived while no dialog was open is still reported once, and two dialogs can never start
 * concurrent downloads of the same area. All entry points must be called on the main thread —
 * MapLibre's OfflineManager callbacks arrive there too.
 *
 * Failure is reserved for terminal conditions (the tile-count limit). Recoverable resource
 * errors are left to MapLibre, which retries them with backoff and on network restoration; a
 * download that cannot proceed stays visibly in progress and can be cancelled, keeping the
 * partial region for a later resume.
 */
object OfflineMapManager {
    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data object Succeeded : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Stored-region counts; null until the first [refresh] resolves. */
    private val _regions = MutableStateFlow<RegionCounts?>(null)
    val regions: StateFlow<RegionCounts?> = _regions.asStateFlow()

    // Not an Activity leak: MapLibre regions hold the application context, and the field is cleared
    // as soon as a download finishes or is cancelled.
    @SuppressLint("StaticFieldLeak")
    private var activeRegion: OfflineRegion? = null

    /** The UI acknowledges a terminal result after showing it, re-arming the Download action. */
    fun acknowledgeResult() {
        if (_state.value is State.Succeeded || _state.value is State.Failed) {
            _state.value = State.Idle
        }
    }

    /** Re-count stored regions, separating complete areas from retained partial ones. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        offlineManager(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    if (regions.isEmpty()) {
                        _regions.value = RegionCounts(0, 0)
                        return
                    }
                    var complete = 0
                    var partial = 0
                    var pending = regions.size
                    regions.forEach { region ->
                        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                if (status?.isComplete == true) complete++ else partial++
                                if (--pending == 0) _regions.value = RegionCounts(complete, partial)
                            }

                            override fun onError(error: String?) {
                                partial++
                                if (--pending == 0) _regions.value = RegionCounts(complete, partial)
                            }
                        })
                    }
                }

                override fun onError(error: String) {
                    _regions.value = RegionCounts(0, 0)
                }
            },
        )
    }

    /**
     * Download the last viewed area ([MapViewport]): style, glyphs and vector tiles from the
     * viewed zoom down to [OFFLINE_EXTRA_ZOOM] extra levels (capped at [OFFLINE_MAX_ZOOM]).
     * Resumes a matching incomplete region when one exists. Returns false only when the map
     * was never opened, so there is no area to download; a request while one is already
     * running is a no-op.
     */
    fun start(context: Context): Boolean {
        val bounds = MapViewport.bounds ?: return false
        if (_state.value is State.Downloading) return true
        val appContext = context.applicationContext
        val minZoom = floor(MapViewport.zoom).coerceIn(0.0, OFFLINE_MAX_ZOOM)
        val maxZoom = (minZoom + OFFLINE_EXTRA_ZOOM).coerceAtMost(OFFLINE_MAX_ZOOM)
        val definition = OfflineTilePyramidRegionDefinition(
            MAP_STYLE_URL,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density,
        )
        val key = offlineRegionKey(
            MAP_STYLE_URL,
            bounds.latitudeSouth, bounds.longitudeWest,
            bounds.latitudeNorth, bounds.longitudeEast,
            minZoom, maxZoom,
        )
        _state.value = State.Downloading(0)

        offlineManager(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    val keys = regions.map { region ->
                        val meta = region.metadata
                        if (meta.isEmpty()) null else String(meta, Charsets.UTF_8)
                    }
                    val resumable = findResumableRegion(keys, key)
                    if (resumable != null) {
                        activate(appContext, regions[resumable])
                    } else {
                        offlineManager(appContext).createOfflineRegion(
                            definition,
                            key.toByteArray(Charsets.UTF_8),
                            object : OfflineManager.CreateOfflineRegionCallback {
                                override fun onCreate(offlineRegion: OfflineRegion) =
                                    activate(appContext, offlineRegion)

                                override fun onError(error: String) = finish(appContext, success = false)
                            },
                        )
                    }
                }

                override fun onError(error: String) = finish(appContext, success = false)
            },
        )
        return true
    }

    private fun activate(appContext: Context, region: OfflineRegion) {
        activeRegion = region
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (activeRegion !== region) return
                if (status.isComplete) {
                    finish(appContext, success = true)
                } else {
                    _state.value = State.Downloading(
                        downloadPercent(status.completedResourceCount, status.requiredResourceCount),
                    )
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Resource errors are recoverable; MapLibre keeps retrying them with backoff
                // and when the network returns. Cancel is the explicit way out.
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (activeRegion !== region) return
                finish(appContext, success = false)
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun finish(appContext: Context, success: Boolean) {
        activeRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        activeRegion = null
        _state.value = if (success) State.Succeeded else State.Failed
        refresh(appContext)
    }

    /** Stop the running download, keeping the partial region so a retry resumes it. */
    fun cancel(context: Context) {
        val region = activeRegion ?: return
        activeRegion = null // stale observer callbacks check identity and bail out
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        _state.value = State.Idle
        refresh(context)
    }

    /** Delete every stored area; an active download is cancelled first so deletion cannot race it. */
    fun deleteAll(context: Context, onDone: () -> Unit) {
        val appContext = context.applicationContext
        cancel(appContext)
        offlineManager(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    if (regions.isEmpty()) {
                        _regions.value = RegionCounts(0, 0)
                        onDone()
                        return
                    }
                    var remaining = regions.size
                    fun oneDone() {
                        if (--remaining == 0) {
                            refresh(appContext)
                            onDone()
                        }
                    }
                    regions.forEach { region ->
                        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = oneDone()
                            override fun onError(error: String) = oneDone()
                        })
                    }
                }

                override fun onError(error: String) = onDone()
            },
        )
    }
}
