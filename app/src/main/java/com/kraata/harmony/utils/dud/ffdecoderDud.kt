/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * Dud class. This should never be used.
 */
open class NextRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        throw NotImplementedError("Dud class. This should never be used.")
    }
}

class FfmpegLibrary() {
    companion object {
        fun isAvailable() = false
        fun getVersion(): String = "N/A"
    }
}
