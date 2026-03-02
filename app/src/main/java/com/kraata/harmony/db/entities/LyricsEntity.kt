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

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.akanework.gramophone.logic.utils.SemanticLyrics

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val id: String,
    val lyrics: String,
) {
    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
        val uninitializedLyric = SemanticLyrics.UnsyncedLyrics(listOf(Pair(LYRICS_NOT_FOUND, null)))
    }
}
