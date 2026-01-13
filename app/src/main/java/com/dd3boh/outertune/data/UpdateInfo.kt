package com.dd3boh.outertune.data

data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val mandatory: Boolean,
    val checksum: String?
)