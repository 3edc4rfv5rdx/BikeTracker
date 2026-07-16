package xx.biketracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val CURRENT_SCHEMA_VERSION = 5

@Database(
    entities = [Trip::class, TrackPoint::class],
    version = CURRENT_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    /** Fold the write-ahead log into the main file so a plain file copy is a complete backup. */
    fun checkpoint() {
        // The cursor must be stepped: SQLite runs the PRAGMA lazily on first read, so closing an
        // unread cursor would skip the checkpoint entirely and leave the WAL unflushed.
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Database checkpoint is busy" }
        }
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

        /** v4 -> v5: optional rider-supplied name and comment (existing trips stay NULL/unset). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE trips ADD COLUMN note TEXT")
            }
        }

        /** Full migration path; internal so the instrumentation tests validate the same objects. */
        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    recoverInterruptedRestore(context.applicationContext)
                    build(context.applicationContext, DB_NAME).also { instance = it }
                }
            }

        /** On-disk location of the main database file. */
        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        /** Deterministic rollback file left only while an atomic restore is being committed. */
        internal fun restoreRollbackFile(context: Context): File =
            context.getDatabasePath("$DB_NAME.restore-rollback")

        /** Open a staged database with the production migrations, without touching the singleton. */
        internal fun openStaged(context: Context, databaseName: String): AppDatabase =
            build(context.applicationContext, databaseName)

        /** Open the newly swapped main file while its rollback marker still exists. */
        internal fun openAfterRestore(context: Context): AppDatabase = synchronized(this) {
            check(instance == null) { "Database is already open" }
            val restored = build(context.applicationContext, DB_NAME)
            try {
                // Force Room schema validation before publishing the replacement singleton.
                restored.openHelper.writableDatabase
                instance = restored
                restored
            } catch (failure: Throwable) {
                restored.close()
                throw failure
            }
        }

        /** Close the open database so its file can be replaced by a restore. */
        fun closeAndReset() = synchronized(this) {
            instance?.close()
            instance = null
        }

        private fun build(context: Context, databaseName: String): AppDatabase =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName,
            ).addMigrations(*MIGRATIONS).build()

        /**
         * A process can die after the old database was moved aside but before the restored one was
         * verified. The rollback file is therefore the commit marker: normal startup always prefers
         * it, accepting a lost restore attempt rather than lost existing rides.
         */
        private fun recoverInterruptedRestore(context: Context) {
            val rollback = restoreRollbackFile(context)
            if (rollback.exists()) {
                val live = databaseFile(context)
                deleteSidecars(live)
                Files.move(
                    rollback.toPath(),
                    live.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
            // A process death before the commit phase can leave only an inert staged candidate.
            databaseFile(context).parentFile?.listFiles { file ->
                file.name.startsWith(".restore-candidate-") && file.name.endsWith(".db")
            }?.forEach { stale ->
                stale.delete()
                deleteSidecars(stale)
            }
        }

        internal fun deleteSidecars(database: File) {
            File("${database.path}-wal").delete()
            File("${database.path}-shm").delete()
            File("${database.path}-journal").delete()
        }
    }
}
