/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.menu

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastSumBy
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import com.kraata.harmony.BuildConfig
import com.kraata.harmony.LocalDatabase
import com.kraata.harmony.LocalDownloadUtil
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.LocalSyncUtils
import com.kraata.harmony.R
import com.kraata.harmony.constants.ListThumbnailSize
import com.kraata.harmony.constants.SyncMode
import com.kraata.harmony.constants.ThumbnailCornerRadius
import com.kraata.harmony.constants.YtmSyncModeKey
import com.kraata.harmony.db.entities.Event
import com.kraata.harmony.db.entities.Playlist
import com.kraata.harmony.db.entities.PlaylistSong
import com.kraata.harmony.db.entities.Song
import com.kraata.harmony.extensions.toMediaItem
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.ExoDownloadService
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.playback.queues.YouTubeQueue
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.component.items.ListItem
import com.kraata.harmony.ui.dialog.AddToPlaylistDialog
import com.kraata.harmony.ui.dialog.AddToQueueDialog
import com.kraata.harmony.ui.dialog.ArtistDialog
import com.kraata.harmony.ui.dialog.DefaultDialog
import com.kraata.harmony.ui.dialog.DetailsDialog
import com.kraata.harmony.ui.dialog.TextFieldDialog
import com.kraata.harmony.utils.joinByBullet
import com.kraata.harmony.utils.makeTimeString
import com.kraata.harmony.utils.rememberEnumPreference
import com.kraata.harmony.utils.syncCoroutine
import com.kraata.harmony.service.LocalSongMetadataUpdateResult
import com.kraata.harmony.service.LocalSongMetadataUpdater
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private const val TAG = "SongMenu"
// ponytail: cap compressed cover input at 16 MiB; stream/decode incrementally if larger artwork is needed.
private const val MAX_ARTWORK_BYTES = 16 * 1024 * 1024

