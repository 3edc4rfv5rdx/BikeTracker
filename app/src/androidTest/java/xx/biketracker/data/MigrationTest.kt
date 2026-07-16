package xx.biketracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test.db"

/**
 * Validates every schema migration against the exported schema JSONs (bundled as androidTest
 * assets), both step by step and over the full 1 -> current path, and checks that pre-existing
 * rows keep their data and get the documented defaults for the new columns.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private fun seedV1() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO trips (startTime, endTime, distanceMeters, movingTimeMillis, maxSpeedMps) " +
                    "VALUES (1000, 2000, 1234.5, 600000, 8.5)"
            )
            db.execSQL(
                "INSERT INTO track_points (tripId, lat, lon, time, speedMps) " +
                    "VALUES (1, 50.45, 30.52, 1500, 5.0)"
            )
        }
    }

    @Test
    fun migrate1To2_pointsGainNullAltitude() {
        seedV1()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT lat, lon, altitudeMeters FROM track_points").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(50.45, cursor.getDouble(0), 1e-9)
            assertEquals(30.52, cursor.getDouble(1), 1e-9)
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun migrate2To3_tripsGainNullAggregates() {
        seedV1()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, *AppDatabase.MIGRATIONS).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT avgGpsSpeedMps, elevationGainMeters FROM trips").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun migrate3To4_existingTripsAreFinished() {
        seedV1()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.MIGRATIONS).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT finished FROM trips").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migrate4To5_tripsGainNullTitleAndNote() {
        seedV1()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, *AppDatabase.MIGRATIONS).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT title, note FROM trips").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun migrateAll_fromV1ToCurrentKeepsRows() {
        seedV1()
        val db = helper.runMigrationsAndValidate(
            TEST_DB, CURRENT_SCHEMA_VERSION, true, *AppDatabase.MIGRATIONS
        )
        db.query(
            "SELECT startTime, endTime, distanceMeters, movingTimeMillis, maxSpeedMps, finished FROM trips"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1000L, cursor.getLong(0))
            assertEquals(2000L, cursor.getLong(1))
            assertEquals(1234.5, cursor.getDouble(2), 1e-9)
            assertEquals(600000L, cursor.getLong(3))
            assertEquals(8.5, cursor.getDouble(4), 1e-9)
            assertEquals(1, cursor.getInt(5))
        }
        db.query("SELECT COUNT(*) FROM track_points").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
