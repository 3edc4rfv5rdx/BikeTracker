package xx.biketracker.map

import android.content.Context
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
/** Consecutive resource errors with no progress in between — the network is effectively gone. */
private const val OFFLINE_MAX_CONSECUTIVE_ERRORS = 5

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

/**
 * Download the last viewed area ([MapViewport]) for offline use: style, glyphs and vector tiles
 * from the viewed zoom down to [OFFLINE_EXTRA_ZOOM] extra levels (capped at [OFFLINE_MAX_ZOOM]).
 * Returns false if the map was never opened, so there is no area to download. Progress is 0..100;
 * finishing (or failing) is reported once. The download continues if the caller's UI goes away —
 * the region persists in MapLibre's offline database.
 */
fun downloadViewedRegion(
    context: Context,
    onProgress: (Int) -> Unit,
    onFinished: (Boolean) -> Unit,
): Boolean {
    val bounds = MapViewport.bounds ?: return false
    val minZoom = floor(MapViewport.zoom).coerceIn(0.0, OFFLINE_MAX_ZOOM)
    val definition = OfflineTilePyramidRegionDefinition(
        MAP_STYLE_URL,
        bounds,
        minZoom,
        (minZoom + OFFLINE_EXTRA_ZOOM).coerceAtMost(OFFLINE_MAX_ZOOM),
        context.resources.displayMetrics.density,
    )
    OfflineManager.getInstance(context).createOfflineRegion(
        definition,
        ByteArray(0),
        object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    private var finished = false
                    private var consecutiveErrors = 0
                    private var lastCompletedCount = -1L

                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (finished) return
                        if (status.isComplete) {
                            finished = true
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            onFinished(true)
                        } else if (status.requiredResourceCount > 0) {
                            // Resources still coming in — earlier errors were transient.
                            if (status.completedResourceCount > lastCompletedCount) {
                                lastCompletedCount = status.completedResourceCount
                                consecutiveErrors = 0
                            }
                            onProgress((100L * status.completedResourceCount / status.requiredResourceCount).toInt())
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        // A lone error can be a server hiccup, but a streak with zero progress
                        // means the network is gone — fail instead of hanging forever. The
                        // partial region is kept; a retry re-downloads only what is missing.
                        if (finished) return
                        if (++consecutiveErrors >= OFFLINE_MAX_CONSECUTIVE_ERRORS) {
                            finished = true
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            onFinished(false)
                        }
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        if (finished) return
                        finished = true
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        onFinished(false)
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) = onFinished(false)
        },
    )
    return true
}

/** Number of downloaded offline areas, delivered asynchronously. */
fun countOfflineRegions(context: Context, onResult: (Int) -> Unit) {
    OfflineManager.getInstance(context).listOfflineRegions(
        object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = onResult(offlineRegions?.size ?: 0)
            override fun onError(error: String) = onResult(0)
        },
    )
}

/** Delete every downloaded offline area; [onDone] fires after the last one (or on failure). */
fun deleteAllOfflineRegions(context: Context, onDone: () -> Unit) {
    OfflineManager.getInstance(context).listOfflineRegions(
        object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty()
                if (regions.isEmpty()) {
                    onDone()
                    return
                }
                var remaining = regions.size
                regions.forEach { region ->
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            if (--remaining == 0) onDone()
                        }

                        override fun onError(error: String) {
                            if (--remaining == 0) onDone()
                        }
                    })
                }
            }

            override fun onError(error: String) = onDone()
        },
    )
}