@Composable
fun SongMenu(
    originalSong: Song,
    playlistSong: PlaylistSong? = null,
    playlist: Playlist? = null,
    event: Event? = null,
    navController: NavController,
    onDismiss: () -> Unit,
    onLocalMetadataUpdated: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val downloadUtil = LocalDownloadUtil.current
    val clipboardManager = LocalClipboard.current
    val syncUtils = LocalSyncUtils.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    val liveSong by database.song(originalSong.id).collectAsState(initial = originalSong)
    val song = liveSong ?: originalSong
    val download by LocalDownloadUtil.current.getDownload(originalSong.id).collectAsState(initial = null)
    val coroutineScope =
        CoroutineScope(syncCoroutine) // rememberCoroutineScope has exception "rememberCoroutineScope left the composition"
    val fileRecognitionScope = rememberCoroutineScope()
    val localSongMetadataUpdater = remember(database, context) {
        LocalSongMetadataUpdater(database, context)
    }
    var fileRecognitionMessage by remember { mutableStateOf<String?>(null) }
    var fileRecognitionRunning by remember { mutableStateOf(false) }
    var fileRecognitionJob by remember { mutableStateOf<Job?>(null) }

    val currentFormatState = database.format(originalSong.id).collectAsState(initial = null)
    val currentFormat = currentFormatState.value

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showManualMetadataDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var manualArtwork by remember { mutableStateOf<ByteArray?>(null) }
    var manualArtworkChanged by remember { mutableStateOf(false) }
    var manualArtworkLoading by remember { mutableStateOf(false) }
    var manualEditRunning by remember { mutableStateOf(false) }
    var manualEditError by remember { mutableStateOf<String?>(null) }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showDetailsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val artworkPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        manualArtworkLoading = true
        fileRecognitionScope.launch {
            try {
                Log.d(
                    TAG,
                    "cover_select scheme=${uri.scheme ?: "unknown"} " +
                        "resolverMime=${context.contentResolver.getType(uri) ?: "unknown"}",
                )
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        Log.d(TAG, "cover_stream_open result=opened")
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_ARTWORK_BYTES) {
                                "Cover art exceeds 16 MiB"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: run {
                        Log.d(TAG, "cover_stream_open result=null")
                        null
                    }
                }
                require(bytes != null && bytes.isNotEmpty()) { "Selected cover art is empty" }
                Log.d(TAG, "cover_stream_read bytes=${bytes.size}")
                manualArtwork = bytes
                manualArtworkChanged = true
                manualEditError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "cover_stream_error exceptionClass=${error::class.java.name}")
                manualEditError = resources.getString(
                    R.string.local_metadata_cover_error,
                    error.message ?: "Unknown error",
                )
            } finally {
                manualArtworkLoading = false
            }
        }
    }

    ListItem(
        title = song.song.title,
        subtitle = joinByBullet(
            song.artists.joinToString { it.name },
            makeTimeString(song.song.duration * 1000L)
        ),
        thumbnailContent = {
            val px = (ListThumbnailSize.value * density.density).roundToInt()
            AsyncImage(
                model = song.song.getThumbnailModel(px, px),
                contentDescription = null,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
            )
        },
        trailingContent = {
            IconButton(
                onClick = {
                    val s = song.song.toggleLike()
                    database.query {
                        update(s)
                    }

                    if (!s.isLocal) {
                        syncUtils.likeSong(s)
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (song.song.liked) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (song.song.liked) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null
                )
            }
        }
    )

    HorizontalDivider()

    val localPath = song.song.localPath
    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (!song.song.isLocal)
            GridMenuItem(
                icon = Icons.Rounded.Radio,
                title = R.string.start_radio
            ) {
                onDismiss()
                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()), isRadio = true)
            }

        GridMenuItem(
            icon = Icons.Rounded.PlayArrow,
            title = R.string.play
        ) {
            playerConnection.playQueue(
                queue = ListQueue(
                    title = song.title,
                    items = listOf(song.toMediaMetadata())
                )
            )
            onDismiss()
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next
        ) {
            onDismiss()
            playerConnection.enqueueNext(song.toMediaItem())
        }
        GridMenuItem(
            icon = Icons.Rounded.Edit,
            title = if (song.song.isLocal && !localPath.isNullOrBlank()) {
                R.string.edit_local_metadata
            } else {
                R.string.edit
            },
        ) {
            if (song.song.isLocal && !localPath.isNullOrBlank()) {
                manualArtwork = null
                manualArtworkChanged = false
                manualEditError = null
                showManualMetadataDialog = true
            } else {
                showEditDialog = true
            }
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }

        if (BuildConfig.DEBUG && song.song.isLocal && !localPath.isNullOrBlank()) {
            GridMenuItem(
                icon = Icons.Rounded.Search,
                title = R.string.acoustid_test,
                enabled = !fileRecognitionRunning,
            ) {
                fileRecognitionMessage = resources.getString(R.string.acoustid_test_processing)
                fileRecognitionRunning = true
                fileRecognitionJob = fileRecognitionScope.launch {
                    try {
                        when (val update = localSongMetadataUpdater.update(song)) {
                            LocalSongMetadataUpdateResult.NoMatch -> {
                                fileRecognitionMessage = resources.getString(R.string.acoustid_test_no_match)
                            }

                            is LocalSongMetadataUpdateResult.LowConfidence -> {
                                fileRecognitionMessage = resources.getString(
                                    R.string.acoustid_test_low_confidence,
                                    (update.score * 100).roundToInt(),
                                )
                            }

                            is LocalSongMetadataUpdateResult.Updated -> {
                                context.imageLoader.memoryCache?.clear()
                                onLocalMetadataUpdated()
                                fileRecognitionMessage = resources.getString(
                                    R.string.acoustid_test_match,
                                    update.artist,
                                    update.title,
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        fileRecognitionMessage = resources.getString(
                            R.string.acoustid_test_error,
                            error.message ?: "Unknown error",
                        )
                    } finally {
                        fileRecognitionRunning = false
                        fileRecognitionJob = null
                    }
                }
            }
        }

        if (playlistSong != null && (playlist?.playlist?.isLocal == true
                    || (playlistSong.song.song.isLocal || syncMode == SyncMode.RW))
        ) {
            GridMenuItem(
                icon = Icons.Rounded.PlaylistRemove,
                title = R.string.remove_from_playlist
            ) {
                database.transaction {
                    move(playlistSong.map.playlistId, playlistSong.map.position, Int.MAX_VALUE)
                    delete(playlistSong.map.copy(position = Int.MAX_VALUE))
                }

                coroutineScope.launch {
                    playlist?.playlist?.browseId?.let { playlistId ->
                        if (playlistSong.map.setVideoId != null) {
                            YouTube.removeFromPlaylist(
                                playlistId, playlistSong.map.songId, playlistSong.map.setVideoId
                            )
                        }
                    }
                }

                onDismiss()
            }
        }

        if (!song.song.isLocal)
            DownloadGridMenu(
                localDateTime = download,
                onDownload = {
                    downloadUtil.download(song.toMediaMetadata())
                },
                onRemoveDownload = {
                    if (song.song.localPath != null) {
                        downloadUtil.delete(song)
                    } else {
                        DownloadService.sendRemoveDownload(
                            context,
                            ExoDownloadService::class.java,
                            song.id,
                            false
                        )
                    }
                }
            )


        GridMenuItem(
            icon = R.drawable.artist,
            title = R.string.view_artist
        ) {
            if (song.artists.size == 1) {
                navController.navigate("artist/${song.artists[0].id}")
                onDismiss()
            } else {
                showSelectArtistDialog = true
            }
        }
        if (song.song.albumId != null && !song.song.isLocal) {
            GridMenuItem(
                icon = Icons.Rounded.Album,
                title = R.string.view_album
            ) {
                onDismiss()
                navController.navigate("album/${song.song.albumId}")
            }
        }
        if (!song.song.isLocal)
            GridMenuItem(
                icon = Icons.Rounded.Share,
                title = R.string.share
            ) {
                onDismiss()
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        GridMenuItem(
            icon = Icons.Rounded.Info,
            title = R.string.details
        ) {
            showDetailsDialog = true
        }
        if (!song.song.isLocal) {
            if (song.song.inLibrary == null) {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAdd,
                    title = R.string.add_to_library
                ) {
                    database.query {
                        update(song.song.toggleLibrary())
                    }
                }
            } else {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAddCheck,
                    title = R.string.remove_from_library
                ) {
                    database.query {
                        update(song.song.toggleLibrary())
                    }
                }
            }
        }
        if (event != null) {
            GridMenuItem(
                icon = Icons.Rounded.Delete,
                title = R.string.remove_from_history
            ) {
                onDismiss()
                database.query {
                    delete(event)
                }
            }
        }
    }

    fileRecognitionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                if (fileRecognitionRunning) fileRecognitionJob?.cancel()
                fileRecognitionMessage = null
            },
            title = { Text(stringResource(R.string.acoustid_test_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (fileRecognitionRunning) {
                        CircularProgressIndicator()
                    }
                    Text(text = message)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (fileRecognitionRunning) fileRecognitionJob?.cancel()
                        fileRecognitionMessage = null
                    },
                ) {
                    Text(stringResource(if (fileRecognitionRunning) android.R.string.cancel else android.R.string.ok))
                }
            },
        )
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showEditDialog) {
        TextFieldDialog(
            icon = { Icon(imageVector = Icons.Rounded.Edit, contentDescription = null) },
            title = { Text(text = stringResource(R.string.edit_song)) },
            onDismiss = { showEditDialog = false },
            initialTextFieldValue = TextFieldValue(song.song.title, TextRange(song.song.title.length)),
            onDone = { title ->
                onDismiss()
                database.query {
                    update(song.song.copy(title = title))
                }
            }
        )
    }

    if (showManualMetadataDialog) {
        EditLocalMetadataDialog(
            initialTitle = song.song.title,
            initialArtist = song.artists.joinToString(", ") { it.name },
            currentArtwork = song.song.getThumbnailModel(),
            artwork = manualArtwork,
            artworkChanged = manualArtworkChanged,
            artworkLoading = manualArtworkLoading,
            saving = manualEditRunning,
            errorMessage = manualEditError,
            onPickArtwork = { artworkPickerLauncher.launch(arrayOf("image/*")) },
            onRemoveArtwork = {
                manualArtwork = null
                manualArtworkChanged = true
                manualEditError = null
            },
            onDismiss = { if (!manualEditRunning && !manualArtworkLoading) showManualMetadataDialog = false },
            onSave = { title, artist ->
                manualEditRunning = true
                manualEditError = null
                fileRecognitionScope.launch {
                    try {
                        localSongMetadataUpdater.updateManually(
                            song = song,
                            title = title,
                            artist = artist,
                            artworkBytes = manualArtwork.takeIf { manualArtworkChanged },
                            removeArtwork = manualArtworkChanged && manualArtwork == null,
                        )
                        context.imageLoader.memoryCache?.clear()
                        onLocalMetadataUpdated()
                        showManualMetadataDialog = false
                        onDismiss()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        manualEditError = resources.getString(
                            R.string.local_metadata_edit_error,
                            error.message ?: "Unknown error",
                        )
                    } finally {
                        manualEditRunning = false
                    }
                }
            },
        )
    }

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName, listOf(song.toMediaMetadata()),
                    forceInsert = true, delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = listOf(song.id),
            onPreAdd = { playlist ->
                playlist.playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                }
                listOf(song.id)
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showSelectArtistDialog) {
        ArtistDialog(
            navController = navController,
            artists = song.artists,
            onDismiss = { showSelectArtistDialog = false }
        )
    }

    if (showDetailsDialog) {
        DetailsDialog(
            mediaMetadata = song.toMediaMetadata(),
            currentFormat = currentFormat,
            currentPlayCount = song.playCount?.fastSumBy { it.count } ?: 0,
            clipboardManager = clipboardManager,
            setVisibility = { showDetailsDialog = it }
        )
    }
}

