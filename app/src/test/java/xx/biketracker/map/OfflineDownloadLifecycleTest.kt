package xx.biketracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A stored area; its own callbacks are immediate, the test drives progress by hand. */
private class FakeArea(
    override val key: String?,
    private val store: MutableList<FakeArea>,
) : OfflineArea {
    var downloading = false
        private set
    var deleted = false
        private set
    private var complete = false
    private var onProgress: ((Long, Long, Boolean) -> Unit)? = null
    private var onTileLimit: (() -> Unit)? = null

    override fun setDownloading(downloading: Boolean) {
        this.downloading = downloading
    }

    override fun observe(
        onProgress: (completed: Long, required: Long, complete: Boolean) -> Unit,
        onTileLimit: () -> Unit,
    ) {
        this.onProgress = onProgress
        this.onTileLimit = onTileLimit
    }

    override fun readComplete(onResult: (complete: Boolean) -> Unit) = onResult(complete)

    override fun delete(onDone: () -> Unit) {
        deleted = true
        store.remove(this)
        onDone()
    }

    /** Deliver a status change the way MapLibre's region observer would. */
    fun report(completed: Long, required: Long, isComplete: Boolean) {
        complete = isComplete
        onProgress?.invoke(completed, required, isComplete)
    }
}

/** MapLibre's offline API, with the test deciding when each asynchronous callback arrives. */
private class FakeBackend : OfflineBackend {
    val stored = mutableListOf<FakeArea>()
    var listFails = false
    var createFails = false
    var created = 0
        private set

    private val pending = ArrayDeque<() -> Unit>()

    override fun list(onAreas: (List<OfflineArea>) -> Unit, onFailure: () -> Unit) {
        pending.addLast { if (listFails) onFailure() else onAreas(stored.toList()) }
    }

    override fun create(
        request: OfflineAreaRequest,
        onArea: (OfflineArea) -> Unit,
        onFailure: () -> Unit,
    ) {
        pending.addLast {
            if (createFails) {
                onFailure()
            } else {
                created++
                val area = FakeArea(request.key, stored)
                stored += area
                onArea(area)
            }
        }
    }

    fun deliverNext() = pending.removeFirst().invoke()

    /** Deliver everything queued, including callbacks queued while these ones run. */
    fun deliverAll() {
        while (pending.isNotEmpty()) deliverNext()
    }

    fun storedArea() = stored.single()
}

/**
 * Cancellation and deletion must be able to interrupt a download at any point, including the
 * window between asking MapLibre for a region and getting one back.
 */
class OfflineDownloadLifecycleTest {

    private val backend = FakeBackend()
    private val request = OfflineAreaRequest(
        latSouth = 50.4, lonWest = 30.5,
        latNorth = 50.5, lonEast = 30.6,
        minZoom = 12.0, maxZoom = 16.0,
    )

    private val state get() = OfflineMapManager.state.value

    @Before
    fun bindFakeBackend() {
        OfflineMapManager.useBackendForTest(backend)
    }

    @Test
    fun cancelDuringLookupReturnsToIdleAndNeverStartsDownloading() {
        OfflineMapManager.startArea(request)
        assertTrue(state is OfflineMapManager.State.Downloading)

        OfflineMapManager.cancelDownload()
        assertEquals(OfflineMapManager.State.Idle, state)

        backend.deliverAll()
        assertEquals(OfflineMapManager.State.Idle, state)
        assertEquals(0, backend.created)
        assertTrue(backend.stored.isEmpty())
    }

    @Test
    fun cancelDuringCreationDropsTheAreaThatArrivesTooLate() {
        OfflineMapManager.startArea(request)
        backend.deliverNext() // lookup found nothing, creation is under way

        OfflineMapManager.cancelDownload()
        backend.deliverAll()

        assertEquals(1, backend.created)
        assertTrue(backend.stored.isEmpty())
        assertEquals(OfflineMapManager.State.Idle, state)
    }

    @Test
    fun deleteAllDuringLookupPreventsTheStartFromActivating() {
        OfflineMapManager.startArea(request)

        var done = false
        OfflineMapManager.deleteAllAreas { done = true }
        backend.deliverAll()

        assertTrue(done)
        assertEquals(OfflineMapManager.State.Idle, state)
        assertTrue(backend.stored.isEmpty())
    }

