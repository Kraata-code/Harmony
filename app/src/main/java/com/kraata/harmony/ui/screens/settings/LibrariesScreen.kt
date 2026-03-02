/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import com.kraata.harmony.R
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.utils.backToMain
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val libraries by rememberLibraries(R.raw.aboutlibraries)

    Box(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current)) {
        LibrariesContainer(libraries, Modifier.fillMaxSize())
    }

    TopAppBar(
        title = { Text(stringResource(R.string.oss_licenses_title)) },
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
