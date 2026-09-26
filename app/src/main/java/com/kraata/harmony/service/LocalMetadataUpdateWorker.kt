package com.kraata.harmony.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kraata.harmony.MainActivity
import com.kraata.harmony.R
import com.kraata.harmony.constants.LastLocalScanKey
import com.kraata.harmony.constants.LocalMetadataUpdateErrorsKey
import com.kraata.harmony.constants.LocalMetadataUpdateFolderKey
import com.kraata.harmony.constants.LocalMetadataUpdateLastCompletedSongIdKey
import com.kraata.harmony.constants.LocalMetadataUpdateLowConfidenceKey
import com.kraata.harmony.constants.LocalMetadataUpdateNoMatchKey
import com.kraata.harmony.constants.LocalMetadataUpdatePendingErrorsKey
import com.kraata.harmony.constants.LocalMetadataUpdatePendingFolderKey
import com.kraata.harmony.constants.LocalMetadataUpdatePendingLowConfidenceKey
import com.kraata.harmony.constants.LocalMetadataUpdatePendingNoMatchKey
import com.kraata.harmony.constants.LocalMetadataUpdatePendingUpdatedKey
import com.kraata.harmony.constants.LocalMetadataUpdateProcessedKey
import com.kraata.harmony.constants.LocalMetadataUpdateUpdatedKey
import com.kraata.harmony.db.InternalDatabase
import com.kraata.harmony.db.entities.Song
import com.kraata.harmony.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class LocalMetadataUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(
            processed = 0,
            total = 0,
        )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())

        val folderPath = inputData.getString(INPUT_FOLDER_PATH)
            ?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure(workDataOf(ERROR_MESSAGE to "Missing folder path"))

        val database = InternalDatabase.newInstance(applicationContext)
        try {
            val songs = database.localSongsInDirDeep(folderPath)
            val checkpoint = loadCheckpoint(folderPath, songs)
            var processed = checkpoint.processed
            var updated = checkpoint.updated
            var noMatch = checkpoint.noMatch
            var lowConfidence = checkpoint.lowConfidence
            var errors = checkpoint.errors
            val updater = LocalSongMetadataUpdater(
                database = database,
                context = applicationContext,
            )

            publishProgress(processed, songs.size, updated, noMatch, lowConfidence, errors)

            for (index in checkpoint.nextIndex until songs.size) {
                val song = songs[index]
                currentCoroutineContext().ensureActive()
                try {
                    when (updater.update(song)) {
                        LocalSongMetadataUpdateResult.NoMatch -> noMatch++
                        is LocalSongMetadataUpdateResult.LowConfidence -> lowConfidence++
                        is LocalSongMetadataUpdateResult.Updated -> updated++
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    errors++
                }

                processed++
                saveCheckpoint(folderPath, song.id, processed, updated, noMatch, lowConfidence, errors)
                publishProgress(processed, songs.size, updated, noMatch, lowConfidence, errors)
            }

            clearCheckpoint()
            applicationContext.dataStore.edit { preferences ->
                preferences[LastLocalScanKey] = System.currentTimeMillis()
            }
            val output = countsData(processed, songs.size, updated, noMatch, lowConfidence, errors)
            savePendingSummary(folderPath, updated, noMatch, lowConfidence, errors)
            notifySummary(folderPath, updated, noMatch, lowConfidence, errors)
            Result.success(output)
        } finally {
            database.close()
        }
    }

    private suspend fun loadCheckpoint(folderPath: String, songs: List<Song>): Checkpoint {
        val preferences = applicationContext.dataStore.data.first()
        if (preferences[LocalMetadataUpdateFolderKey] != folderPath) return Checkpoint()

        val lastSongId = preferences[LocalMetadataUpdateLastCompletedSongIdKey] ?: return Checkpoint()
        val lastIndex = songs.indexOfFirst { it.id == lastSongId }
        if (lastIndex < 0) return Checkpoint()

        return Checkpoint(
            nextIndex = lastIndex + 1,
            processed = preferences[LocalMetadataUpdateProcessedKey].toCount(songs.size),
            updated = preferences[LocalMetadataUpdateUpdatedKey].toCount(),
            noMatch = preferences[LocalMetadataUpdateNoMatchKey].toCount(),
            lowConfidence = preferences[LocalMetadataUpdateLowConfidenceKey].toCount(),
            errors = preferences[LocalMetadataUpdateErrorsKey].toCount(),
        )
    }

    private suspend fun saveCheckpoint(
        folderPath: String,
        songId: String,
        processed: Int,
        updated: Int,
        noMatch: Int,
        lowConfidence: Int,
        errors: Int,
    ) {
        applicationContext.dataStore.edit { preferences ->
            preferences[LocalMetadataUpdateFolderKey] = folderPath
            preferences[LocalMetadataUpdateLastCompletedSongIdKey] = songId
            preferences[LocalMetadataUpdateProcessedKey] = processed.toLong()
            preferences[LocalMetadataUpdateUpdatedKey] = updated.toLong()
            preferences[LocalMetadataUpdateNoMatchKey] = noMatch.toLong()
            preferences[LocalMetadataUpdateLowConfidenceKey] = lowConfidence.toLong()
            preferences[LocalMetadataUpdateErrorsKey] = errors.toLong()
        }
    }

    private suspend fun clearCheckpoint() {
        applicationContext.dataStore.edit { preferences ->
            preferences.remove(LocalMetadataUpdateFolderKey)
            preferences.remove(LocalMetadataUpdateLastCompletedSongIdKey)
            preferences.remove(LocalMetadataUpdateProcessedKey)
            preferences.remove(LocalMetadataUpdateUpdatedKey)
            preferences.remove(LocalMetadataUpdateNoMatchKey)
            preferences.remove(LocalMetadataUpdateLowConfidenceKey)
            preferences.remove(LocalMetadataUpdateErrorsKey)
        }
    }

    private suspend fun savePendingSummary(
        folderPath: String,
        updated: Int,
        noMatch: Int,
        lowConfidence: Int,
        errors: Int,
    ) {
        applicationContext.dataStore.edit { preferences ->
            preferences[LocalMetadataUpdatePendingFolderKey] = folderPath
            preferences[LocalMetadataUpdatePendingUpdatedKey] = updated
            preferences[LocalMetadataUpdatePendingNoMatchKey] = noMatch
            preferences[LocalMetadataUpdatePendingLowConfidenceKey] = lowConfidence
            preferences[LocalMetadataUpdatePendingErrorsKey] = errors
        }
    }

    private suspend fun publishProgress(
        processed: Int,
        total: Int,
        updated: Int,
        noMatch: Int,
        lowConfidence: Int,
        errors: Int,
    ) {
        setProgress(countsData(processed, total, updated, noMatch, lowConfidence, errors))
        setForeground(createForegroundInfo(processed, total))
    }

    @SuppressLint("SpecifyForegroundServiceType")
    private fun createForegroundInfo(
        processed: Int,
        total: Int,
    ): ForegroundInfo {
        val notificationManager = notificationManager()
        createNotificationChannel(notificationManager)
        val notification = notificationBuilder()
            .setContentTitle(applicationContext.getString(R.string.local_metadata_update_title))
            .setContentText(
                applicationContext.getString(
                    R.string.local_metadata_update_progress,
                    processed,
                    total,
                )
            )
            .setSmallIcon(R.drawable.download_metadata)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, processed.coerceIn(0, total), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifySummary(
        folderPath: String,
        updated: Int,
        noMatch: Int,
        lowConfidence: Int,
        errors: Int,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            val notificationManager = notificationManager()
            createNotificationChannel(notificationManager)
            val contentIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(INPUT_FOLDER_PATH, folderPath)
                putExtra(UPDATED_COUNT, updated)
                putExtra(NO_MATCH_COUNT, noMatch)
                putExtra(LOW_CONFIDENCE_COUNT, lowConfidence)
                putExtra(ERROR_COUNT, errors)
            }
            notificationManager.notify(
                SUMMARY_NOTIFICATION_ID,
                notificationBuilder()
                    .setContentTitle(applicationContext.getString(R.string.local_metadata_update_complete))
                    .setContentText(
                        applicationContext.getString(
                            R.string.local_metadata_update_summary,
                            updated,
                            noMatch,
                            lowConfidence,
                            errors,
                        )
                    )
                    .setSmallIcon(R.drawable.download_metadata)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            applicationContext,
                            SUMMARY_NOTIFICATION_ID,
                            contentIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            Notification.Builder(applicationContext)
        }

    private fun notificationManager(): NotificationManager =
        requireNotNull(applicationContext.getSystemService(NotificationManager::class.java))

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.local_metadata_update_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun countsData(
        processed: Int,
        total: Int,
        updated: Int,
        noMatch: Int,
        lowConfidence: Int,
        errors: Int,
    ) = workDataOf(
        PROCESSED_COUNT to processed,
        TOTAL_COUNT to total,
        UPDATED_COUNT to updated,
        NO_MATCH_COUNT to noMatch,
        LOW_CONFIDENCE_COUNT to lowConfidence,
        ERROR_COUNT to errors,
    )

    private data class Checkpoint(
        val nextIndex: Int = 0,
        val processed: Int = 0,
        val updated: Int = 0,
        val noMatch: Int = 0,
        val lowConfidence: Int = 0,
        val errors: Int = 0,
    )

    data class Summary(
        val folderPath: String,
        val updated: Int,
        val noMatch: Int,
        val lowConfidence: Int,
        val errors: Int,
    )

    companion object {
        const val UNIQUE_WORK_NAME = "local_metadata_update"
        const val ACTION_COMPLETE = "com.kraata.harmony.action.LOCAL_METADATA_UPDATE_COMPLETE"
        const val INPUT_FOLDER_PATH = "folder_path"
        const val PROCESSED_COUNT = "processed_count"
        const val TOTAL_COUNT = "total_count"
        const val UPDATED_COUNT = "updated_count"
        const val NO_MATCH_COUNT = "no_match_count"
        const val LOW_CONFIDENCE_COUNT = "low_confidence_count"
        const val ERROR_COUNT = "error_count"
        const val ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "local_metadata_updates"
        private const val NOTIFICATION_ID = 4101
        private const val SUMMARY_NOTIFICATION_ID = 4102
    }
}

private fun Long?.toCount(max: Int = Int.MAX_VALUE): Int =
    (this ?: 0L).coerceIn(0L, max.toLong()).toInt()
