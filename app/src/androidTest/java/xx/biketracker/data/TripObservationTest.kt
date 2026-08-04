package xx.biketracker.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TripDao.observeTrip] is what keeps a screen showing a ride from outliving the ride: the Map tab
 * and the extended-stats overlay both follow it rather than holding the row they were handed.
 */
@RunWith(AndroidJUnit4::class)
class TripObservationTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun trip() = Trip(
        startTime = 1_000L,
        endTime = 2_000L,
        distanceMeters = 1_234.0,
        movingTimeMillis = 600_000L,
        maxSpeedMps = 8.0,
        title = "Before",
        finished = true,
    )

    @Test
    fun renamingARideReachesItsObserver() = runBlocking {
        val dao = db.tripDao()
        val id = dao.insertTrip(trip())

        assertEquals("Before", dao.observeTrip(id).first()?.title)
        dao.updateTripMeta(id, title = "After", note = "Windy")

        val renamed = dao.observeTrip(id).first()
        assertEquals("After", renamed?.title)
        assertEquals("Windy", renamed?.note)
    }

    @Test
    fun deletingARideLeavesItsObserverWithNothing() = runBlocking {
        val dao = db.tripDao()
        val id = dao.insertTrip(trip())
        assertEquals(id, dao.observeTrip(id).first()?.id)

        dao.deleteTripById(id)

        assertNull(dao.observeTrip(id).first())
    }
}
