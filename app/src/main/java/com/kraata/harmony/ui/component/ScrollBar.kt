/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.component

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kraata.harmony.LocalPlayerAwareWindowInsets
import my.nanihadesuka.compose.InternalLazyColumnScrollbar
import my.nanihadesuka.compose.InternalLazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarSettings

// Thumb doesn't reach bottom / thumb size randomly changes
// https://github.com/nanihadesuka/LazyColumnScrollbar/issues/40
val DefaultScrollbar: ScrollbarSettings
    @Composable
    get() =
        ScrollbarSettings.Default.copy(
            thumbThickness = 8.dp,
            thumbUnselectedColor = MaterialTheme.colorScheme.primary,
            thumbSelectedColor = MaterialTheme.colorScheme.secondary, // TODO: re-eval colour
            hideDelayMillis = 2000,
        )

@Composable
fun LazyColumnScrollbar(
    state: LazyListState,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    ),
    settings: ScrollbarSettings = DefaultScrollbar,
) = InternalLazyColumnScrollbar(
    state = state,
    settings = settings,
    modifier = modifier
)

@Composable
fun LazyVerticalGridScrollbar(
    state: LazyGridState,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    ),
    settings: ScrollbarSettings = DefaultScrollbar,
) = InternalLazyVerticalGridScrollbar(
    state = state,
    settings = settings,
    modifier = modifier
)
