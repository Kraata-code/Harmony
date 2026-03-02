/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.ui.utils

import androidx.navigation.NavController

val NavController.canNavigateUp: Boolean
    get() = currentBackStackEntry?.destination?.parent?.route != null

fun NavController.backToMain() {
    do { navigateUp() } while (canNavigateUp)
}
