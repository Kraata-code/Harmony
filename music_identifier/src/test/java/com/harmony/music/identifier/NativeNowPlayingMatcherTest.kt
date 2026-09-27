package com.harmony.music.identifier

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeNowPlayingMatcherTest {
    @Test
    fun basicMatchParams_matchesGoogleRecognitionParams() {
        assertArrayEquals(
            byteArrayOf(
                0x08, 0x80.toByte(), 0x7d,
                0x28, 0x00,
                0x38, 0x00,
                0x40, 0x00,
            ),
            NativeNowPlayingMatcher.basicMatchParams(16_000),
        )
    }

    @Test
    fun stereoPcm_isDownmixedToMono() {
        val stereo = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1_000)
            .putShort(-1_000)
            .putShort(-2_000)
            .putShort(4_000)
            .array()

        val mono = NativeNowPlayingMatcher.toMonoPcm(stereo, 2)

        val expected = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(1_000)
            .array()
        assertArrayEquals(expected, mono)
    }
}
