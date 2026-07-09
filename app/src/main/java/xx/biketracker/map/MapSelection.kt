package xx.biketracker.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xx.biketracker.data.Trip

/**
 * Process-wide "show this ride on the Map tab" selection, set by the History tree's map button
 * and cleared from the Map tab's chip (or when the ride is deleted). While set, the Map tab
 * shows the selected ride's stored track instead of the live one.
 */
object MapSelection {
    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip.asStateFlow()

    fun select(trip: Trip) {
        _trip.value = trip
    }

    fun clear() {
        _trip.value = null
    }

    /** Drop the selection if it points at the given trip — called when a ride is deleted. */
    fun clearIf(tripId: Long) {
        _trip.update { if (it?.id == tripId) null else it }
    }
}
