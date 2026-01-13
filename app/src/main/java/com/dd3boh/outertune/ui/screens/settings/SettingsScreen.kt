/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LastVersionKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.UpdateAvailableKey
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
//import com.dd3boh.outertune.ui.utils.Updater
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.compareVersion
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.data.UpdateChecker
import com.dd3boh.outertune.data.UpdateRepository
import com.dd3boh.outertune.data.UpdateCheckState
import androidx.compose.runtime.collectAsState


val SETTINGS_TAG = "Settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val uriHandler = LocalUriHandler.current

    val lastVer by rememberPreference(LastVersionKey, defaultValue = "0.0.0")

    val coroutineScope = rememberCoroutineScope()
    var newVersion by remember { mutableStateOf("") }
    val updateState by UpdateRepository.state.collectAsState()
    var updateStatus by remember { mutableStateOf("") }
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_account_sync)) },
                icon = { Icon(Icons.Rounded.AccountCircle, null) },
                onClick = { navController.navigate("settings/account_sync") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_library_and_content)) },
                icon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null) },
                onClick = { navController.navigate("settings/library") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.local_player_settings_title)) },
                icon = { Icon(Icons.Rounded.SdCard, null) },
                onClick = { navController.navigate("settings/local") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.appearance)) },
                icon = { Icon(Icons.Rounded.Palette, null) },
                onClick = { navController.navigate("settings/appearance") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_interface)) },
                icon = { Icon(Icons.Rounded.Interests, null) },
                onClick = { navController.navigate("settings/interface") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_and_audio)) },
                icon = { Icon(Icons.Rounded.PlayArrow, null) },
                onClick = { navController.navigate("settings/player") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.backup_restore)) },
                icon = { Icon(Icons.Rounded.Restore, null) },
                onClick = { navController.navigate("settings/backup_restore") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.storage)) },
                icon = { Icon(Icons.Rounded.Storage, null) },
                onClick = { navController.navigate("settings/storage") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.experimental_settings_title)) },
                icon = { Icon(Icons.Rounded.WarningAmber, null) },
                onClick = { navController.navigate("settings/experimental") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                val descriptionText = when {
                    updateStatus.isNotEmpty() -> updateStatus
                    updateState is UpdateCheckState.UpdateAvailable -> {
                        val info = (updateState as UpdateCheckState.UpdateAvailable).info
                        stringResource(R.string.new_version_available, info.latestVersionName)
                    }
                    else -> BuildConfig.VERSION_NAME
                }
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.version)) },
                        icon = { Icon(Icons.Rounded.Update, null) },
                        onClick = {
                            val checker = UpdateChecker()
                            coroutineScope.launch {
                                // If MainActivity already found an update, use it. Otherwise perform a manual check.
                                if (updateState is UpdateCheckState.UpdateAvailable) {
                                    val info = (updateState as UpdateCheckState.UpdateAvailable).info
                                    updateStatus = "Downloading ${info.latestVersionName}..."
                                    checker.downloadUpdate(context).collect { dstate: com.dd3boh.outertune.data.DownloadState ->
                                        when (dstate) {
                                            is com.dd3boh.outertune.data.DownloadState.Downloading -> updateStatus = "Downloading: ${dstate.progress}%"
                                            is com.dd3boh.outertune.data.DownloadState.Downloaded -> {
                                                updateStatus = "Installing..."
                                                try {
                                                    checker.installUpdate(context, dstate.file)
                                                } catch (e: Exception) {
                                                    updateStatus = "Install failed: ${e.message}"
                                                }
                                            }
                                            is com.dd3boh.outertune.data.DownloadState.Error -> updateStatus = "Download error: ${dstate.exception.message}"
                                        }
                                    }
                                } else {
                                    updateStatus = "Checking for updates..."
                                    checker.checkForUpdates(context).collect { state: com.dd3boh.outertune.data.UpdateCheckState ->
                                        // publish state so MainActivity and others can observe
                                        com.dd3boh.outertune.data.UpdateRepository.update(state)
                                        when (state) {
                                            is com.dd3boh.outertune.data.UpdateCheckState.Loading -> updateStatus = "Checking for updates..."
                                            is com.dd3boh.outertune.data.UpdateCheckState.UpToDate -> updateStatus = "App is up to date"
                                            is com.dd3boh.outertune.data.UpdateCheckState.UpdateAvailable -> updateStatus = "New version available"
                                            is com.dd3boh.outertune.data.UpdateCheckState.Error -> updateStatus = "Update check failed: ${state.throwable.message}"
                                        }
                                    }
                                }
                            }
                        },
                        description = descriptionText
                    )
            }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.about)) },
                icon = { Icon(Icons.Rounded.Info, null) },
                onClick = { navController.navigate("settings/about") }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
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
}
