package xx.biketracker.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMaintenanceTest {

    @After
    fun cleanUp() {
        DatabaseMaintenance.releaseRide()
        assertEquals(DatabaseMaintenanceOperation.NONE, DatabaseMaintenance.operation.value)
    }

    @Test
    fun reservedRideRejectsMaintenanceAndDuplicateStart() = runBlocking {
        assertTrue(DatabaseMaintenance.reserveRide())
        assertFalse(DatabaseMaintenance.reserveRide())

        var rejected = false
        try {
            DatabaseMaintenance.withMaintenance(DatabaseMaintenanceOperation.BACKUP) { }
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(DatabaseMaintenanceOperation.NONE, DatabaseMaintenance.operation.value)
    }

    @Test
    fun maintenanceRejectsRideAndSerializesWriters() = runBlocking {
        val enteredMaintenance = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val maintenance = launch(Dispatchers.Default) {
            DatabaseMaintenance.withMaintenance(DatabaseMaintenanceOperation.BACKUP) {
                enteredMaintenance.complete(Unit)
                releaseMaintenance.await()
            }
        }
        enteredMaintenance.await()

        assertFalse(DatabaseMaintenance.reserveRide())
        assertFalse(DatabaseMaintenance.tryWrite { })
        var writerEntered = false
        val writer = async(Dispatchers.Default) {
            DatabaseMaintenance.withWrite { writerEntered = true }
        }
        delay(50)
        assertFalse(writerEntered)

        releaseMaintenance.complete(Unit)
        maintenance.join()
        writer.await()

        assertTrue(writerEntered)
        assertEquals(DatabaseMaintenanceOperation.NONE, DatabaseMaintenance.operation.value)
    }
}
