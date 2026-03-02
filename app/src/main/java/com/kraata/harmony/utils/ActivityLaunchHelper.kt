/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.utils

import android.content.Intent
import android.util.ArrayMap
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

class ActivityLauncherHelper(
    activity: ComponentActivity
) {
    private var consumers = ArrayMap<String, ((ActivityResult) -> Unit)?>()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getStringExtra("id")
        val consumer = consumers.get(id)
        consumer?.invoke(result)
        consumers.remove(id)
    }

    fun launchActivityForResult(intent: Intent, onResult: (ActivityResult) -> Unit) {
        val id = intent.getStringExtra("filePath")
        if (id != null) {
            consumers.put(id, onResult)
            launcher.launch(intent)
        }
    }
}
