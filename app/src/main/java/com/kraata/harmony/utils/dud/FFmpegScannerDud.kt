/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.utils.scanners

import com.kraata.harmony.models.SongTempData
import java.io.File

class FFmpegScanner() : MetadataScanner {
    override suspend fun getAllMetadataFromFile(file: File): SongTempData {
        throw NotImplementedError()
    }

    companion object {
        const val VERSION_STRING = "N/A"
    }
}
