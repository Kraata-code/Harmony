package com.zionhuang.innertube.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailRendererTest {
    @Test
    fun selectsLargestThumbnailRegardlessOfOrder() {
        val renderer = ThumbnailRenderer.MusicThumbnailRenderer(
            thumbnail = Thumbnails(
                listOf(
                    Thumbnail("small", 60, 60),
                    Thumbnail("large", 544, 544),
                    Thumbnail("medium", 120, 120),
                )
            ),
            thumbnailCrop = null,
            thumbnailScale = null,
        )

        assertEquals("large", renderer.getThumbnailUrl())
    }
}
