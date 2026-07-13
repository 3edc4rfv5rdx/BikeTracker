package xx.biketracker.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineRegionLogicTest {

    private val key = offlineRegionKey(
        styleUrl = "https://tiles.example.org/style.json",
        latSouth = 50.400000, lonWest = 30.500000,
        latNorth = 50.500000, lonEast = 30.600000,
        minZoom = 12.0, maxZoom = 16.0,
    )

    @Test
    fun keyIsStableAcrossRuns() {
        val again = offlineRegionKey(
            "https://tiles.example.org/style.json",
            50.4, 30.5, 50.5, 30.6, 12.0, 16.0,
        )
        assertEquals(key, again)
    }

    @Test
    fun keyDiffersWhenAreaOrZoomDiffers() {
        val otherArea = offlineRegionKey(
            "https://tiles.example.org/style.json",
            50.4, 30.5, 50.5, 30.7, 12.0, 16.0,
        )
        val otherZoom = offlineRegionKey(
            "https://tiles.example.org/style.json",
            50.4, 30.5, 50.5, 30.6, 13.0, 16.0,
        )
        assertEquals(3, setOf(key, otherArea, otherZoom).size)
    }

    @Test
    fun matchingRegionIsResumedInsteadOfDuplicated() {
        assertEquals(1, findResumableRegion(listOf("other", key, key), key))
    }

    @Test
    fun noMatchCreatesNewRegion() {
        assertNull(findResumableRegion(listOf("other-a", "other-b"), key))
        assertNull(findResumableRegion(emptyList(), key))
    }

    @Test
    fun legacyRegionsWithoutMetadataNeverMatch() {
        assertNull(findResumableRegion(listOf(null, null), key))
    }

    @Test
    fun percentHandlesUnknownManifestAndClamping() {
        assertEquals(0, downloadPercent(completed = 0, required = 0))
        assertEquals(0, downloadPercent(completed = 5, required = 0))
        assertEquals(50, downloadPercent(completed = 1, required = 2))
        assertEquals(100, downloadPercent(completed = 7, required = 7))
    }
}
