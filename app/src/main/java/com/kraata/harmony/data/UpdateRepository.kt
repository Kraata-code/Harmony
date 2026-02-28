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
