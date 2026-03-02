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

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.kraata.harmony.R

/*
IMPORTANT: Put any string utils that **DO NOT** require composable in outertu.ne/utils/StringUtils.kt
 */

@Composable
fun getNSongsString(songCount: Int, downloadCount: Int = 0): String {
    return if (downloadCount > 0)
        "$downloadCount / " + pluralStringResource(R.plurals.n_song, songCount, songCount)
    else
        pluralStringResource(R.plurals.n_song, songCount, songCount)
}
