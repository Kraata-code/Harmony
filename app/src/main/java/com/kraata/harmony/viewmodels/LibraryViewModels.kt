/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kraata.harmony.viewmodels

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kraata.harmony.constants.AlbumFilter
import com.kraata.harmony.constants.AlbumFilterKey
import com.kraata.harmony.constants.AlbumSortDescendingKey
import com.kraata.harmony.constants.AlbumSortType
import com.kraata.harmony.constants.AlbumSortTypeKey
import com.kraata.harmony.constants.ArtistFilter
import com.kraata.harmony.constants.ArtistFilterKey
import com.kraata.harmony.constants.ArtistSongSortDescendingKey
import com.kraata.harmony.constants.ArtistSongSortType
import com.kraata.harmony.constants.ArtistSongSortTypeKey
import com.kraata.harmony.constants.ArtistSortDescendingKey
import com.kraata.harmony.constants.ArtistSortType
import com.kraata.harmony.constants.ArtistSortTypeKey
import com.kraata.harmony.constants.LibrarySortDescendingKey
import com.kraata.harmony.constants.LibrarySortType
import com.kraata.harmony.constants.LibrarySortTypeKey
import com.kraata.harmony.constants.PlaylistFilter
import com.kraata.harmony.constants.PlaylistFilterKey
import com.kraata.harmony.constants.PlaylistSortDescendingKey
import com.kraata.harmony.constants.PlaylistSortType
import com.kraata.harmony.constants.PlaylistSortTypeKey
import com.kraata.harmony.constants.SongFilter
import com.kraata.harmony.constants.SongFilterKey
import com.kraata.harmony.constants.SongSortDescendingKey
import com.kraata.harmony.constants.SongSortType
import com.kraata.harmony.constants.SongSortTypeKey
import com.kraata.harmony.db.MusicDatabase
import com.kraata.harmony.db.entities.Album
import com.kraata.harmony.db.entities.Artist
import com.kraata.harmony.db.entities.Playlist
import com.kraata.harmony.db.entities.Song
import com.kraata.harmony.extensions.toEnum
import com.kraata.harmony.models.DirectoryTree
import com.kraata.harmony.ui.utils.STORAGE_ROOT
import com.kraata.harmony.ui.utils.cacheDirectoryTree
import com.kraata.harmony.ui.utils.getDirectoryTree
import com.kraata.harmony.utils.SyncUtils
import com.kraata.harmony.utils.dataStore
import com.kraata.harmony.utils.reportException
import com.kraata.harmony.utils.scanners.LocalMediaScanner.Companion.refreshLocal
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allSongs = getSyncedSongs(context, database)
    val isSyncingRemoteLikedSongs = syncUtils.isSyncingRemoteLikedSongs
    val isSyncingRemoteSongs = syncUtils.isSyncingRemoteSongs

    fun syncLibrarySongs(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteSongs(bypassCd) }
    }

    fun syncLikedSongs(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteLikedSongs(bypassCd) }
    }

    private fun getSyncedSongs(context: Context, database: MusicDatabase): StateFlow<List<Song>?> {

        return context.dataStore.data
            .map {
                Triple(
                    it[SongFilterKey].toEnum(SongFilter.LIKED),
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                    (it[SongSortDescendingKey] != false)
                )
            }
            .distinctUntilChanged()
            .flatMapLatest { (filter, sortType, descending) ->
                when (filter) {
                    SongFilter.LIBRARY -> database.songs(sortType, descending)
                    SongFilter.LIKED -> database.likedSongs(sortType, descending)
                    SongFilter.DOWNLOADED -> database.downloadSongs(sortType, descending)
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    }
}

@HiltViewModel
class LibraryFoldersViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val TAG = LibraryFoldersViewModel::class.simpleName.toString()
    val path = savedStateHandle.get<String>("path")?.replace(';', '/') ?: STORAGE_ROOT

    val localSongDirectoryTree: MutableStateFlow<DirectoryTree> = MutableStateFlow(getDirectoryTree(path))
    val localSongDtSongCount = MutableStateFlow(0)
    val filteredSongs = mutableStateListOf<Song>()

    var uiInit = false
    var lastLocalScan = 0L

    /**
     * Trigger a scan of local directory
     */
    suspend fun getLocalSongs(dir: String? = null) {
        Log.d(TAG, "Loading folders page: ${dir ?: path}")
        val dt = refreshLocal(database, dir ?: path)
        dt.isSkeleton = false
        cacheDirectoryTree(dt)
        localSongDirectoryTree.value = dt
    }

    /**
     * Get total number of songs in directory
     */
    suspend fun getSongCount(dir: String? = null) {
        Log.d(TAG, "Loading folder song count: ${dir ?: path}")
        localSongDtSongCount.value = database.localSongCountInPath(dir ?: path).first()
    }

    /**
     * Update filteredSongs with search query
     */
    fun searchInDir(query: String, dir: String = path) {
        if (query.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val dbSongs = database.searchSongsAllLocalInDir(dir, query).first()
                filteredSongs.clear()
                filteredSongs.addAll(dbSongs)
            }
        }
    }
}

