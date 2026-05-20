package com.harmony.music.identifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicIdentifierServiceTest {
    private val service = MusicIdentifierService()

    @Test
    fun identifyFromBytes_returnsNoAudioForEmptyInput() {
        val result = service.identifyFromBytes(ByteArray(0))

        assertEquals(MusicIdentificationResult.NoAudio, result)
    }

    @Test
    fun identifyFromBytes_returnsStableFingerprintForSameInput() {
        val audioData = byteArrayOf(1, 2, 3, 4, 5)

        val first = service.identifyFromBytes(audioData) as MusicIdentificationResult.Success
        val second = service.identifyFromBytes(audioData) as MusicIdentificationResult.Success

        assertEquals(first.fingerprint.sha256, second.fingerprint.sha256)
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
}
