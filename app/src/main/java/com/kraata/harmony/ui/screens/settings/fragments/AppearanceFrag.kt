/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kraata.harmony.R
import com.kraata.harmony.constants.SlimNavBarKey
import com.kraata.harmony.ui.component.SwitchPreference
import com.kraata.harmony.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )
}
