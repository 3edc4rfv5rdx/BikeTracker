package xx.biketracker.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DatabaseMaintenanceOperation { NONE, BACKUP, RESTORE }

/** Serializes whole-database maintenance with every application-owned Room write. */
object DatabaseMaintenance {
    private val databaseMutex = Mutex()
    private val _operation = MutableStateFlow(DatabaseMaintenanceOperation.NONE)
    val operation: StateFlow<DatabaseMaintenanceOperation> = _operation.asStateFlow()

    private var rideReserved = false

    /** Atomically reserve the database for a new ride before its asynchronous draft insert. */
    @Synchronized
    fun reserveRide(): Boolean {
        if (rideReserved || _operation.value != DatabaseMaintenanceOperation.NONE) return false
        rideReserved = true
        return true
    }

    @Synchronized
    fun releaseRide() {
        rideReserved = false
    }

    suspend fun <T> withWrite(block: suspend () -> T): T = databaseMutex.withLock { block() }

    /** Destructive UI actions must not queue across a restore and act on stale entity IDs. */
    suspend fun tryWrite(block: suspend () -> Unit): Boolean {
        if (!databaseMutex.tryLock()) return false
        return try {
            if (_operation.value != DatabaseMaintenanceOperation.NONE) return false
            block()
            true
        } finally {
            databaseMutex.unlock()
        }
    }

    suspend fun <T> withMaintenance(
        requested: DatabaseMaintenanceOperation,
        block: suspend () -> T,
    ): T = databaseMutex.withLock {
        synchronized(this) {
            check(!rideReserved) { "Ride is active" }
            check(_operation.value == DatabaseMaintenanceOperation.NONE) { "Database maintenance is active" }
            _operation.value = requested
        }
        try {
            block()
        } finally {
            synchronized(this) { _operation.value = DatabaseMaintenanceOperation.NONE }
        }
    }
}
