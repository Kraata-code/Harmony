/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncLock
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kraata.harmony.LocalNetworkConnected
import com.kraata.harmony.LocalSnackbarHostState
import com.kraata.harmony.LocalSyncUtils
import com.kraata.harmony.R
import com.kraata.harmony.constants.InnerTubeCookieKey
import com.kraata.harmony.constants.PauseListenHistoryKey
import com.kraata.harmony.constants.PauseRemoteListenHistoryKey
import com.kraata.harmony.constants.SyncConflictResolution
import com.kraata.harmony.constants.SyncContent
import com.kraata.harmony.constants.SyncMode
import com.kraata.harmony.constants.YtmSyncConflictKey
import com.kraata.harmony.constants.YtmSyncContentKey
import com.kraata.harmony.constants.YtmSyncKey
import com.kraata.harmony.constants.YtmSyncModeKey
import com.kraata.harmony.constants.decodeSyncString
import com.kraata.harmony.constants.encodeSyncString
import com.kraata.harmony.ui.component.EnumListPreference
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.ui.component.SwitchPreference
import com.kraata.harmony.utils.SyncUtils
import com.kraata.harmony.utils.rememberEnumPreference
import com.kraata.harmony.utils.rememberPreference
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.SyncAutoFrag() {
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, defaultValue = true)

    SwitchPreference(
        title = { Text(stringResource(R.string.ytm_sync)) },
        icon = { Icon(Icons.Rounded.Sync, null) },
        checked = ytmSync,
        onCheckedChange = onYtmSyncChange,
        isEnabled = isLoggedIn
    )
}

@Composable
fun ColumnScope.SyncManualFrag() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isNetworkConnected = LocalNetworkConnected.current
    val syncUtils = LocalSyncUtils.current
    val snackbarHostState = LocalSnackbarHostState.current


    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val (syncContent, onSyncContentChange) = rememberPreference(
        YtmSyncContentKey,
        defaultValue = SyncUtils.DEFAULT_SYNC_CONTENT
    )

    val isSyncingRemotePlaylists by syncUtils.isSyncingRemotePlaylists.collectAsState()
    val isSyncingRemoteAlbums by syncUtils.isSyncingRemoteAlbums.collectAsState()
    val isSyncingRemoteArtists by syncUtils.isSyncingRemoteArtists.collectAsState()
    val isSyncingRemoteSongs by syncUtils.isSyncingRemoteSongs.collectAsState()
    val isSyncingRemoteLikedSongs by syncUtils.isSyncingRemoteLikedSongs.collectAsState()
    val isSyncingRecentActivity by syncUtils.isSyncingRecentActivity.collectAsState()

    PreferenceEntry(
        title = { Text(stringResource(R.string.scanner_manual_btn)) },
        icon = { Icon(Icons.Rounded.Sync, null) },
        onClick = {
            coroutineScope.launch(Dispatchers.Main) {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.sync_progress_active),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )

                syncUtils.tryAutoSync(true)
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.sync_progress_success),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
            }
        },
        isEnabled = isLoggedIn && isNetworkConnected
    )

    val enabledContent = decodeSyncString(syncContent).sortedBy { it.name }
    encodeSyncString(enabledContent.toList())
    SyncContent.entries.filterNot { it == SyncContent.NULL }.forEach { item ->
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp)
        ) {
            val title = when (item) {
                SyncContent.ALBUMS -> stringResource(R.string.albums)
                SyncContent.ARTISTS -> stringResource(R.string.artists)
                SyncContent.PLAYLISTS -> stringResource(R.string.playlists)
                SyncContent.LIKED_SONGS -> stringResource(R.string.liked_songs)
                SyncContent.PRIVATE_SONGS -> stringResource(R.string.songs)
                SyncContent.RECENT_ACTIVITY -> stringResource(R.string.recent_activity)
                else -> ""
            }
            val syncProgressIndicator = when (item) {
                SyncContent.ALBUMS -> isSyncingRemoteAlbums
                SyncContent.ARTISTS -> isSyncingRemoteArtists
                SyncContent.PLAYLISTS -> isSyncingRemotePlaylists
                SyncContent.LIKED_SONGS -> isSyncingRemoteLikedSongs
                SyncContent.PRIVATE_SONGS -> isSyncingRemoteSongs
                SyncContent.RECENT_ACTIVITY -> isSyncingRecentActivity
                else -> false
            }

            if (syncProgressIndicator) {
                Row(
                    modifier = Modifier.padding(14.dp)
                ) {
                    SyncProgressItem(true)
                }
            } else {
                Checkbox(
                    checked = enabledContent.contains(item),
                    onCheckedChange = { checked ->
                        val updated = enabledContent.toMutableList()
                        if (checked) {
                            updated.add(item)
                        } else {
                            updated.removeAll { it == item }
                        }
                        onSyncContentChange(encodeSyncString(updated))
                    },
                    enabled = isLoggedIn
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun ColumnScope.SyncParamsFrag() {
    val (syncConflict, onSyncConflictChange) = rememberEnumPreference(
        key = YtmSyncConflictKey,
        defaultValue = SyncConflictResolution.ADD_ONLY
    )
    val (syncMode, onSyncModeChange) = rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    EnumListPreference(
        title = { Text(stringResource(R.string.sync_mode)) },
        icon = { Icon(Icons.Rounded.SyncLock, null) },
        selectedValue = syncMode,
        onValueSelected = onSyncModeChange,
        valueText = {
            when (it) {
                SyncMode.RO -> stringResource(R.string.sync_mode_ro)
                SyncMode.RW -> stringResource(R.string.sync_mode_rw)
            }
        }
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.sync_conflict_title)) },
        icon = { Icon(Icons.Rounded.SyncProblem, null) },
        selectedValue = syncConflict,
        onValueSelected = onSyncConflictChange,
        valueText = {
            when (it) {
                SyncConflictResolution.ADD_ONLY -> stringResource(R.string.sync_conflict_add_only)
                SyncConflictResolution.OVERWRITE_WITH_REMOTE -> stringResource(R.string.sync_conflict_overwrite)
            }
        },
    )
}

@Composable
fun ColumnScope.SyncExtrasFrag() {
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    val pauseListenHistory by rememberPreference(key = PauseListenHistoryKey, defaultValue = false)
    val (pauseRemoteListenHistory, onPauseRemoteListenHistoryChange) = rememberPreference(
        key = PauseRemoteListenHistoryKey,
        defaultValue = false
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.pause_remote_listen_history)) },
        icon = { Icon(Icons.Rounded.History, null) },
        checked = pauseRemoteListenHistory,
        onCheckedChange = onPauseRemoteListenHistoryChange,
        isEnabled = !pauseListenHistory && isLoggedIn
    )
}

@Composable
fun SyncProgressItem(isSyncing: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(isSyncing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
