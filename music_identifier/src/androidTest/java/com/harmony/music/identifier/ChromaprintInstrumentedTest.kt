package com.harmony.music.identifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromaprintInstrumentedTest {
    @Test
    fun generatesFingerprintFromPcm() {
        val sampleRate = 16_000
        val sampleCount = sampleRate * 5
        val pcm = ByteArray(sampleCount * 2)

        repeat(sampleCount) { index ->
            val sample = (sin(2 * PI * 440 * index / sampleRate) * 10_000).toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }

        assertFalse(Chromaprint.fingerprint(pcm, sampleRate, 1).isBlank())
    }
}
