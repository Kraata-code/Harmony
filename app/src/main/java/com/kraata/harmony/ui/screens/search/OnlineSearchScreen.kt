package com.kraata.harmony.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.kraata.harmony.LocalDatabase
import com.kraata.harmony.LocalMenuState
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.LocalPlayerConnection
import com.kraata.harmony.LocalSnackbarHostState
import com.kraata.harmony.R
import com.kraata.harmony.constants.SuggestionItemHeight
import com.kraata.harmony.constants.SwipeToQueueKey
import com.kraata.harmony.extensions.toMediaItem
import com.kraata.harmony.extensions.togglePlayPause
import com.kraata.harmony.models.toMediaMetadata
import com.kraata.harmony.playback.queues.ListQueue
import com.kraata.harmony.ui.component.LazyColumnScrollbar
import com.kraata.harmony.ui.component.SearchBarIconOffsetX
import com.kraata.harmony.ui.component.SwipeToQueueBox
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.component.items.YouTubeListItem
import com.kraata.harmony.ui.menu.YouTubeAlbumMenu
import com.kraata.harmony.ui.menu.YouTubeArtistMenu
import com.kraata.harmony.ui.menu.YouTubePlaylistMenu
import com.kraata.harmony.ui.menu.YouTubeSongMenu
import com.kraata.harmony.utils.rememberPreference
import com.kraata.harmony.viewmodels.OnlineSearchSuggestionViewModel
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

@OptIn(FlowPreview::class)
@Composable
fun OnlineSearchScreen(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    navController: NavController,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: OnlineSearchSuggestionViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val database = LocalDatabase.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val viewState by viewModel.viewState.collectAsState()

    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current
    val fillQuery: (String) -> Unit = { value ->
        onQueryChange(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length)
            )
        )
    }

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        snapshotFlow { query }.debounce { 300L }.collectLatest {
            viewModel.query.value = query
        }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start).asPaddingValues(),
    ) {
        items(
            items = viewState.history,
            key = { it.query }
        ) { history ->
            SuggestionItem(
                query = history.query,
                online = false,
                onClick = {
                    fillQuery(history.query)
                    onSearch(history.query)
                    onDismiss()
                },
                onDelete = {
                    database.query {
                        delete(history)
                    }
                },
                onFillTextField = {
                    fillQuery(history.query)
                },
                modifier = Modifier.animateItem()
            )
        }

        items(
            items = viewState.suggestions,
            key = { it }
        ) { query ->
            SuggestionItem(
                query = query,
                online = true,
                onClick = {
                    fillQuery(query)
                    onSearch(query)
                    onDismiss()
                },
                onFillTextField = {
                    fillQuery(query)
                },
                modifier = Modifier.animateItem()
            )
        }

        if (viewState.items.isNotEmpty() && viewState.history.size + viewState.suggestions.size > 0) {
            item {
                HorizontalDivider()
            }
        }

        items(
            items = viewState.items,
            key = { it.id }
        ) { item ->
            val content: @Composable () -> Unit = {
                YouTubeListItem(
                    item = item,
                    isActive = when (item) {
                        is SongItem -> mediaMetadata?.id == item.id
                        is AlbumItem -> mediaMetadata?.album?.id == item.id
                        else -> false
                    },
                    isPlaying = isPlaying,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem ->
                                            YouTubeSongMenu(
                                                song = item,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )

                                        is AlbumItem ->
                                            YouTubeAlbumMenu(
                                                albumItem = item,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )

                                        is ArtistItem ->
                                            YouTubeArtistMenu(
                                                artist = item,
                                                onDismiss = menuState::dismiss,
                                            )

                                        is PlaylistItem ->
                                            YouTubePlaylistMenu(
                                                navController = navController,
                                                playlist = item,
                                                coroutineScope = scope,
                                                onDismiss = menuState::dismiss,
                                            )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier
                        .clickable {
                            when (item) {
                                is SongItem -> {
                                    if (item.id == mediaMetadata?.id) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        val songSuggestions = viewState.items.filter { it is SongItem }
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = "${context.getString(R.string.queue_searched_songs_ot)} $query",
                                                items = songSuggestions.map { (it as SongItem).toMediaMetadata() },
                                                startIndex = songSuggestions.indexOf(item)
                                            ),
                                            replace = true,
                                        )
                                        onDismiss()
                                    }
                                }

                                is AlbumItem -> {
                                    navController.navigate("album/${item.id}")
                                    onDismiss()
                                }

                                is ArtistItem -> {
                                    navController.navigate("artist/${item.id}")
                                    onDismiss()
                                }

                                is PlaylistItem -> {
                                    navController.navigate("online_playlist/${item.id}")
                                    onDismiss()
                                }
                            }
                        }
                        .animateItem()
                )
            }

            if (item !is SongItem) content()
            else {
                SwipeToQueueBox(
                    item = item.toMediaItem(),
                    swipeEnabled = swipeEnabled,
                    snackbarHostState = snackbarHostState,
                    content = { content() },
                )
            }
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SuggestionItemHeight)
            .clickable(onClick = onClick)
            .padding(end = SearchBarIconOffsetX)
    ) {
        Icon(
            if (online) Icons.Rounded.Search else Icons.Rounded.History,
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .alpha(0.5f)
        )

        Text(
            text = query,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (!online) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.alpha(0.5f)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = null
                )
            }
        }

        IconButton(
            onClick = onFillTextField,
            modifier = Modifier.alpha(0.5f)
        ) {
            Icon(
                Icons.Rounded.ArrowOutward,
                contentDescription = null
            )
        }
    }
}
