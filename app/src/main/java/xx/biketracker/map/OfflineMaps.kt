package xx.biketracker.map

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

/** The area to download: viewport bounds plus the zoom range, as plain values. */
internal data class OfflineAreaRequest(
    val latSouth: Double,
    val lonWest: Double,
    val latNorth: Double,
    val lonEast: Double,
    val minZoom: Double,
    val maxZoom: Double,
) {
    val key: String = offlineRegionKey(
        MAP_STYLE_URL, latSouth, lonWest, latNorth, lonEast, minZoom, maxZoom,
    )
}

/** One stored offline area — as much of MapLibre's `OfflineRegion` as the manager drives. */
internal interface OfflineArea {
    /** Identity stored as region metadata; null for a legacy region created before identity keys. */
    val key: String?

    fun setDownloading(downloading: Boolean)

    /** Attach progress and tile-limit notifications; resource errors are MapLibre's to retry. */
    fun observe(
        onProgress: (completed: Long, required: Long, complete: Boolean) -> Unit,
        onTileLimit: () -> Unit,
    )

    fun readComplete(onResult: (complete: Boolean) -> Unit)

    fun delete(onDone: () -> Unit)
}

/**
 * The slice of MapLibre's offline API the download state machine uses. It is an interface so the
 * lifecycle — lookup, creation, activation, cancellation, deletion — can be driven by a fake with
 * callbacks that arrive when a test says so, instead of by the real tile downloader.
 */
internal interface OfflineBackend {
    fun list(onAreas: (List<OfflineArea>) -> Unit, onFailure: () -> Unit)

    fun create(
        request: OfflineAreaRequest,
        onArea: (OfflineArea) -> Unit,
        onFailure: () -> Unit,
    )
}

private class MapLibreArea(private val region: OfflineRegion) : OfflineArea {
    override val key: String?
        get() {
            val meta = region.metadata
            return if (meta.isEmpty()) null else String(meta, Charsets.UTF_8)
        }

    override fun setDownloading(downloading: Boolean) = region.setDownloadState(
        if (downloading) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE,
    )

    override fun observe(
        onProgress: (completed: Long, required: Long, complete: Boolean) -> Unit,
        onTileLimit: () -> Unit,
    ) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = onProgress(
                status.completedResourceCount, status.requiredResourceCount, status.isComplete,
            )

            override fun onError(error: OfflineRegionError) {
                // Resource errors are recoverable; MapLibre keeps retrying them with backoff
                // and when the network returns. Cancel is the explicit way out.
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) = onTileLimit()
        })
    }

    override fun readComplete(onResult: (complete: Boolean) -> Unit) {
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) = onResult(status?.isComplete == true)
            override fun onError(error: String?) = onResult(false)
        })
    }

    override fun delete(onDone: () -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone()
            override fun onError(error: String) = onDone()
        })
    }
}

