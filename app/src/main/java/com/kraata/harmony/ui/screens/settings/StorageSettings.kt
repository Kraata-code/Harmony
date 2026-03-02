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

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.R
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.ui.component.ColumnWithContentPadding
import com.kraata.harmony.ui.component.PreferenceGroupTitle
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.screens.settings.fragments.DownloadsFrag
import com.kraata.harmony.ui.screens.settings.fragments.ImageCacheFrag
import com.kraata.harmony.ui.screens.settings.fragments.SongCacheFrag
import com.kraata.harmony.ui.utils.backToMain


@SuppressLint("PrivateResource")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.downloaded_songs)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth().animateContentSize()
        ) {
            DownloadsFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))


        PreferenceGroupTitle(
            title = stringResource(R.string.song_cache)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SongCacheFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.image_cache)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ImageCacheFrag()
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
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
