/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 * Copyright (C) 2026 Harmony Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.kraata.harmony.playback.queues

import com.kraata.harmony.models.MediaMetadata

class ListQueue(
    override val playlistId: String? = null,
    val title: String? = null,
    val items: List<MediaMetadata>,
    override val startShuffled: Boolean = false,
    val startIndex: Int = 0,
    val position: Long = 0L,
) : Queue {
    override val preloadItem: MediaMetadata? = null
    override suspend fun getInitialStatus() = Queue.Status(title, items, startIndex, position)
    override fun hasNextPage(): Boolean = false
    override suspend fun nextPage() = throw UnsupportedOperationException()
}
