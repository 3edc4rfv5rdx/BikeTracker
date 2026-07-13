package xx.biketracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/** A corrupt/malicious archive must not fill internal storage while being inspected. */
private const val MAX_RESTORE_DATABASE_BYTES = 512L * 1024L * 1024L

sealed interface RestoreOperationState {
    data object Idle : RestoreOperationState
    data object Running : RestoreOperationState
    data object Succeeded : RestoreOperationState
    data object Failed : RestoreOperationState
}

/**
 * Owns restore work independently of the Settings composition. Switching tabs can no longer
 * cancel a database replacement half-way through; MainActivity observes [state] and recreates the
 * UI after the singleton has been reopened.
 */
object DatabaseRestoreCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RestoreOperationState>(RestoreOperationState.Idle)
    val state: StateFlow<RestoreOperationState> = _state.asStateFlow()

    @Synchronized
    fun start(context: Context, source: Uri): Boolean {
        if (_state.value == RestoreOperationState.Running) return false
        _state.value = RestoreOperationState.Running
        val appContext = context.applicationContext
        scope.launch {
            _state.value = try {
                restoreDatabase(appContext, source)
                RestoreOperationState.Succeeded
            } catch (_: Exception) {
                RestoreOperationState.Failed
            }
        }
        return true
    }

    @Synchronized
    fun acknowledgeResult() {
        if (_state.value != RestoreOperationState.Running) {
            _state.value = RestoreOperationState.Idle
        }
    }
}

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
    val databaseDir = AppDatabase.databaseFile(context).parentFile
        ?: error("Database directory is unavailable")
    check(databaseDir.mkdirs() || databaseDir.isDirectory) { "Cannot create database directory" }
    // Same directory means both commit renames remain on one filesystem and can be atomic.
    val staged = File.createTempFile(".restore-candidate-", ".db", databaseDir)
    try {
        extractDatabase(context, source, staged)
        validateAndMigrateStagedDatabase(context, staged)

        // The launch-time recovery pass may still be querying; closing under it would crash.
        recoveryJob?.join()
        require(xx.biketracker.tracking.TrackingState.snapshot.value.status ==
            xx.biketracker.tracking.TrackingStatus.IDLE) { "Ride is active" }

        // Once the live database is closed, cancellation must not strand a truncated/missing file.
        withContext(NonCancellable) {
            commitStagedDatabase(context, staged)
        }
    } finally {
        staged.delete()
        AppDatabase.deleteSidecars(staged)
    }
}

/** Copy the first `.db` entry of the backup ZIP into [dest]. */
private suspend fun extractDatabase(context: Context, source: Uri, dest: File) {
    context.contentResolver.openInputStream(source)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".db")) {
                    require(entry.size < 0 || entry.size <= MAX_RESTORE_DATABASE_BYTES) {
                        "Database entry is too large"
                    }
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_RESTORE_DATABASE_BYTES) { "Database entry is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                    return
                }
                entry = zip.nextEntry
            }
            error("No database in backup")
        }
    } ?: error("Cannot open input stream")
}

/** Validate basic SQLite integrity, then let production Room migrations prove full compatibility. */
private fun validateAndMigrateStagedDatabase(context: Context, file: File) {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "Database integrity check failed"
            }
        }
        val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            require(cursor.moveToFirst()) { "Database version is missing" }
            cursor.getInt(0)
        }
        require(version in 1..CURRENT_SCHEMA_VERSION) { "Unsupported database version: $version" }
        val tableCount = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type='table' AND name IN ('trips', 'track_points')",
            null,
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Database schema is unreadable" }
            cursor.getInt(0)
        }
        require(tableCount == 2) { "Not a BikeTracker database" }
    }

    val stagedDb = AppDatabase.openStaged(context, file.name)
    try {
        stagedDb.openHelper.writableDatabase
        stagedDb.checkpoint()
    } finally {
        stagedDb.close()
        AppDatabase.deleteSidecars(file)
    }
}

/** Swap the already-validated current-schema file in, rolling back on every failure. */
private fun commitStagedDatabase(context: Context, staged: File) {
    val live = AppDatabase.databaseFile(context)
    val rollback = AppDatabase.restoreRollbackFile(context)
    require(!rollback.exists()) { "A previous restore requires recovery" }

    // Closing the last Room connection normally checkpoints WAL, but make the durability
    // requirement explicit before moving only the main file aside.
    AppDatabase.get(context).checkpoint()
    AppDatabase.closeAndReset()
    AppDatabase.deleteSidecars(live)
    val hadLiveDatabase = live.exists()
    if (hadLiveDatabase) atomicMove(live, rollback)

    try {
        atomicMove(staged, live)
        AppDatabase.openAfterRestore(context)
        check(!rollback.exists() || rollback.delete()) { "Cannot finalize database restore" }
    } catch (failure: Throwable) {
        AppDatabase.closeAndReset()
        AppDatabase.deleteSidecars(live)
        live.delete()
        if (hadLiveDatabase && rollback.exists()) {
            atomicMove(rollback, live)
        }
        try {
            AppDatabase.get(context).openHelper.writableDatabase
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        throw failure
    }
}

private fun atomicMove(source: File, destination: File) {
    Files.move(
        source.toPath(),
        destination.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
}
