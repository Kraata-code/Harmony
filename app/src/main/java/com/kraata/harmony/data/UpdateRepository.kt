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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object UpdateRepository {
    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.UpToDate)
    val state: StateFlow<UpdateCheckState> = _state

    fun update(state: UpdateCheckState) {
        _state.value = state
    }

    // Backwards compatible helper
    fun latestUpdateInfoOrNull(): UpdateInfo? = when (val s = _state.value) {
        is UpdateCheckState.UpdateAvailable -> s.info
        else -> null
    }
}