/** Holds the application context only, so it can live in the process-wide manager. */
private class MapLibreBackend(private val appContext: Context) : OfflineBackend {
    override fun list(onAreas: (List<OfflineArea>) -> Unit, onFailure: () -> Unit) {
        offlineManager(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) =
                    onAreas(offlineRegions.orEmpty().map { MapLibreArea(it) })

                override fun onError(error: String) = onFailure()
            },
        )
    }

    override fun create(
        request: OfflineAreaRequest,
        onArea: (OfflineArea) -> Unit,
        onFailure: () -> Unit,
    ) {
        // Rebuilding the rectangle is safe: MapLibre's own visible region is produced by this same
        // validating factory, so the values it reported are accepted again unchanged.
        val definition = OfflineTilePyramidRegionDefinition(
            MAP_STYLE_URL,
            LatLngBounds.from(request.latNorth, request.lonEast, request.latSouth, request.lonWest),
            request.minZoom,
            request.maxZoom,
            appContext.resources.displayMetrics.density,
        )
        offlineManager(appContext).createOfflineRegion(
            definition,
            request.key.toByteArray(Charsets.UTF_8),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = onArea(MapLibreArea(offlineRegion))
                override fun onError(error: String) = onFailure()
            },
        )
    }
}

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

    /** Bound to MapLibre by the first entry point that carries a Context; a test binds a fake. */
    private var backend: OfflineBackend? = null

    /** The area being downloaded, once one has been looked up or created. */
    private var activeArea: OfflineArea? = null

    /**
     * Generation of the running download operation. Looking a region up and creating one are
     * asynchronous, so a cancellation can arrive long before there is any region to stop. Every
     * callback carries the generation it was started for and gives up when it no longer matches;
     * [stop] bumps the generation, which is what makes cancelling take effect at any point of the
     * operation, including before the region exists.
     */
    private var operation = 0

    /** The UI acknowledges a terminal result after showing it, re-arming the Download action. */
    fun acknowledgeResult() {
        if (_state.value is State.Succeeded || _state.value is State.Failed) {
            _state.value = State.Idle
        }
    }

    private fun bind(context: Context): OfflineBackend =
        backend ?: MapLibreBackend(context.applicationContext).also { backend = it }

    /** Re-count stored regions, separating complete areas from retained partial ones. */
    fun refresh(context: Context) {
        bind(context)
        refreshCounts()
    }

    private fun refreshCounts() {
        val backend = backend ?: return
        backend.list(
            onAreas = { areas -> countAreas(areas) },
            onFailure = { _regions.value = RegionCounts(0, 0) },
        )
    }

    private fun countAreas(areas: List<OfflineArea>) {
        if (areas.isEmpty()) {
            _regions.value = RegionCounts(0, 0)
            return
        }
        var complete = 0
        var partial = 0
        var pending = areas.size
        areas.forEach { area ->
            area.readComplete { isComplete ->
                if (isComplete) complete++ else partial++
                if (--pending == 0) _regions.value = RegionCounts(complete, partial)
            }
        }
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
        bind(context)
        val minZoom = floor(MapViewport.zoom).coerceIn(0.0, OFFLINE_MAX_ZOOM)
        val maxZoom = (minZoom + OFFLINE_EXTRA_ZOOM).coerceAtMost(OFFLINE_MAX_ZOOM)
        startArea(
            OfflineAreaRequest(
                latSouth = bounds.latitudeSouth,
                lonWest = bounds.longitudeWest,
                latNorth = bounds.latitudeNorth,
                lonEast = bounds.longitudeEast,
                minZoom = minZoom,
                maxZoom = maxZoom,
            ),
        )
        return true
    }

    /** The context-free core of [start]: one operation, from lookup to activation. */
    internal fun startArea(request: OfflineAreaRequest) {
        if (_state.value is State.Downloading) return
        val backend = backend ?: return
        val op = ++operation
        _state.value = State.Downloading(0)
        backend.list(
            onAreas = { areas -> if (op == operation) resumeOrCreate(backend, request, areas, op) },
            onFailure = { if (op == operation) finish(success = false) },
        )
    }

    private fun resumeOrCreate(
        backend: OfflineBackend,
        request: OfflineAreaRequest,
        areas: List<OfflineArea>,
        op: Int,
    ) {
        val resumable = findResumableRegion(areas.map { it.key }, request.key)
        if (resumable != null) {
            activate(areas[resumable], op)
            return
        }
        backend.create(
            request,
            onArea = { area ->
                // Cancelled or deleted while the region was being created: it holds no tiles yet,
                // so drop it rather than leave a phantom partial area behind.
                if (op == operation) activate(area, op) else area.delete {}
            },
            onFailure = { if (op == operation) finish(success = false) },
        )
    }

    private fun activate(area: OfflineArea, op: Int) {
        activeArea = area
        area.observe(
            onProgress = { completed, required, complete ->
                if (op == operation) {
                    if (complete) {
                        finish(success = true)
                    } else {
                        _state.value = State.Downloading(downloadPercent(completed, required))
                    }
                }
            },
            onTileLimit = { if (op == operation) finish(success = false) },
        )
        area.setDownloading(true)
    }

    private fun finish(success: Boolean) {
        stop()
        _state.value = if (success) State.Succeeded else State.Failed
        refreshCounts()
    }

    /**
     * End the running operation: an already active area stops downloading, keeping its partial
     * tiles, and every callback still in flight for that operation gives up when it arrives.
     */
    private fun stop() {
        operation++
        val area = activeArea
        activeArea = null
        area?.setDownloading(false)
    }

    /** Stop the running download, keeping the partial region so a retry resumes it. */
    fun cancel(context: Context) {
        bind(context)
        cancelDownload()
    }

    internal fun cancelDownload() {
        stop()
        // A terminal result the UI has not shown yet outlives the cancel; only progress is dropped.
        if (_state.value is State.Downloading) _state.value = State.Idle
        refreshCounts()
    }

    /** Delete every stored area; an active download is cancelled first so deletion cannot race it. */
    fun deleteAll(context: Context, onDone: () -> Unit) {
        bind(context)
        deleteAllAreas(onDone)
    }

    internal fun deleteAllAreas(onDone: () -> Unit) {
        val backend = backend ?: return onDone()
        // Also invalidates a start that is still looking up or creating its region, so a stale
        // callback cannot leave a fresh area behind after everything has been deleted.
        stop()
        if (_state.value is State.Downloading) _state.value = State.Idle
        backend.list(
            onAreas = { areas -> deleteEach(areas, onDone) },
            onFailure = onDone,
        )
    }

    private fun deleteEach(areas: List<OfflineArea>, onDone: () -> Unit) {
        if (areas.isEmpty()) {
            _regions.value = RegionCounts(0, 0)
            onDone()
            return
        }
        var remaining = areas.size
        areas.forEach { area ->
            area.delete {
                if (--remaining == 0) {
                    refreshCounts()
                    onDone()
                }
            }
        }
    }

    /** Test seam: drive the state machine with a fake backend from a known clean state. */
    internal fun useBackendForTest(fake: OfflineBackend) {
        stop()
        backend = fake
        _state.value = State.Idle
        _regions.value = null
    }
}
