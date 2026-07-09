package xx.biketracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-database backup and restore. A backup is a ZIP holding one SQLite file (the complete
 * ride database), written to the shared Documents/BikeTracker folder so it survives app
 * uninstall and is easy to copy off the phone. This is deliberately distinct from per-trip
 * GPX export: GPX is for interop, this is a full restorable snapshot.
 */

/** Public subfolder under Documents where backups are written. */
private val BACKUP_DIR = "${Environment.DIRECTORY_DOCUMENTS}/BikeTracker"

/** Name of the single SQLite entry inside the backup ZIP. */
private const val DB_ENTRY_NAME = "biketracker.db"

/** Backup file name: biketr-YYYYMMDD-HHMMSS.zip. */
private fun backupFileName(now: Long = System.currentTimeMillis()): String =
    "biketr-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.zip"

/**
 * Zip the live database into Documents/BikeTracker with a timestamped name. Checkpoints the
 * WAL first so the copied file is self-contained. Returns the relative path shown to the user.
 * Must run off the main thread.
 */
suspend fun backupDatabase(context: Context): String = withContext(Dispatchers.IO) {
    AppDatabase.get(context).checkpoint()
    val source = AppDatabase.databaseFile(context)

    val name = backupFileName()
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, BACKUP_DIR)
    }
    val resolver = context.contentResolver
    val target = resolver.insert(MediaStore.Files.getContentUri("external"), values)
        ?: error("Cannot create backup file")

    resolver.openOutputStream(target)?.use { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY_NAME))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    } ?: error("Cannot open output stream")

    "$BACKUP_DIR/$name"
}

/**
 * Restore from a backup ZIP at [source]. Extracts and validates the SQLite entry before
 * touching the live database, so a wrong pick can't brick the app. After this returns the
 * caller must recreate its UI (the Room instance was reopened fresh).
 */
suspend fun restoreDatabase(context: Context, source: Uri): Unit = withContext(Dispatchers.IO) {
    val temp = File.createTempFile("restore", ".db", context.cacheDir)
    try {
        extractDatabase(context, source, temp)
        require(isBikeTrackerDatabase(temp)) { "Not a BikeTracker backup" }

        AppDatabase.closeAndReset()
        val dbFile = AppDatabase.databaseFile(context)
        // Stale WAL/SHM would otherwise be replayed over the restored file.
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        dbFile.parentFile?.mkdirs()
        temp.inputStream().use { input ->
            dbFile.outputStream().use { input.copyTo(it) }
        }
    } finally {
        temp.delete()
    }
    AppDatabase.get(context)
}

/** Copy the first `.db` entry of the backup ZIP into [dest]. */
private fun extractDatabase(context: Context, source: Uri, dest: File) {
    context.contentResolver.openInputStream(source)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".db")) {
                    dest.outputStream().use { zip.copyTo(it) }
                    return
                }
                entry = zip.nextEntry
            }
            error("No database in backup")
        }
    } ?: error("Cannot open input stream")
}

/** True if the file is a readable SQLite database containing our expected tables. */
private fun isBikeTrackerDatabase(file: File): Boolean = try {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('trips', 'track_points')",
            null,
        ).use { it.count == 2 }
    }
} catch (_: Exception) {
    false
}