@Composable
private fun EditLocalMetadataDialog(
    initialTitle: String,
    initialArtist: String,
    currentArtwork: Any?,
    artwork: ByteArray?,
    artworkChanged: Boolean,
    artworkLoading: Boolean,
    saving: Boolean,
    errorMessage: String?,
    onPickArtwork: () -> Unit,
    onRemoveArtwork: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String) -> Unit,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var artist by rememberSaveable(initialArtist) { mutableStateOf(initialArtist) }
    val canSave = !saving && !artworkLoading && title.isNotBlank() && artist.isNotBlank()

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text(stringResource(R.string.edit_local_metadata)) },
        buttons = {
            TextButton(
                enabled = !saving && !artworkLoading,
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = canSave,
                onClick = { onSave(title.trim(), artist.trim()) },
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.song_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text(stringResource(R.string.song_artists)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (artworkChanged && artwork == null) {
                Text(
                    text = stringResource(R.string.local_metadata_no_cover),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (artwork != null || currentArtwork != null) {
                AsyncImage(
                    model = if (artworkChanged) artwork else currentArtwork,
                    contentDescription = stringResource(R.string.local_metadata_cover),
                    contentScale = ContentScale.Crop,
                    onLoading = {
                        Log.d(
                            TAG,
                            "cover_image state=loading surface=manual_preview " +
                                "source=${if (artworkChanged) "selected_bytes" else "local_file"} " +
                                "bytes=${artwork?.size ?: 0}",
                        )
                    },
                    onSuccess = {
                        Log.d(TAG, "cover_image state=success surface=manual_preview")
                    },
                    onError = {
                        Log.e(TAG, "cover_image state=error surface=manual_preview")
                    },
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                        .align(Alignment.CenterHorizontally),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    enabled = !saving && !artworkLoading,
                    onClick = onPickArtwork,
                ) {
                    Text(stringResource(R.string.local_metadata_select_cover))
                }
                TextButton(
                    enabled = !saving && !artworkLoading,
                    onClick = onRemoveArtwork,
                ) {
                    Text(stringResource(R.string.local_metadata_remove_cover))
                }
            }

            if (artworkLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
