/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.screens.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    filterContent: @Composable (() -> Unit)? = null
) {
    FolderScreen(navController, scrollBehavior, isRoot = true, libraryFilterContent = filterContent)
}
