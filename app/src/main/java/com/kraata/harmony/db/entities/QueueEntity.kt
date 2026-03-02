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

import androidx.media3.common.C
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.apache.commons.lang3.RandomStringUtils

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title", defaultValue = "")
    var title: String,
    var shuffled: Boolean = false,
    var queuePos: Int = -1, // position of current song
    @ColumnInfo(name = "lastSongPos", defaultValue = C.TIME_UNSET.toString())
    var lastSongPos: Long = C.TIME_UNSET,
    @ColumnInfo(name = "index", defaultValue = 0.toString())
    val index: Int, // order of queue
    val playlistId: String? = null,
) {
    companion object {
        fun generateQueueId() = RandomStringUtils.insecure().next(8, false, true).toLong()
    }
}
