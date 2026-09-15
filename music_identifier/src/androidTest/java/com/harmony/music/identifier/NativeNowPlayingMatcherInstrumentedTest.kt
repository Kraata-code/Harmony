package com.harmony.music.identifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeNowPlayingMatcherInstrumentedTest {
    @Test
    fun initializesAndRecognizesSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = NativeNowPlayingMatcher.recognize(context, ByteArray(16_000 * 2 * 8), 16_000)

        assertNull(result)
    }

    @Test
    fun initializesWithEachShardGroupSeparately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val silence = ByteArray(16_000 * 2 * 8)

        listOf("mx", "us-xa").forEach { group ->
            assertNull(
                NativeNowPlayingMatcher.recognize(
                    context,
                    silence,
                    16_000,
                    setOf(group),
                ),
            )
        }
    }

    @Test
    fun decodesLocalWavBeforeMatching() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-matcher-silence.wav")
        writeSilentWav(file)

        try {
            val sample = AudioFileDecoder().decode(file)

            assertEquals(16_000, sample.sampleRate)
            assertEquals(1, sample.channelCount)
            assertNull(NativeNowPlayingMatcher.recognize(context, sample))
        } finally {
            file.delete()
        }
    }

    private fun writeSilentWav(file: File) {
        val sampleRate = 16_000
        val dataSize = sampleRate * 8 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        fun putAscii(value: String) = header.put(value.toByteArray(Charsets.US_ASCII))

        putAscii("RIFF")
        header.putInt(36 + dataSize)
        putAscii("WAVEfmt ")
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        putAscii("data")
        header.putInt(dataSize)

        file.outputStream().use { output ->
            output.write(header.array())
            output.write(ByteArray(dataSize))
        }
    }

}
