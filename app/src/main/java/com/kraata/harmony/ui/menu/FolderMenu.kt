package com.kraata.harmony.ui.menu

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.LocalDatabase
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.R
import com.kraata.harmony.db.entities.Song
import com.kraata.harmony.extensions.toMediaItem
import com.kraata.harmony.models.DirectoryTree
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.ui.component.items.SongFolderItem
import com.kraata.harmony.ui.dialog.AddToPlaylistDialog
import com.kraata.harmony.ui.dialog.AddToQueueDialog
import com.kraata.harmony.utils.joinByBullet
import com.kraata.harmony.utils.lmScannerCoroutine
import com.kraata.harmony.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "FolderMenu"

@Composable
fun FolderMenu(
    folder: DirectoryTree,
    coroutineScope: CoroutineScope,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return

    // Estado mejorado con gestión de carga
    val allFolderSongs = remember { mutableStateListOf<Song>() }
    var subDirSongCount by remember { mutableIntStateOf(0) }
    var isLoadingSongs by remember { mutableStateOf(false) }

    val m3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(lmScannerCoroutine) {
                try {
                    var result = "#EXTM3U\n"
                    allFolderSongs.forEach { s ->
                        val se = s.song
                        result += "#EXTINF:${se.duration},${s.artists.joinToString(";") { it.name }} - ${s.title}\n"
                        result += if (se.isLocal) "${se.id}, ${se.localPath}" else "https://youtube.com/watch?v=${se.id}"
                        result += "\n"
                    }
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(result.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: IOException) {
                    reportException(e)
                }
            }
        }
    }

    /**
     * Obtiene todas las canciones de la carpeta de forma recursiva
     * Mejoras:
     * - Logging para debugging
     * - Validación de resultados
     * - Estado de carga
     */
    suspend fun fetchAllSongsRecursive() {
        try {
            isLoadingSongs = true
            val path = folder.getFullSquashedDir()
            Log.d(TAG, "Fetching songs from path: $path")

            val dbSongs = database.localSongsInDirDeep(path)

            Log.d(TAG, "Found ${dbSongs.size} songs in folder")
            if (dbSongs.isEmpty()) {
                Log.w(TAG, "No songs found in path: $path")
            } else {
                // Log de muestra para debugging
                dbSongs.take(3).forEach { song ->
                    Log.d(TAG, "Sample song - ID: ${song.id}, Title: ${song.title}, IsLocal: ${song.song.isLocal}")
                }
            }

            allFolderSongs.clear()
            allFolderSongs.addAll(dbSongs)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching songs from folder", e)
            reportException(e)
        } finally {
            isLoadingSongs = false
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                subDirSongCount = database.localSongCountInPath(folder.getFullPath()).first()
                Log.d(TAG, "Song count in path: $subDirSongCount")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting song count", e)
                reportException(e)
            }
        }
    }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // folder info
    SongFolderItem(
        folderTitle = folder.getSquashedDir(),
        modifier = Modifier,
        subtitle = joinByBullet(
            pluralStringResource(R.plurals.n_song, subDirSongCount, subDirSongCount),
            folder.parent
        ),
    )

    HorizontalDivider()

    // options
    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (folder.toList().isEmpty()) {
            Log.w(TAG, "Folder is empty, no actions available")
            return@GridMenu
        }

        GridMenuItem(
            icon = Icons.Rounded.PlayArrow,
            title = R.string.play
        ) {
            onDismiss()
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                if (allFolderSongs.isNotEmpty()) {
                    playerConnection.playQueue(
                        ListQueue(
                            title = folder.getSquashedDir().substringAfterLast('/'),
                            items = allFolderSongs.map { it.toMediaMetadata() },
                        )
                    )
                    Log.d(TAG, "Started playing ${allFolderSongs.size} songs")
                } else {
                    Log.w(TAG, "No songs to play")
                }
            }
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next
        ) {
            onDismiss()
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                if (allFolderSongs.isNotEmpty()) {
                    playerConnection.enqueueNext(allFolderSongs.map { it.toMediaItem() })
                    Log.d(TAG, "Enqueued ${allFolderSongs.size} songs to play next")
                } else {
                    Log.w(TAG, "No songs to enqueue")
                }
            }
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                // Cambiar al hilo principal para actualizar el UI
                withContext(Dispatchers.Main) {
                    if (allFolderSongs.isNotEmpty()) {
                        showChooseQueueDialog = true
                        Log.d(TAG, "Opening queue dialog with ${allFolderSongs.size} songs")
                    } else {
                        Log.w(TAG, "No songs loaded for queue dialog")
                    }
                }
            }
        }

        GridMenuItem(
            icon = Icons.Rounded.Shuffle,
            title = R.string.shuffle
        ) {
            onDismiss()
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                if (allFolderSongs.isNotEmpty()) {
                    playerConnection.playQueue(
                        ListQueue(
                            title = folder.currentDir.substringAfterLast('/'),
                            items = allFolderSongs.map { it.toMediaMetadata() },
                            startShuffled = true
                        )
                    )
                    Log.d(TAG, "Started shuffled playback with ${allFolderSongs.size} songs")
                } else {
                    Log.w(TAG, "No songs to shuffle")
                }
            }
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            // CORRECCIÓN CRÍTICA: Asegurar que las canciones se cargan ANTES del diálogo
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                withContext(Dispatchers.Main) {
                    if (allFolderSongs.isNotEmpty()) {
                        showChoosePlaylistDialog = true
                        Log.d(TAG, "Opening playlist dialog with ${allFolderSongs.size} songs")
                        Log.d(TAG, "Song IDs to add: ${allFolderSongs.map { it.id }}")
                    } else {
                        Log.e(TAG, "Cannot open playlist dialog - no songs loaded!")
                    }
                }
            }
        }

        GridMenuItem(
            icon = Icons.Rounded.Output,
            title = R.string.m3u_export
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                fetchAllSongsRecursive()

                if (allFolderSongs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        m3uLauncher.launch("${folder.currentDir.trim('/')}.m3u")
                        Log.d(TAG, "Launching M3U export for ${allFolderSongs.size} songs")
                    }
                } else {
                    Log.w(TAG, "No songs to export")
                }
            }
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                if (allFolderSongs.isEmpty()) {
                    Log.e(TAG, "Queue dialog opened but no songs available")
                    return@AddToQueueDialog
                }

                Log.d(TAG, "Adding ${allFolderSongs.size} songs to queue: $queueName")
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName,
                    allFolderSongs.map { it.toMediaMetadata() },
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                    Log.d(TAG, "Successfully added songs to queue")
                } ?: Log.e(TAG, "Failed to create queue")
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        val songIds = if (allFolderSongs.isEmpty()) {
            Log.e(TAG, "Playlist dialog opened but no songs available")
            emptyList()
        } else {
            allFolderSongs.map { it.id }.also { ids ->
                Log.d(TAG, "Playlist dialog showing ${ids.size} song IDs")
            }
        }

        AddToPlaylistDialog(
            navController = navController,
            songIds = songIds,
            onDismiss = {
                showChoosePlaylistDialog = false
            }
        )
    }
}