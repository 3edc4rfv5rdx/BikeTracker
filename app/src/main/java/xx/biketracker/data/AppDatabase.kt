package xx.biketracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [Trip::class, TrackPoint::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    /** Fold the write-ahead log into the main file so a plain file copy is a complete backup. */
    fun checkpoint() {
        // The cursor must be stepped: SQLite runs the PRAGMA lazily on first read, so closing an
        // unread cursor would skip the checkpoint entirely and leave the WAL unflushed.
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    companion object {
        const val DB_NAME = "biketracker.db"

        /** v1 -> v2: per-point GPS altitude (nullable; existing points stay NULL). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN altitudeMeters REAL")
            }
        }

        /** v2 -> v3: per-trip average GPS speed and elevation gain (nullable; older trips stay NULL). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN avgGpsSpeedMps REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN elevationGainMeters REAL")
            }
        }

        /** v3 -> v4: draft flag for incremental ride persistence (existing trips are finished). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN finished INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }

        /** On-disk location of the main database file. */
        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        /** Close the open database so its file can be replaced by a restore. */
        fun closeAndReset() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
