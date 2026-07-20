package xx.biketracker.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xx.biketracker.GeoPoint
import xx.biketracker.data.Trip

/** An imported GPX track shown on the map for viewing only; never stored. [id] keys the map's
 *  recenter so a freshly imported track frames itself once. */
class ImportedTrack(val id: Long, val name: String?, val route: List<GeoPoint>)

/**
 * Process-wide "show this on the Map tab" selection: either a stored ride (set by the History
 * tree's map button) or an imported GPX track (set by the Map top-bar's import button). While one
 * is set, the Map tab shows it instead of the live track; clearing returns to the live view. The
 * two are mutually exclusive — setting one drops the other.
 */
object MapSelection {
    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip.asStateFlow()

    private val _imported = MutableStateFlow<ImportedTrack?>(null)
    val imported: StateFlow<ImportedTrack?> = _imported.asStateFlow()
    private var nextImportId = 0L

    fun select(trip: Trip) {
        _imported.value = null
        _trip.value = trip
    }

    fun showImported(name: String?, route: List<GeoPoint>) {
        _trip.value = null
        _imported.value = ImportedTrack(nextImportId++, name, route)
    }

    fun clear() {
        _trip.value = null
        _imported.value = null
    }

    /** Drop the selection if it points at the given trip — called when a ride is deleted. */
    fun clearIf(tripId: Long) {
        _trip.update { if (it?.id == tripId) null else it }
    }
}
