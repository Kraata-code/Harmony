package com.kraata.harmony.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kraata.harmony.MainActivity
import com.kraata.harmony.R
import com.kraata.harmony.constants.AccountChannelHandleKey
import com.kraata.harmony.constants.AccountEmailKey
import com.kraata.harmony.constants.AccountNameKey
import com.kraata.harmony.constants.DataSyncIdKey
import com.kraata.harmony.constants.InnerTubeCookieKey
import com.kraata.harmony.constants.OOBE_VERSION
import com.kraata.harmony.constants.OobeStatusKey
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
                    (context.filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered()
                        .use { inputStream ->
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
                                val destFile =
                                    context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                destFile.parentFile?.apply {
                                    if (!exists()) mkdirs()
                                }
                                FileOutputStream(destFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }

                                val status = try {
                                    val t = InternalDatabase.newTestInstance(
                                        context,
                                        InternalDatabase.TEST_DB_NAME
                                    )
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
        viewModelScope.launch {
            Log.d(TAG, "=== STARTING IMPORT PROCESS ===")

            var importSuccessful = false
            var importResult: CrossForkMigrationViewModel.ImportResult? = null
            var errorMessage: String? = null

            // 1. Todo lo pesado en segundo plano (IO)
            withContext(Dispatchers.IO) {
                runCatching {
                    logAuthSettingsSnapshot("before_import")

                    context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                        input.zipInputStream().use { zipInputStream ->
                            var entry = zipInputStream.nextEntry
                            while (entry != null) {
                                when (entry.name) {
                                    SETTINGS_FILENAME -> {
                                        Log.i(TAG, "Import mode: skipping settings file")
                                    }

                                    InternalDatabase.DB_NAME -> {
                                        val destFile =
                                            context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                        destFile.parentFile?.mkdirs()

                                        FileOutputStream(destFile).use { outputStream ->
                                            zipInputStream.copyTo(outputStream)
                                        }

                                        val isHarmonyBackup = try {
                                            val t = InternalDatabase.newTestInstance(
                                                context,
                                                InternalDatabase.TEST_DB_NAME
                                            )
                                            val integrityOk =
                                                t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                            t.close()
                                            integrityOk
                                        } catch (e: Exception) {
                                            false
                                        }

                                        if (isHarmonyBackup) {
                                            // CASO 1: RESTAURACIÓN NATIVA
                                            database.checkpoint()
                                            database.close() // Cerrar para asegurar escritura

                                            destFile.inputStream().use { fileInputStream ->
                                                FileOutputStream(database.openHelper.writableDatabase.path).use { outputStream ->
                                                    fileInputStream.copyTo(outputStream)
                                                }
                                            }
                                            importSuccessful = true
                                        } else {
                                            // CASO 2: MIGRACIÓN DE FORK
                                            val migrationViewModel =
                                                CrossForkMigrationViewModel(context, database)
                                            val destUri = Uri.fromFile(destFile)

                                            val result = migrationViewModel.importFromOtherFork(
                                                destUri,
                                                CrossForkMigrationViewModel.ImportOptions(
                                                    importSongs = true,
                                                    importPlaylists = true,
                                                    importFavorites = true,
                                                    generateNewIds = false,
                                                    overwriteExisting = false
                                                )
                                            )

                                            result.fold(
                                                onSuccess = { res ->
                                                    importResult = res
                                                    importSuccessful = true
                                                    // CORRECCIÓN CRÍTICA: Cerrar la BD también en migración
                                                    // para asegurar que se liberen los locks antes de reiniciar
                                                    try {
                                                        database.checkpoint()
                                                        database.close()
                                                        Log.d(
                                                            TAG,
                                                            "Database closed after fork migration"
                                                        )
                                                    } catch (e: Exception) {
                                                        Log.e(
                                                            TAG,
                                                            "Error closing DB after migration",
                                                            e
                                                        )
                                                    }
                                                },
                                                onFailure = { e ->
                                                    throw e
                                                }
                                            )
                                        }
                                        // Limpieza
                                        if (destFile.exists()) destFile.delete()
                                    }
                                }
                                entry = zipInputStream.nextEntry
                            }
                        }
                    } ?: throw Exception("Cannot open backup file")
                }.onFailure {
                    errorMessage = it.message
                    reportException(it)
                }
            } // Fin IO

            // 2. UI y Reinicio (Hilo Principal)
            if (importSuccessful) {
                val message = if (importResult != null) {
                    "Imported: ${importResult!!.songsImported} songs, ${importResult!!.playlistsImported} playlists"
                } else {
                    context.getString(R.string.backup_create_success)
                }

                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                markWizardAsCompletedAfterImport()
                createImportCacheMarker("import")

                // Espera breve para que el usuario vea el mensaje
                delay(1500)

                // --- FORZAR REINICIO SEGURO ---
                // 1. Detener el servicio de música explícitamente
                try {
                    val stopIntent = Intent(context, MusicService::class.java)
                    context.stopService(stopIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping service", e)
                }

                // 2. Crear intent de reinicio limpio
                val restartIntent =
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        ?.putExtra(
                            "FROM_IMPORT",
                            true
                        ) // Opcional: flag para saber que viene de un import

                // 3. Ejecutar reinicio
                if (restartIntent != null) {
                    // makeRestartActivityTask asegura que la pila de actividades anterior se destruya
                    context.startActivity(restartIntent)
                }

                // 4. Matar el proceso actual
                exitProcess(0)

            } else if (errorMessage != null) {
                Toast.makeText(context, "Import failed: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun markWizardAsCompletedAfterImport() {
        runCatching {
            runBlocking {
                context.dataStore.edit { settings ->
                    settings[OobeStatusKey] = OOBE_VERSION
                }
            }
            Log.i(TAG, "Marked setup wizard as completed after import (oobeStatus=$OOBE_VERSION)")
        }.onFailure { e ->
            Log.w(TAG, "Failed to mark setup wizard as completed after import: ${e.message}", e)
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
