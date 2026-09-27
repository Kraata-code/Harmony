package com.harmony.music.identifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicIdentifierServiceTest {
    private val service = MusicIdentifierService { _, _, _ -> "encoded-fingerprint" }

    @Test
    fun identifyFromBytes_returnsNoAudioForEmptyInput() {
        val result = service.identifyFromBytes(ByteArray(0))

        assertEquals(MusicIdentificationResult.NoAudio, result)
    }

    @Test
    fun identifyFromBytes_returnsStableFingerprintForSameInput() {
        val audioData = byteArrayOf(1, 2, 3, 4)

        val first = service.identifyFromBytes(
            audioData,
            mimeType = "audio/pcm",
            sampleRate = 16_000,
            channelCount = 1,
        ) as MusicIdentificationResult.Success
        val second = service.identifyFromBytes(
            audioData,
            mimeType = "audio/pcm",
            sampleRate = 16_000,
            channelCount = 1,
        ) as MusicIdentificationResult.Success

        assertEquals(first.fingerprint.encoded, second.fingerprint.encoded)
        assertEquals(audioData.size, first.fingerprint.byteCount)
    }

    @Test
    fun identifyFromBytes_rejectsNonAudioMimeType() {
        val result = service.identifyFromBytes(
            audioData = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
        )

        assertTrue(result is MusicIdentificationResult.UnsupportedFormat)
    }

    @Test
    fun identifyFromBytes_requiresPcmMetadata() {
        val result = service.identifyFromBytes(
            audioData = byteArrayOf(0, 0),
            mimeType = "audio/pcm",
        )

        assertTrue(result is MusicIdentificationResult.UnsupportedFormat)
    }

    @Test
    fun identifyFromBytes_rejectsUnalignedPcm() {
        val result = service.identifyFromBytes(
            audioData = byteArrayOf(0, 0, 0),
            mimeType = "audio/pcm",
            sampleRate = 16_000,
            channelCount = 1,
        )

        assertTrue(result is MusicIdentificationResult.UnsupportedFormat)
    }
}
