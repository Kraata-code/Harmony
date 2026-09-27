package com.harmony.music.identifier

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private fun MediaFormat.requiredInteger(key: String): Int =
    optionalInteger(key) ?: error("Audio format is missing $key")

private fun MediaFormat.optionalInteger(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

internal class AudioFileDecoder {
    suspend fun decode(file: File): AudioSample {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("No audio track found")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mimeType = format.getString(MediaFormat.KEY_MIME).orEmpty()

            return if (mimeType == MediaFormat.MIMETYPE_AUDIO_RAW) {
                decodeRaw(extractor, format, file)
            } else {
                decodeCompressed(extractor, format, mimeType, file)
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        file: File,
    ): AudioSample {
        val pcmFormat = PcmFormat(
            sampleRate = format.requiredInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = format.requiredInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding = format.optionalInteger(MediaFormat.KEY_PCM_ENCODING)
                ?: AudioFormat.ENCODING_PCM_16BIT,
        )
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(RAW_SAMPLE_BUFFER_SIZE)

        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            appendPcm(output, buffer, 0, sampleSize, pcmFormat.encoding)
            if (!extractor.advance()) break
        }

        return output.toAudioSample(pcmFormat, file)
    }

    private suspend fun decodeCompressed(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mimeType: String,
        file: File,
    ): AudioSample {
        val codec = MediaCodec.createDecoderByType(mimeType)
        val bufferInfo = MediaCodec.BufferInfo()
        val output = ByteArrayOutputStream()
        var inputDone = false
        var outputDone = false
        var pcmFormat = PcmFormat(
            sampleRate = inputFormat.requiredInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = inputFormat.requiredInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder input buffer is unavailable")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.takeIf { it >= 0 } ?: 0
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        pcmFormat = pcmFormat.updateFrom(codec.outputFormat)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        try {
                            if (bufferInfo.size > 0 &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                    ?: error("Decoder output buffer is unavailable")
                                appendPcm(
                                    output,
                                    outputBuffer,
                                    bufferInfo.offset,
                                    bufferInfo.size,
                                    pcmFormat.encoding,
                                )
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        return output.toAudioSample(pcmFormat, file)
    }

    private fun appendPcm(
        output: ByteArrayOutputStream,
        source: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
    ) {
        require(offset >= 0 && size >= 0 && offset <= source.limit() - size) {
            "Invalid PCM buffer range"
        }

        val pcm = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(offset)
            limit(offset + size)
        }

        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val bytes = ByteArray(size)
                pcm.get(bytes)
                output.write(bytes)
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                repeat(size) {
                    val sample = ((pcm.get().toInt() and 0xff) - 128) shl 8
                    output.write(sample and 0xff)
                    output.write((sample ushr 8) and 0xff)
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                require(size % Float.SIZE_BYTES == 0) { "Invalid float PCM buffer" }
                repeat(size / Float.SIZE_BYTES) {
                    val value = pcm.float
                    val sample = ((if (value.isFinite()) value else 0f)
                        .coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    output.write(sample and 0xff)
                    output.write((sample ushr 8) and 0xff)
                }
            }

            else -> error("Unsupported PCM encoding: $encoding")
        }
    }

    private data class PcmFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int,
    ) {
        fun updateFrom(format: MediaFormat): PcmFormat = copy(
            sampleRate = format.optionalInteger(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate,
            channelCount = format.optionalInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount,
            encoding = format.optionalInteger(MediaFormat.KEY_PCM_ENCODING) ?: encoding,
        )
    }

    private fun ByteArrayOutputStream.toAudioSample(
        pcmFormat: PcmFormat,
        file: File,
    ): AudioSample {
        val data = toByteArray()
        val durationMs = data.size.toLong() * 1_000L /
            (PCM_16_BYTES_PER_SAMPLE * pcmFormat.sampleRate * pcmFormat.channelCount)
        return AudioSample(
            data = data,
            source = AudioSource.File(
                uri = android.net.Uri.fromFile(file),
                displayName = file.name,
            ),
            mimeType = MIME_TYPE_PCM,
            sampleRate = pcmFormat.sampleRate,
            channelCount = pcmFormat.channelCount,
            durationMs = durationMs,
        )
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val RAW_SAMPLE_BUFFER_SIZE = 64 * 1024
        const val PCM_16_BYTES_PER_SAMPLE = 2
        const val MIME_TYPE_PCM = "audio/pcm"
    }
}