@HiltViewModel
class LibraryArtistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemoteArtists = syncUtils.isSyncingRemoteArtists

    val allArtists = context.dataStore.data
        .map {
            Triple(
                it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                it[ArtistSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending) ->
            database.artists(filter, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun syncArtists(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteArtists(bypassCd) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.collect { artists ->
                artists
                    ?.map { it.artist }
                    ?.filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }
                    ?.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemoteAlbums = syncUtils.isSyncingRemoteAlbums

    val allAlbums = context.dataStore.data
        .map {
            Triple(
                it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE),
                it[AlbumSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending) ->
            database.albums(filter, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun syncAlbums(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteAlbums(bypassCd) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.collect { albums ->
                albums
                    ?.filter {
                        !it.album.isLocal && it.album.songCount == 0
                    }?.forEach { album ->
                        YouTube.album(album.id).onSuccess { albumPage ->
                            database.query {
                                update(album.album, albumPage)
                            }
                        }.onFailure {
                            reportException(it)
                            if (it.message?.contains("NOT_FOUND") == true) {
                                database.query {
                                    delete(album.album)
                                }
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemotePlaylists = syncUtils.isSyncingRemotePlaylists

    val allPlaylists = context.dataStore.data
        .map {
            Triple(
                it[PlaylistFilterKey].toEnum(PlaylistFilter.LIBRARY),
                it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE),
                it[PlaylistSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending) ->
            database.playlists(filter, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun syncPlaylists(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemotePlaylists(bypassCd) }
    }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils
) : ViewModel() {

    val isSyncingRemoteLikedSongs = syncUtils.isSyncingRemoteLikedSongs
    val isSyncingRemoteSongs = syncUtils.isSyncingRemoteSongs
    val isSyncingRemoteAlbums = syncUtils.isSyncingRemoteAlbums
    val isSyncingRemoteArtists = syncUtils.isSyncingRemoteArtists
    val isSyncingRemotePlaylists = syncUtils.isSyncingRemotePlaylists

    var artists = database.artistsBookmarkedAsc().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums = database.albumsLikedAsc().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists = database.playlistInLibraryAsc().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allItems = context.dataStore.data
        .map {
            it[LibrarySortTypeKey].toEnum(LibrarySortType.CREATE_DATE) to (it[LibrarySortDescendingKey] != false)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            combine(artists, albums, playlists) { artists, albums, playlists ->
                val items = artists + albums + playlists
                items.sortedBy { item ->
                    when (sortType) {
                        LibrarySortType.CREATE_DATE -> when (item) {
                            is Album -> item.album.bookmarkedAt
                            is Artist -> item.artist.bookmarkedAt
                            is Playlist -> item.playlist.bookmarkedAt
                            else -> LocalDateTime.now()
                        }

                        else -> when (item) {
                            is Album -> item.album.title.lowercase()
                            is Artist -> item.artist.name.lowercase()
                            is Playlist -> item.playlist.name.lowercase()
                            else -> ""
                        }
                    }.toString()
                }.let { if (descending) it.reversed() else it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncAll(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.tryAutoSync(bypassCd) }
    }
}

@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs = context.dataStore.data
        .map {
            it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey]
                ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.artistSongs(artistId, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
