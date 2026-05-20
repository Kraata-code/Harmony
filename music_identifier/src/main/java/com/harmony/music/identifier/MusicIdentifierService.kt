package com.harmony.music.identifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
    val sha256: String,
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
    private val maxFileSizeBytes: Int = DEFAULT_MAX_FILE_SIZE_BYTES,
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
            MusicIdentificationResult.ProcessingError(
                message = error.message ?: "Could not record microphone audio",
                cause = error,
            )
        }
    }

    suspend fun identifyFromUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
    ): MusicIdentificationResult = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)

            if (mimeType != null && !mimeType.startsWith("audio/")) {
                return@withContext MusicIdentificationResult.UnsupportedFormat("Unsupported mime type: $mimeType")
            }

            val audioData = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break

                    totalBytes += read
                    if (totalBytes > maxFileSizeBytes) {
                        return@withContext MusicIdentificationResult.UnsupportedFormat(
                            "Audio file is larger than $maxFileSizeBytes bytes",
                        )
                    }

                    output.write(buffer, 0, read)
                }

                output.toByteArray()
            } ?: return@withContext MusicIdentificationResult.ProcessingError("Could not open audio uri")

            val sample = AudioSample(
                data = audioData,
                source = AudioSource.File(uri = uri, displayName = displayName),
                mimeType = mimeType,
                durationMs = readDurationMs(context, uri),
            )

            processAudio(sample)
        }.getOrElse { error ->
            MusicIdentificationResult.ProcessingError(
                message = error.message ?: "Could not process audio uri",
                cause = error,
            )
        }
    }

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

        if (sample.mimeType != null && !sample.mimeType.startsWith("audio/")) {
            return MusicIdentificationResult.UnsupportedFormat("Unsupported mime type: ${sample.mimeType}")
        }

        val fingerprint = AudioFingerprint(
            sha256 = sample.data.sha256(),
            byteCount = sample.byteCount,
            durationMs = sample.durationMs,
            mimeType = sample.mimeType,
            source = sample.source,
        )

        return MusicIdentificationResult.Success(
            fingerprint = fingerprint,
            sample = sample,
        )
    }

    @SuppressLint("MissingPermission")
    private fun recordMicrophoneSample(
        durationMs: Long,
        sampleRate: Int,
    ): AudioSample {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        require(minBufferSize > 0) { "Unsupported microphone audio configuration" }

        val bufferSize = max(minBufferSize, sampleRate * PCM_16_MONO_BYTES_PER_SAMPLE)
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
            val bytesToRead = ((sampleRate * PCM_16_MONO_BYTES_PER_SAMPLE) * (durationMs / 1000.0)).toInt()
            val output = ByteArrayOutputStream(bytesToRead.coerceAtLeast(bufferSize))
            val buffer = ByteArray(minBufferSize)

            audioRecord.startRecording()

            while (output.size() < bytesToRead) {
                val read = audioRecord.read(buffer, 0, minOf(buffer.size, bytesToRead - output.size()))
                if (read > 0) output.write(buffer, 0, read)
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

    private fun readDurationMs(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun Context.hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun ByteArray.sha256(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val DEFAULT_RECORDING_DURATION_MS = 10_000L
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val DEFAULT_MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024
        private const val PCM_16_MONO_BYTES_PER_SAMPLE = 2
        private const val MIME_TYPE_PCM = "audio/pcm"
    }
}
