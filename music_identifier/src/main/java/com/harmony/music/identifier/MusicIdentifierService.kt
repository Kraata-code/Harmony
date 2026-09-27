package com.harmony.music.identifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

sealed interface AudioSource {
    data object Microphone : AudioSource

    data class File(
        val uri: Uri? = null,
        val displayName: String? = null,
    ) : AudioSource
}

data class AudioSample(
    val data: ByteArray,
    val source: AudioSource,
    val mimeType: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val durationMs: Long? = null,
) {
    val byteCount: Int = data.size
}

data class AudioFingerprint(
    val encoded: String,
    val byteCount: Int,
    val durationMs: Long?,
    val mimeType: String?,
    val source: AudioSource,
)

sealed interface MusicIdentificationResult {
    data class Success(
        val fingerprint: AudioFingerprint,
        val sample: AudioSample,
    ) : MusicIdentificationResult

    data object NoAudio : MusicIdentificationResult

    data class UnsupportedFormat(
        val reason: String,
    ) : MusicIdentificationResult

    data class PermissionMissing(
        val permission: String,
    ) : MusicIdentificationResult

    data class ProcessingError(
        val message: String,
        val cause: Throwable? = null,
    ) : MusicIdentificationResult
}

class MusicIdentifierService(
    private val fingerprint: (ByteArray, Int, Int) -> String = { data, sampleRate, channelCount ->
        Chromaprint.fingerprint(data, sampleRate, channelCount)
    },
) {
    suspend fun identifyFromMicrophone(
        context: Context,
        durationMs: Long = DEFAULT_RECORDING_DURATION_MS,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): MusicIdentificationResult = withContext(Dispatchers.IO) {
        if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return@withContext MusicIdentificationResult.PermissionMissing(Manifest.permission.RECORD_AUDIO)
        }

        runCatching {
            val sample = recordMicrophoneSample(durationMs = durationMs, sampleRate = sampleRate)
            processAudio(sample)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            MusicIdentificationResult.ProcessingError(
                message = error.message ?: "Could not record microphone audio",
                cause = error,
            )
        }
    }

    suspend fun identifyFromFile(file: File): MusicIdentificationResult = withContext(Dispatchers.IO) {
        if (!file.isFile || !file.canRead()) {
            return@withContext MusicIdentificationResult.ProcessingError("Local audio file cannot be read")
        }

        try {
            processAudio(AudioFileDecoder().decode(file))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MusicIdentificationResult.ProcessingError(
                message = error.message ?: "Could not decode local audio file",
                cause = error,
            )
        }
    }

    // Raw PCM entry point for callers that already decoded the audio.
    fun identifyFromBytes(
        audioData: ByteArray,
        source: AudioSource = AudioSource.File(),
        mimeType: String? = null,
        sampleRate: Int? = null,
        channelCount: Int? = null,
        durationMs: Long? = null,
    ): MusicIdentificationResult = processAudio(
        AudioSample(
            data = audioData,
            source = source,
            mimeType = mimeType,
            sampleRate = sampleRate,
            channelCount = channelCount,
            durationMs = durationMs,
        ),
    )

    fun processAudio(sample: AudioSample): MusicIdentificationResult {
        if (sample.data.isEmpty()) return MusicIdentificationResult.NoAudio

        if (sample.mimeType != null && sample.mimeType != MIME_TYPE_PCM) {
            return MusicIdentificationResult.UnsupportedFormat("Chromaprint requires raw PCM audio")
        }

        val sampleRate = sample.sampleRate
            ?: return MusicIdentificationResult.UnsupportedFormat("PCM sample rate is required")
        val channelCount = sample.channelCount
            ?: return MusicIdentificationResult.UnsupportedFormat("PCM channel count is required")
        if (sampleRate <= 1000 || channelCount !in 1..2) {
            return MusicIdentificationResult.UnsupportedFormat("Unsupported PCM audio format")
        }
        if (sample.data.size % (PCM_16_BYTES_PER_SAMPLE * channelCount) != 0) {
            return MusicIdentificationResult.UnsupportedFormat("PCM data is not aligned to its channels")
        }

        val encoded = runCatching {
            fingerprint(sample.data, sampleRate, channelCount)
        }.getOrElse { error ->
            return MusicIdentificationResult.ProcessingError(
                message = error.message ?: "Could not generate audio fingerprint",
                cause = error,
            )
        }
        if (encoded.isBlank()) {
            return MusicIdentificationResult.ProcessingError("Could not generate audio fingerprint")
        }

        val durationMs = sample.durationMs?.takeIf { it > 0 } ?:
            sample.data.size.toLong() * 1_000 / (PCM_16_BYTES_PER_SAMPLE * sampleRate * channelCount)
        val audioFingerprint = AudioFingerprint(
            encoded = encoded,
            byteCount = sample.byteCount,
            durationMs = durationMs,
            mimeType = sample.mimeType,
            source = sample.source,
        )

        return MusicIdentificationResult.Success(
            fingerprint = audioFingerprint,
            sample = sample,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordMicrophoneSample(
        durationMs: Long,
        sampleRate: Int,
    ): AudioSample {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        require(minBufferSize > 0) { "Unsupported microphone audio configuration" }

        val bufferSize = max(minBufferSize, sampleRate * PCM_16_BYTES_PER_SAMPLE)
        val audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
            )
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            error("Could not initialize microphone recorder")
        }

        return try {
            require(durationMs > 0) { "Recording duration must be positive" }
            val bytesToRead = ((sampleRate * PCM_16_BYTES_PER_SAMPLE) * (durationMs / 1000.0)).toInt()
            val output = ByteArrayOutputStream(bytesToRead.coerceAtLeast(bufferSize))
            val buffer = ByteArray(minBufferSize)

            audioRecord.startRecording()

            while (output.size() < bytesToRead) {
                currentCoroutineContext().ensureActive()
                val read = audioRecord.read(buffer, 0, minOf(buffer.size, bytesToRead - output.size()))
                if (read <= 0) {
                    error("Could not read microphone audio (code=$read)")
                }
                output.write(buffer, 0, read)
            }

            AudioSample(
                data = output.toByteArray(),
                source = AudioSource.Microphone,
                mimeType = MIME_TYPE_PCM,
                sampleRate = sampleRate,
                channelCount = 1,
                durationMs = durationMs,
            )
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }

    private fun Context.hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val DEFAULT_RECORDING_DURATION_MS = 10_000L
        const val DEFAULT_SAMPLE_RATE = 16_000
        private const val PCM_16_BYTES_PER_SAMPLE = 2
        private const val MIME_TYPE_PCM = "audio/pcm"
    }
}
