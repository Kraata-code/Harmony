/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.data

data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val mandatory: Boolean,
    val checksum: String?
)
