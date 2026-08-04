package xx.biketracker.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xx.biketracker.GeoPoint
import xx.biketracker.data.Trip

/**
 * What the Map tab is showing is process-wide state that outlives any one screen — and, without
 * care, the database it was chosen from. A ride is held by id precisely so that nothing here can
 * go on describing a row that has changed underneath it.
 */
class MapSelectionTest {

    @After
    fun clearProcessWideState() = MapSelection.clear()

    private fun trip(id: Long) = Trip(
        id = id,
        startTime = 1_000L,
        endTime = 2_000L,
        distanceMeters = 1_000.0,
        movingTimeMillis = 300_000L,
        maxSpeedMps = 8.0,
    )

    @Test
    fun pickingARideKeepsItsIdAndItsLastKnownRow() {
        MapSelection.select(trip(7))

        assertEquals(7L, MapSelection.selectedTripId.value)
        // Only so the first frame has something to name; the row itself is read from the database.
        assertEquals(7L, MapSelection.pickedTrip(7)?.id)
        assertNull("another ride's id must not be answered", MapSelection.pickedTrip(8))
    }

    @Test
    fun pickingARideDropsAnImportedTrackAndTheOtherWayAround() {
        MapSelection.showImported("tour.gpx", listOf(GeoPoint(50.0, 30.0)))
        MapSelection.select(trip(7))
        assertNull(MapSelection.imported.value)

        MapSelection.showImported("tour.gpx", listOf(GeoPoint(50.0, 30.0)))
        assertNull(MapSelection.selectedTripId.value)
    }

    @Test
    fun aRestoreForgetsTheRideButNotTheImportedTrack() {
        // After a restore the same id belongs to a different set of rides: it may name nothing, or
        // — the reason this cannot be left to the screens — a different ride that inherited it.
        // An imported GPX is not a row in any database and has nothing to fear from the swap.
        MapSelection.select(trip(7))
        MapSelection.showImported("tour.gpx", listOf(GeoPoint(50.0, 30.0)))
        MapSelection.select(trip(7))

        MapSelection.clearStoredSelection()

        assertNull(MapSelection.selectedTripId.value)
        assertNull("a stale row must not survive either", MapSelection.pickedTrip(7))

        MapSelection.showImported("tour.gpx", listOf(GeoPoint(50.0, 30.0)))
        MapSelection.clearStoredSelection()
        assertNotNull(MapSelection.imported.value)
    }

    @Test
    fun deletingAnotherRideLeavesTheSelectionAlone() {
        MapSelection.select(trip(7))

        MapSelection.clearIf(8)
        assertEquals(7L, MapSelection.selectedTripId.value)

        MapSelection.clearIf(7)
        assertNull(MapSelection.selectedTripId.value)
        assertNull(MapSelection.pickedTrip(7))
    }
}
