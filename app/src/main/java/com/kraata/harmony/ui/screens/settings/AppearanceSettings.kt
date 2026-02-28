/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kraata.harmony.R
import com.kraata.harmony.constants.TopBarInsets
import com.kraata.harmony.ui.component.ColumnWithContentPadding
import com.kraata.harmony.ui.component.PreferenceEntry
import com.kraata.harmony.ui.component.PreferenceGroupTitle
import com.kraata.harmony.ui.component.button.IconButton
import com.kraata.harmony.ui.screens.LayoutOptionCard
import com.kraata.harmony.constants.FloatingMiniplayerKey
import com.kraata.harmony.ui.screens.settings.fragments.AppearanceMiscFrag
import com.kraata.harmony.ui.screens.settings.fragments.ThemeAppFrag
import com.kraata.harmony.ui.screens.settings.fragments.ThemePlayerFrag
import com.kraata.harmony.ui.utils.backToMain
import com.kraata.harmony.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppearanceSettings(
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
            title = stringResource(R.string.theme)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemeAppFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemePlayerFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            AppearanceMiscFrag()
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Opción 1: Diseño por defecto
            val (isFloatingMiniplayer, onFloatingMiniplayerChange) = rememberPreference(
                FloatingMiniplayerKey,
                defaultValue = false
            )
            LayoutOptionCard(
                title = "Diseño Clásico",
                isSelected = !isFloatingMiniplayer,
                onClick = { onFloatingMiniplayerChange(false) },
                preview = R.drawable.miniplayer_default
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Opción 2: Diseño flotante
            LayoutOptionCard(
                title = "Flotante",
                isSelected = isFloatingMiniplayer,
                onClick = { onFloatingMiniplayerChange(true) },
                preview = R.drawable.miniplayer_floating
            )
        }
        Spacer(modifier = Modifier.height(24.dp))


        PreferenceGroupTitle(
            title = stringResource(R.string.more_settings)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_interface)) },
                icon = { Icon(Icons.Rounded.Interests, null) },
                onClick = { navController.navigate("settings/interface") }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.appearance)) },
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
