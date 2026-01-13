package com.dd3boh.outertune.data

sealed class UpdateCheckState {
    object Loading : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckState()
    data class Error(val throwable: Throwable) : UpdateCheckState()
}
