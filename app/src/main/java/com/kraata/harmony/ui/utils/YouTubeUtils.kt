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

import kotlin.math.roundToInt

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    "https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)((?:-[^-?]+)?-h)(\\d+)(.*)".toRegex()
        .matchEntire(this)?.groupValues?.let { group ->
        val originalWidth = group[1].toInt()
        val originalHeight = group[3].toInt()
        var w = width
        var h = height
        if (w != null && h == null) h = (w.toFloat() / originalWidth * originalHeight).roundToInt()
        if (w == null && h != null) w = (h.toFloat() / originalHeight * originalWidth).roundToInt()
        return "${group[0].substringBefore("=w")}=w$w${group[2]}$h${group[4]}"
    }

    "https://yt3\\.ggpht\\.com/.*=s(\\d+)(.*)".toRegex().matchEntire(this)?.groupValues?.let { group ->
        return "${group[0].substringBefore("=s")}=s${width ?: height}${group[2]}"
    }

    return this
}
