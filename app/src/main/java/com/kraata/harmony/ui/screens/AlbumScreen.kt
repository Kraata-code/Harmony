/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kraata.harmony.LocalDatabase
import com.kraata.harmony.LocalDownloadUtil
import com.kraata.harmony.LocalMenuState
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.LocalSnackbarHostState
import com.kraata.harmony.R
import com.kraata.harmony.constants.AlbumThumbnailSize
import com.kraata.harmony.constants.ListThumbnailSize
import com.kraata.harmony.constants.SwipeToQueueKey
import com.kraata.harmony.constants.ThumbnailCornerRadius
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.db.entities.Album
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.ExoDownloadService
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.ui.component.AsyncImageLocal
import com.kraata.harmony.ui.component.AutoResizeText
import com.kraata.harmony.ui.component.FloatingFooter
import com.kraata.harmony.ui.component.FontSizeRange
import com.kraata.harmony.ui.component.LazyColumnScrollbar
import com.kraata.harmony.ui.component.NavigationTitle
import com.kraata.harmony.ui.component.SelectHeader
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.component.items.SongListItem
import com.kraata.harmony.ui.component.items.YouTubeGridItem
import com.kraata.harmony.ui.component.shimmer.ButtonPlaceholder
import com.kraata.harmony.ui.component.shimmer.ListItemPlaceHolder
import com.kraata.harmony.ui.component.shimmer.ShimmerHost
import com.kraata.harmony.ui.component.shimmer.TextPlaceholder
import com.kraata.harmony.ui.menu.AlbumMenu
import com.kraata.harmony.ui.menu.YouTubeAlbumMenu
import com.kraata.harmony.ui.utils.backToMain
import com.kraata.harmony.ui.utils.getNSongsString
import com.kraata.harmony.utils.LocalArtworkPath
import com.kraata.harmony.utils.getDownloadState
import com.kraata.harmony.utils.joinByBullet
import com.kraata.harmony.utils.rememberPreference
import com.kraata.harmony.viewmodels.AlbumViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val scope = rememberCoroutineScope()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val albumWithSongs by viewModel.albumWithSongs.collectAsState()
    val otherVersions by viewModel.otherVersions.collectAsState()
    val state = rememberLazyListState()
    val isLoading by viewModel.isLoading.collectAsState()

    // multiselect
    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val snackbarHostState = LocalSnackbarHostState.current

    val downloadUtil = LocalDownloadUtil.current
    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(albumWithSongs) {
        if (albumWithSongs?.album?.isLocal != false) return@LaunchedEffect
        val songs = albumWithSongs?.songs?.filterNot { it.song.isLocal }?.map { it.id }
        if (songs.isNullOrEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it] })
        }
    }

    LazyColumn(
        state = state,
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
    ) {
        val albumWithSongsLocal = albumWithSongs
        if (albumWithSongsLocal != null && albumWithSongsLocal.songs.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val thumbnailUrl = albumWithSongsLocal.album.thumbnailUrl
                        if (thumbnailUrl != null) {
                            val px = (AlbumThumbnailSize.value * density.density).roundToInt()
                            AsyncImage(
                                model = if (thumbnailUrl.startsWith("/storage")) LocalArtworkPath(
                                    thumbnailUrl,
                                    px,
                                    px
                                ) else thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(AlbumThumbnailSize)
                                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                            )
                        } else {
                            // TODO: use painter fallback
                            AsyncImageLocal(
                                image = { null },
                                placeholderIcon = Icons.Rounded.Album,
                                modifier = Modifier
                                    .size(AlbumThumbnailSize)
                                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                            )
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AutoResizeText(
                                text = albumWithSongsLocal.album.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSizeRange = FontSizeRange(16.sp, 22.sp)
                            )

                            val annotatedString = buildAnnotatedString {
                                withStyle(
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onBackground
                                    ).toSpanStyle()
                                ) {
                                    albumWithSongsLocal.artists.fastForEachIndexed { index, artist ->
                                        withLink(
                                            LinkAnnotation.Clickable(artist.id) {
                                                navController.navigate("artist/${artist.id}")
                                            }
                                        ) { append(artist.name) }
                                        if (index != albumWithSongsLocal.artists.lastIndex) {
                                            append(", ")
                                        }
                                    }
                                }
                            }

                            Text(annotatedString)

                            Text(
                                text = if (albumWithSongsLocal.album.year != null) {
                                    joinByBullet(
                                        getNSongsString(
                                            albumWithSongsLocal.album.songCount,
                                            albumWithSongsLocal.downloadCount
                                        ),
                                        albumWithSongsLocal.album.year.toString()
                                    )
                                } else {
                                    getNSongsString(
                                        albumWithSongsLocal.album.songCount,
                                        albumWithSongsLocal.downloadCount
                                    )
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal
                            )

                            Row {
                                IconButton(
                                    onClick = {
                                        database.query {
                                            update(albumWithSongsLocal.album.toggleLike())
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(if (albumWithSongsLocal.album.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                                        contentDescription = null,
                                        tint = if (albumWithSongsLocal.album.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                                    )
                                }

                                if (albumWithSongsLocal.album.isLocal == false) {
                                    when (downloadState) {
                                        Download.STATE_COMPLETED -> {
                                            IconButton(
                                                onClick = {
                                                    albumWithSongsLocal.songs.forEach { song ->
                                                        DownloadService.sendRemoveDownload(
                                                            context,
                                                            ExoDownloadService::class.java,
                                                            song.id,
                                                            false
                                                        )
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.OfflinePin,
                                                    contentDescription = null
                                                )
                                            }
                                        }

                                        Download.STATE_DOWNLOADING -> {
                                            IconButton(
                                                onClick = {
                                                    albumWithSongsLocal.songs.forEach { song ->
                                                        DownloadService.sendRemoveDownload(
                                                            context,
                                                            ExoDownloadService::class.java,
                                                            song.id,
                                                            false
                                                        )
                                                    }
                                                }
                                            ) {
                                                CircularProgressIndicator(
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        else -> {
                                            IconButton(
                                                onClick = {
                                                    val songs =
                                                        albumWithSongsLocal.songs.map { it.toMediaMetadata() }
                                                    downloadUtil.download(songs)
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Download,
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            AlbumMenu(
                                                originalAlbum = Album(
                                                    albumWithSongsLocal.album,
                                                    albumWithSongsLocal.downloadCount,
                                                    albumWithSongsLocal.artists
                                                ),
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = albumWithSongsLocal.album.title,
                                        items = albumWithSongs?.songs?.mapNotNull { it.toMediaMetadata() }?.toList()
                                            ?: emptyList(),
                                        playlistId = albumWithSongsLocal.album.playlistId
                                    )
                                )
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.play),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(
                                text = stringResource(R.string.play)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = albumWithSongsLocal.album.title,
                                        items = albumWithSongs?.songs?.mapNotNull { it.toMediaMetadata() }?.toList()
                                            ?: emptyList(),
                                        playlistId = albumWithSongsLocal.album.playlistId,
                                        startShuffled = true,
                                    )
                                )
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.shuffle_on),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                }
            }

            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = albumWithSongs!!.songs,
                key = { _, song -> song.id }
            ) { index, song ->
                SongListItem(
                    song = song,
                    albumIndex = index + 1,

                    navController = navController,
                    snackbarHostState = snackbarHostState,

                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    inSelectMode = inSelectMode,
                    isSelected = selection.contains(song.id),
                    onSelectedChange = {
                        inSelectMode = true
                        if (it) {
                            selection.add(song.id)
                        } else {
                            selection.remove(song.id)
                        }
                    },
                    swipeEnabled = swipeEnabled,

                    thumbnailSize = thumbnailSize,
                    onPlay = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = albumWithSongsLocal.album.title,
                                items = albumWithSongsLocal.songs.map { it.toMediaMetadata() },
                                startIndex = index,
                                playlistId = albumWithSongsLocal.album.playlistId
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }

            if (otherVersions.isNotEmpty()) {
                item {
                    NavigationTitle(
                        title = stringResource(R.string.other_versions),
                    )
                }
                item {
                    LazyRow {
                        items(
                            items = otherVersions,
                            key = { it.id },
                        ) { item ->
                            YouTubeGridItem(
                                item = item,
                                isActive = mediaMetadata?.album?.id == item.id,
                                isPlaying = isPlaying,
                                coroutineScope = scope,
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = { navController.navigate("album/${item.id}") },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                YouTubeAlbumMenu(
                                                    albumItem = item,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    )
                                    .animateItem(),
                            )
                        }
                    }
                }
            }
        } else if (isLoading) {
            item {
                ShimmerHost {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(
                                modifier = Modifier
                                    .size(AlbumThumbnailSize)
                                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                    .background(MaterialTheme.colorScheme.onSurface)
                            )

                            Spacer(Modifier.width(16.dp))

                            Column(
                                verticalArrangement = Arrangement.Center,
                            ) {
                                TextPlaceholder()
                                TextPlaceholder()
                                TextPlaceholder()
                            }
                        }

                        Spacer(Modifier.padding(8.dp))

                        Row {
                            ButtonPlaceholder(Modifier.weight(1f))

                            Spacer(Modifier.width(12.dp))

                            ButtonPlaceholder(Modifier.weight(1f))
                        }
                    }

                    repeat(6) {
                        ListItemPlaceHolder()
                    }
                }
            }
        }
    }
    LazyColumnScrollbar(
        state = state,
    )

    TopAppBar(
        title = { },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        FloatingFooter(inSelectMode) {
            val albumWithSongsLocal = albumWithSongs
            if (albumWithSongsLocal != null && albumWithSongsLocal.songs.isNotEmpty()) {
                SelectHeader(
                    navController = navController,
                    selectedItems = selection.mapNotNull { id ->
                        albumWithSongsLocal.songs.find { it.song.id == id }
                    }.map { it.toMediaMetadata() },
                    totalItemCount = albumWithSongsLocal.songs.size,
                    onSelectAll = {
                        selection.clear()
                        selection.addAll(albumWithSongsLocal.songs.map { it.id })
                    },
                    onDeselectAll = { selection.clear() },
                    menuState = menuState,
                    onDismiss = onExitSelectionMode
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}
