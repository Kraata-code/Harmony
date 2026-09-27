package com.kraata.harmony.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUtilsTest {
    @Test
    fun resizesGoogleusercontentThumbnail() {
        val url = "https://yt3.googleusercontent.com/image=w120-h120-l90-rj"

        assertEquals(
            "https://yt3.googleusercontent.com/image=w544-h544-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun preservesGoogleusercontentCropParameters() {
        val url = "https://yt3.googleusercontent.com/image=w120-c-h120-k-c0x00ffffff-no-l90-rj"

        assertEquals(
            "https://yt3.googleusercontent.com/image=w1080-c-h1080-k-c0x00ffffff-no-l90-rj",
            url.resize(1080, 1080),
        )
    }

    @Test
    fun replacesGgphtSize() {
        val url = "https://yt3.ggpht.com/image=s1200"

        assertEquals("https://yt3.ggpht.com/image=s544", url.resize(544, 544))
    }

    @Test
    fun leavesVideoThumbnailUntouched() {
        val url = "https://i.ytimg.com/vi/video/hqdefault.jpg"

        assertEquals(url, url.resize(1080, 1080))
    }
}