    @Test
    fun deleteAllDuringCreationDeletesTheAreaThatArrivesAfterwards() {
        OfflineMapManager.startArea(request)
        backend.deliverNext()

        var done = false
        OfflineMapManager.deleteAllAreas { done = true }
        backend.deliverAll()

        assertTrue(done)
        assertEquals(1, backend.created)
        assertTrue(backend.stored.isEmpty())
        assertEquals(OfflineMapManager.State.Idle, state)
    }

    @Test
    fun deleteAllRemovesEveryStoredArea() {
        backend.stored += FakeArea("other", backend.stored)
        backend.stored += FakeArea(request.key, backend.stored)

        var done = false
        OfflineMapManager.deleteAllAreas { done = true }
        backend.deliverAll()

        assertTrue(done)
        assertTrue(backend.stored.isEmpty())
        assertEquals(RegionCounts(0, 0), OfflineMapManager.regions.value)
    }

    @Test
    fun staleCompletionAfterCancelDoesNotReportSuccess() {
        OfflineMapManager.startArea(request)
        backend.deliverAll()
        val area = backend.storedArea()
        assertTrue(area.downloading)

        OfflineMapManager.cancelDownload()
        area.report(completed = 10, required = 10, isComplete = true)

        assertEquals(OfflineMapManager.State.Idle, state)
    }

    @Test
    fun staleLookupFailureAfterCancelDoesNotReportFailure() {
        backend.listFails = true
        OfflineMapManager.startArea(request)
        OfflineMapManager.cancelDownload()

        backend.deliverAll()

        assertEquals(OfflineMapManager.State.Idle, state)
    }

    @Test
    fun creationFailureIsTerminal() {
        backend.createFails = true
        OfflineMapManager.startArea(request)
        backend.deliverAll()

        assertEquals(OfflineMapManager.State.Failed, state)
    }

    @Test
    fun startAfterCancellationDownloadsAgain() {
        OfflineMapManager.startArea(request)
        OfflineMapManager.cancelDownload()
        backend.deliverAll()

        OfflineMapManager.startArea(request)
        backend.deliverAll()

        assertTrue(state is OfflineMapManager.State.Downloading)
        assertEquals(1, backend.created)
        assertTrue(backend.storedArea().downloading)
    }

    @Test
    fun secondStartWhileDownloadingIsIgnored() {
        OfflineMapManager.startArea(request)
        backend.deliverAll()
        OfflineMapManager.startArea(request)
        backend.deliverAll()

        assertEquals(1, backend.created)
    }

    @Test
    fun progressIsPublishedAndCompletionSucceeds() {
        OfflineMapManager.startArea(request)
        backend.deliverAll()
        val area = backend.storedArea()

        area.report(completed = 5, required = 10, isComplete = false)
        assertEquals(OfflineMapManager.State.Downloading(50), state)

        area.report(completed = 10, required = 10, isComplete = true)
        assertEquals(OfflineMapManager.State.Succeeded, state)
        assertFalse(area.downloading)

        backend.deliverAll()
        assertEquals(RegionCounts(1, 0), OfflineMapManager.regions.value)
    }

    @Test
    fun matchingStoredAreaIsResumedInsteadOfCreated() {
        backend.stored += FakeArea(request.key, backend.stored)

        OfflineMapManager.startArea(request)
        backend.deliverAll()

        assertEquals(0, backend.created)
        assertTrue(backend.storedArea().downloading)
    }

    @Test
    fun cancelKeepsThePartialAreaForALaterResume() {
        OfflineMapManager.startArea(request)
        backend.deliverAll()
        val area = backend.storedArea()
        area.report(completed = 3, required = 10, isComplete = false)

        OfflineMapManager.cancelDownload()
        backend.deliverAll()

        assertFalse(area.downloading)
        assertFalse(area.deleted)
        assertEquals(OfflineMapManager.State.Idle, state)
        assertEquals(RegionCounts(0, 1), OfflineMapManager.regions.value)
    }

    @Test
    fun cancelKeepsAResultTheUiHasNotShownYet() {
        OfflineMapManager.startArea(request)
        backend.deliverAll()
        backend.storedArea().report(completed = 10, required = 10, isComplete = true)

        OfflineMapManager.cancelDownload()

        assertEquals(OfflineMapManager.State.Succeeded, state)
    }
}
