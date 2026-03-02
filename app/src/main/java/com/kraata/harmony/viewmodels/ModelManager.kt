/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.viewmodels

import android.content.Context
import java.io.File

object ModelManager {

    fun prepareModel(context: Context): String {
        val outFile = File(context.filesDir, "Qwen2-500M-Instruct-IQ4_XS.gguf")

        if (!outFile.exists()) {
            context.assets.open("Qwen2-500M-Instruct-IQ4_XS.gguf").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return outFile.absolutePath
    }
}
