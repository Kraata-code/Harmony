package com.kraata.harmony.playback.queues

import com.kraata.harmony.models.MediaMetadata

object EmptyQueue : Queue {
    override val preloadItem: MediaMetadata? = null
    override val playlistId: String? = null
    override val startShuffled: Boolean = false
    override suspend fun getInitialStatus() = Queue.Status(null, emptyList(), -1)
    override fun hasNextPage() = false
    override suspend fun nextPage() = emptyList<MediaMetadata>()
}