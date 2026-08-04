package xx.biketracker.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import xx.biketracker.GeoPoint
import xx.biketracker.data.AppDatabase
import xx.biketracker.data.Trip

/** An imported GPX track shown on the map for viewing only; never stored. [id] keys the map's
 *  recenter so a freshly imported track frames itself once. */
class ImportedTrack(val id: Long, val name: String?, val route: List<GeoPoint>)

/**
 * Process-wide "show this on the Map tab" selection: either a stored ride (set by the History
 * tree's map button) or an imported GPX track (set by the Map top-bar's import button). While one
 * is set, the Map tab shows it instead of the live track; clearing returns to the live view. The
 * two are mutually exclusive — setting one drops the other.
 *
 * A stored ride is held by **id**, never as a copy of its row: a copy goes on naming a ride that
 * has since been renamed, and goes on showing one that has since been deleted. Screens read the
 * ride itself through [rememberSelectedTrip].
 */
object MapSelection {
    private val _selectedTripId = MutableStateFlow<Long?>(null)
    val selectedTripId: StateFlow<Long?> = _selectedTripId.asStateFlow()

    /** The ride as it read when it was picked — only so the first frame after picking has
     *  something to name while the database answers. Never the source of truth. */
    @Volatile
    private var picked: Trip? = null

    private val _imported = MutableStateFlow<ImportedTrack?>(null)
    val imported: StateFlow<ImportedTrack?> = _imported.asStateFlow()
    private var nextImportId = 0L

    fun select(trip: Trip) {
        _imported.value = null
        picked = trip
        _selectedTripId.value = trip.id
    }

    fun showImported(name: String?, route: List<GeoPoint>) {
        _selectedTripId.value = null
        picked = null
        _imported.value = ImportedTrack(nextImportId++, name, route)
    }

    fun clear() {
        _selectedTripId.value = null
        picked = null
        _imported.value = null
    }

    /** Drop the selection if it points at the given trip — called when a ride is deleted. */
    fun clearIf(tripId: Long) {
        if (_selectedTripId.compareAndSet(tripId, null)) picked = null
    }

    internal fun pickedTrip(tripId: Long): Trip? = picked?.takeIf { it.id == tripId }
}

/**
 * The selected ride as the database has it now, or null when none is selected. It follows an edit
 * without the ride having to be picked again, and when the row is deleted it comes back null and
 * the selection drops itself — wherever the deletion happened.
 */
@Composable
fun rememberSelectedTrip(): Trip? {
    val context = LocalContext.current
    val selectedId by MapSelection.selectedTripId.collectAsState()
    val tripId = selectedId ?: return null
    val trip by remember(tripId) {
        AppDatabase.get(context).tripDao().observeTrip(tripId).distinctUntilChanged()
    }.collectAsState(initial = MapSelection.pickedTrip(tripId))
    // Deleting from History clears the selection itself; this also covers a row that goes without
    // anyone saying so — a restored database above all.
    LaunchedEffect(tripId, trip) {
        if (trip == null) MapSelection.clearIf(tripId)
    }
    return trip
}
