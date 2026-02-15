package com.kraata.harmony.viewmodels

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import com.kraata.harmony.MainActivity
import com.kraata.harmony.R
import com.kraata.harmony.constants.AccountChannelHandleKey
import com.kraata.harmony.constants.AccountEmailKey
import com.kraata.harmony.constants.AccountNameKey
import com.kraata.harmony.constants.DataSyncIdKey
import com.kraata.harmony.constants.InnerTubeCookieKey
import com.kraata.harmony.constants.UseLoginForBrowse
import com.kraata.harmony.constants.VisitorDataKey
import com.kraata.harmony.db.InternalDatabase
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.extensions.div
import com.kraata.harmony.extensions.zipInputStream
import com.kraata.harmony.extensions.zipOutputStream
import com.kraata.harmony.playback.MusicService
import com.kraata.harmony.utils.dataStore
import com.kraata.harmony.utils.reportException
import com.zionhuang.innertube.utils.parseCookieString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()

    fun backup(uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openOutputStream(uri)?.use {
                it.buffered().zipOutputStream().use { outputStream ->
                    outputStream.setLevel(Deflater.BEST_COMPRESSION)
                    (context.filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered().use { inputStream ->
                        outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                        inputStream.copyTo(outputStream)
                    }
                    runBlocking(Dispatchers.IO) {
                        database.checkpoint()
                    }
                    FileInputStream(database.openHelper.writableDatabase.path).use { inputStream ->
                        outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }.onSuccess {
            Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            reportException(it)
            Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun restore(uri: Uri) {
        runCatching {
            logAuthSettingsSnapshot("before_restore")
            var restoreSuccessful = false
            var settingsRestored = false

            context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.zipInputStream().use { inputStream ->
                    var entry = inputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            SETTINGS_FILENAME -> {
                                (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream()
                                    .use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                settingsRestored = true
                            }

                            InternalDatabase.DB_NAME -> {
                                Log.i(TAG, "Starting database restore")
                                runBlocking(Dispatchers.IO) {
                                    database.checkpoint()
                                }
                                database.close()

                                Log.i(TAG, "Testing new database for compatibility...")
                                val destFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                destFile.parentFile?.apply {
                                    if (!exists()) mkdirs()
                                }
                                FileOutputStream(destFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }

                                val status = try {
                                    val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                    t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                    t.close()
                                    true
                                } catch (e: Exception) {
                                    Log.e(TAG, "DB validation failed", e)
                                    false
                                }

                                if (status) {
                                    Log.i(TAG, "Found valid database, proceeding with restore")
                                    destFile.inputStream().use { inputStream ->
                                        FileOutputStream(database.openHelper.writableDatabase.path).use { outputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                    restoreSuccessful = true
                                    createImportCacheMarker("restore")
                                } else {
                                    Log.e(TAG, "Incompatible database, aborting restore")
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.err_restore_incompatible_database),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }

            if (settingsRestored) {
                clearImportedSessionCredentials("restore")
            }

            if (restoreSuccessful) {
                logAuthSettingsSnapshot("after_restore_before_restart")
                val stopIntent = Intent(context, MusicService::class.java)
                context.stopService(stopIntent)
                val startIntent = Intent(context, MainActivity::class.java)
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(startIntent)
                exitProcess(0)
            } else {
                Log.w(TAG, "Restore finished without replacing database. Restart skipped.")
            }
        }.onFailure {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun import(uri: Uri) {
        Log.d(TAG, "=== STARTING IMPORT PROCESS ===")
        Log.d(TAG, "Import URI: $uri")

        runCatching {
            logAuthSettingsSnapshot("before_import")
            var importSuccessful = false
            var importResult: CrossForkMigrationViewModel.ImportResult? = null
            var settingsRestored = false

            context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                Log.d(TAG, "Successfully opened input stream from URI")

                input.zipInputStream().use { zipInputStream ->
                    Log.d(TAG, "Successfully opened ZIP input stream")

                    var entry = zipInputStream.nextEntry
                    var entriesFound = 0

                    while (entry != null) {
                        entriesFound++
                        Log.d(TAG, "ZIP entry #$entriesFound: ${entry.name}, size: ${entry.size}, compressed: ${entry.compressedSize}")

                        when (entry.name) {
                            SETTINGS_FILENAME -> {
                                Log.d(TAG, "Found settings file, extracting...")
                                try {
                                    (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream()
                                        .use { outputStream ->
                                            zipInputStream.copyTo(outputStream)
                                        }
                                    Log.d(TAG, "Settings file extracted successfully")
                                    settingsRestored = true
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to extract settings file", e)
                                }
                            }

                            InternalDatabase.DB_NAME -> {
                                Log.i(TAG, "Found database file in backup")

                                // Create temp file for import
                                val destFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                Log.d(TAG, "Temp database path: ${destFile.absolutePath}")

                                destFile.parentFile?.apply {
                                    if (!exists()) {
                                        Log.d(TAG, "Creating database directory: $absolutePath")
                                        mkdirs()
                                    }
                                }

                                try {
                                    FileOutputStream(destFile).use { outputStream ->
                                        val bytesCopied = zipInputStream.copyTo(outputStream)
                                        Log.d(TAG, "Copied $bytesCopied bytes to temp database file")
                                    }

                                    // Check if file was created and has content
                                    if (!destFile.exists()) {
                                        Log.e(TAG, "Temp database file was not created!")
                                        throw Exception("Failed to create temp database file")
                                    }

                                    val fileSize = destFile.length()
                                    Log.d(TAG, "Temp database file size: $fileSize bytes")

                                    if (fileSize == 0L) {
                                        Log.e(TAG, "Temp database file is empty!")
                                        throw Exception("Database file is empty")
                                    }

                                    // First, try to detect if this is a native Harmony backup
                                    Log.d(TAG, "Attempting to validate database...")
                                    val isHarmonyBackup = try {
                                        val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                        Log.d(TAG, "Successfully created test database instance")

                                        val integrityOk = t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                        Log.d(TAG, "Database integrity check: $integrityOk")

                                        t.close()
                                        integrityOk
                                    } catch (e: Exception) {
                                        Log.i(TAG, "DB validation failed - checking if this is a fork backup")
                                        Log.d(TAG, "Validation error details:", e)
                                        false
                                    }

                                    if (isHarmonyBackup) {
                                        Log.i(TAG, "Found valid Harmony database, proceeding with native restore")

                                        runBlocking(Dispatchers.IO) {
                                            database.checkpoint()
                                        }
                                        database.close()
                                        Log.d(TAG, "Main database closed for restore")

                                        destFile.inputStream().use { fileInputStream ->
                                            FileOutputStream(database.openHelper.writableDatabase.path).use { outputStream ->
                                                fileInputStream.copyTo(outputStream)
                                                Log.d(TAG, "Database restored to main location")
                                            }
                                        }

                                        importSuccessful = true
                                        Log.i(TAG, "Native Harmony restore completed successfully")
                                    } else {
                                        Log.i(TAG, "Database appears to be from another fork, attempting fork migration")

                                        // Check if database file is readable
                                        if (!destFile.canRead()) {
                                            Log.e(TAG, "Temp database file is not readable!")
                                            throw Exception("Cannot read imported database file")
                                        }

                                        // Try to open and analyze the fork database
                                        try {
                                            Log.d(TAG, "Attempting to open fork database for analysis...")
                                            SQLiteDatabase.openDatabase(
                                                destFile.absolutePath,
                                                null,
                                                SQLiteDatabase.OPEN_READONLY
                                            ).use { db ->
                                                Log.d(TAG, "Successfully opened fork database")

                                                // Check database version
                                                val versionCursor = db.rawQuery("PRAGMA user_version", null)
                                                if (versionCursor.moveToFirst()) {
                                                    val version = versionCursor.getInt(0)
                                                    Log.d(TAG, "Fork database version: $version")
                                                }
                                                versionCursor.close()

                                                // List all tables
                                                val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                                                Log.d(TAG, "Fork database tables:")
                                                while (cursor.moveToNext()) {
                                                    val tableName = cursor.getString(0)
                                                    Log.d(TAG, "  - $tableName")

                                                    // Count rows for important tables
                                                    if (tableName in listOf("songs", "playlists", "favorites")) {
                                                        try {
                                                            val countCursor = db.rawQuery("SELECT COUNT(*) FROM $tableName", null)
                                                            if (countCursor.moveToFirst()) {
                                                                Log.d(TAG, "    Count: ${countCursor.getInt(0)} rows")
                                                            }
                                                            countCursor.close()
                                                        } catch (e: Exception) {
                                                            Log.d(TAG, "    Could not count rows: ${e.message}")
                                                        }
                                                    }
                                                }
                                                cursor.close()
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Could not analyze fork database structure", e)
                                        }

                                        // Don't close the main database for fork imports
                                        // The migration will handle transactions properly
                                        val migrationViewModel = CrossForkMigrationViewModel(context, database)
                                        val destUri = Uri.fromFile(destFile)
                                        Log.d(TAG, "Starting fork migration with URI: $destUri")

                                        val result = runBlocking(Dispatchers.IO) {
                                            Log.d(TAG, "Running migration in IO dispatcher...")
                                            migrationViewModel.importFromOtherFork(
                                                destUri,
                                                CrossForkMigrationViewModel.ImportOptions(
                                                    importSongs = true,
                                                    importPlaylists = true,
                                                    importFavorites = true,
                                                    generateNewIds = false,
                                                    overwriteExisting = false
                                                )
                                            )
                                        }

                                        result.fold(
                                            onSuccess = { res ->
                                                Log.i(TAG, "Successfully imported from fork: $res")
                                                Log.d(TAG, "Import details:")
                                                Log.d(TAG, "  - Songs imported: ${res.songsImported}")
                                                Log.d(TAG, "  - Playlists imported: ${res.playlistsImported}")
                                                Log.d(TAG, "  - Favorites imported: ${res.favoritesImported}")
                                                importResult = res
                                                importSuccessful = true
                                            },
                                            onFailure = { e ->
                                                Log.e(TAG, "Failed to import fork database", e)
                                                Log.e(TAG, "Migration error type: ${e.javaClass.simpleName}")
                                                Log.e(TAG, "Migration error message: ${e.message}")

                                                // Log stack trace for better debugging
                                                e.printStackTrace()
                                                throw e
                                            }
                                        )
                                    }

                                    // Clean up temp file
                                    try {
                                        if (destFile.delete()) {
                                            Log.d(TAG, "Temp database file deleted successfully")
                                        } else {
                                            Log.w(TAG, "Failed to delete temp database file")
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to delete temp file", e)
                                    }

                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing database entry", e)
                                    throw e
                                }
                            }

                            else -> {
                                Log.w(TAG, "Unknown ZIP entry ignored: ${entry.name}")
                            }
                        }
                        entry = zipInputStream.nextEntry
                    }

                    if (entriesFound == 0) {
                        Log.w(TAG, "ZIP file is empty or contains no entries")
                    }
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream from URI")
                throw Exception("Cannot open backup file")
            }

            if (settingsRestored) {
                clearImportedSessionCredentials("import")
            }

            // Only restart if import was successful
            if (importSuccessful) {
                val message = if (importResult != null) {
                    "Imported: ${importResult!!.songsImported} songs, ${importResult!!.playlistsImported} playlists"
                } else {
                    context.getString(R.string.backup_create_success)
                }

                Log.i(TAG, "Import successful: $message")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                createImportCacheMarker("import")
                logAuthSettingsSnapshot("after_import_before_restart")

                // Give time for toast to show and transactions to complete
                Log.d(TAG, "Waiting before restart...")
                Thread.sleep(1500)

                val stopIntent = Intent(context, MusicService::class.java)
                context.stopService(stopIntent)
                val startIntent = Intent(context, MainActivity::class.java)
                startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(startIntent)
                exitProcess(0)
            } else {
                Log.w(TAG, "Import was not successful")
            }
        }.onFailure {
            Log.e(TAG, "=== IMPORT PROCESS FAILED ===")
            Log.e(TAG, "Error type: ${it.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${it.message}")

            // Additional error analysis
            when {
                it.message?.contains("no such table", ignoreCase = true) == true -> {
                    Log.e(TAG, "Database schema mismatch - tables missing")
                }
                it.message?.contains("corrupt", ignoreCase = true) == true -> {
                    Log.e(TAG, "Database file appears to be corrupt")
                }
                it.message?.contains("not a database", ignoreCase = true) == true -> {
                    Log.e(TAG, "File is not a valid SQLite database")
                }
                it.message?.contains("disk I/O error", ignoreCase = true) == true -> {
                    Log.e(TAG, "Disk I/O error - check file permissions")
                }
                it.message?.contains("permission denied", ignoreCase = true) == true -> {
                    Log.e(TAG, "Permission denied - check storage permissions")
                }
            }

            reportException(it)
            Log.e(TAG, "Import failed with exception", it)
            Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
        }

        Log.d(TAG, "=== IMPORT PROCESS COMPLETED ===")
    }

    private fun clearImportedSessionCredentials(source: String) {
        runCatching {
            runBlocking {
                context.dataStore.edit { settings ->
                    settings.remove(InnerTubeCookieKey)
                    settings.remove(VisitorDataKey)
                    settings.remove(DataSyncIdKey)
                    settings.remove(AccountNameKey)
                    settings.remove(AccountEmailKey)
                    settings.remove(AccountChannelHandleKey)
                }
            }
            Log.i(TAG, "Cleared imported session credentials after $source")
        }.onFailure { e ->
            Log.e(TAG, "Failed to clear imported session credentials after $source", e)
        }
    }

    private fun createImportCacheMarker(source: String) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val markerFile = File(context.cacheDir, ".import_completed_clear_cache")
            markerFile.writeText(timestamp.toString())
            Log.i(
                TAG,
                "Created import marker for MusicService cache cleanup. source=$source, timestamp=$timestamp"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to create import cache marker ($source): ${e.message}", e)
        }
    }

    private fun logAuthSettingsSnapshot(stage: String) {
        runCatching {
            val settings = runBlocking { context.dataStore.data.first() }
            val cookie = settings[InnerTubeCookieKey].orEmpty()
            val visitorData = settings[VisitorDataKey].orEmpty()
            val dataSyncId = settings[DataSyncIdKey].orEmpty()
            val useLoginForBrowse = settings[UseLoginForBrowse] != false
            val hasSapisid = runCatching { "SAPISID" in parseCookieString(cookie) }
                .getOrDefault(false)

            Log.i(
                TAG,
                "AUTH SNAPSHOT [$stage] useLoginForBrowse=$useLoginForBrowse, " +
                        "cookieChars=${cookie.length}, hasSapisid=$hasSapisid, " +
                        "visitorDataChars=${visitorData.length}, dataSyncIdChars=${dataSyncId.length}"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to capture auth snapshot ($stage): ${e.message}", e)
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}
