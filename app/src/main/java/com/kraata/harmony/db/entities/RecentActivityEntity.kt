/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Immutable
enum class RecentActivityType {
    PLAYLIST, ALBUM, ARTIST
}

@Entity(tableName = "recent_activity")
@Immutable
data class RecentActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val thumbnail: String?,
    val explicit: Boolean,
    val shareLink: String,
    val type: RecentActivityType,
    val playlistId: String?,
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
    val date: LocalDateTime = LocalDateTime.now()
)
