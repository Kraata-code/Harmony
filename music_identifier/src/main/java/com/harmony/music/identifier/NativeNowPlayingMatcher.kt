package com.harmony.music.identifier

import android.content.Context
import com.google.audio.ambientmusic.NnfpRecognizerCallback
import com.google.audio.ambientmusic.NnfpV3Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NativeNowPlayingMatch(
    val title: String,
    val artist: String,
    val googleId: String,
    val players: List<String>,
)

object NativeNowPlayingMatcher {
    private const val DATA_DIRECTORY = "native_now_playing"
    private const val CORE_ASSET = "matcher_tah.leveldb"
    private const val CONFIG_ASSET = "v3_config_tah.pb"

    fun recognize(context: Context, pcm: ByteArray, sampleRate: Int): NativeNowPlayingMatch? =
        recognize(context, pcm, sampleRate, DEFAULT_SHARD_GROUPS)

    internal fun recognize(
        context: Context,
        pcm: ByteArray,
        sampleRate: Int,
        shardGroups: Collection<String>,
    ): NativeNowPlayingMatch? {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "PCM data must contain 16-bit samples" }
        require(sampleRate > 0) { "PCM sample rate must be positive" }

        val databaseDirectory = File(context.filesDir, DATA_DIRECTORY).apply { mkdirs() }
        val assets = mutableListOf(CORE_ASSET)
        shardGroups.distinct().forEach { group ->
            require(group in SUPPORTED_SHARD_GROUPS) { "Unsupported shard group: $group" }
            context.assets.list(group)?.mapTo(assets) { "$group/$it" }
        }
        val paths = assets.map { asset ->
            copyAsset(context, asset, File(databaseDirectory, asset)).absolutePath
        }
        val names = paths.map { File(it).name }.toTypedArray()
        val pointer = NnfpV3Recognizer.init(
            names,
            paths.toTypedArray(),
            context.assets.open(CONFIG_ASSET).use { it.readBytes() },
        )
        check(pointer != 0L) { "Native Now Playing matcher failed to initialize" }

        val audio = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(audio)
        val result = try {
            NnfpV3Recognizer.recognize(
                pointer,
                audio,
                basicMatchParams(sampleRate),
                object : NnfpRecognizerCallback {
                    override fun onMusicScoreComputed(score: Float) = Unit
                },
                false,
            )
        } finally {
            NnfpV3Recognizer.close(pointer)
        }
        return parseResult(result)
    }

    fun recognize(context: Context, sample: AudioSample): NativeNowPlayingMatch? {
        val sampleRate = requireNotNull(sample.sampleRate) { "PCM sample rate is required" }
        val channelCount = requireNotNull(sample.channelCount) { "PCM channel count is required" }
        require(sample.mimeType == null || sample.mimeType == MIME_TYPE_PCM) {
            "Native Now Playing matcher requires raw PCM audio"
        }
        return recognize(context, toMonoPcm(sample.data, channelCount), sampleRate)
    }

    internal fun recognize(
        context: Context,
        sample: AudioSample,
        shardGroups: Collection<String>,
    ): NativeNowPlayingMatch? {
        val sampleRate = requireNotNull(sample.sampleRate) { "PCM sample rate is required" }
        val channelCount = requireNotNull(sample.channelCount) { "PCM channel count is required" }
        require(sample.mimeType == null || sample.mimeType == MIME_TYPE_PCM) {
            "Native Now Playing matcher requires raw PCM audio"
        }
        return recognize(context, toMonoPcm(sample.data, channelCount), sampleRate, shardGroups)
    }

    internal fun toMonoPcm(pcm: ByteArray, channelCount: Int): ByteArray {
        require(channelCount in 1..2) { "Unsupported PCM channel count: $channelCount" }
        require(pcm.size % (2 * channelCount) == 0) { "PCM data is not aligned to its channels" }
        if (channelCount == 1) return pcm

        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(pcm.size / channelCount).order(ByteOrder.LITTLE_ENDIAN)
        repeat(pcm.size / (2 * channelCount)) {
            val left = input.short.toInt()
            val right = input.short.toInt()
            output.putShort(((left + right) / 2).toShort())
        }
        return output.array()
    }

    internal fun basicMatchParams(sampleRate: Int): ByteArray {
        val encoded = ByteArray(32)
        var index = 0

        fun writeVarint(value: Int) {
            var remaining = value
            do {
                encoded[index++] = ((remaining and 0x7f) or
                    if (remaining ushr 7 != 0) 0x80 else 0).toByte()
                remaining = remaining ushr 7
            } while (remaining != 0)
        }

        encoded[index++] = 0x08 // field 1: input sample rate
        writeVarint(sampleRate)
        encoded[index++] = 0x28 // field 5
        encoded[index++] = 0
        encoded[index++] = 0x38 // field 7
        encoded[index++] = 0
        encoded[index++] = 0x40 // field 8
        encoded[index++] = 0
        return encoded.copyOf(index)
    }

    private fun copyAsset(context: Context, asset: String, target: File): File {
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    private const val MIME_TYPE_PCM = "audio/pcm"
    private val DEFAULT_SHARD_GROUPS = listOf("mx", "us-xa")
    private val SUPPORTED_SHARD_GROUPS = setOf("mx", "us-xa")

    private fun parseResult(data: ByteArray): NativeNowPlayingMatch? {
        var match: NativeNowPlayingMatch? = null
        val reader = ProtoReader(data)
        while (reader.hasNext()) {
            when (reader.nextField()) {
                1 -> parseTrack(reader.readBytes())?.takeIf { it.isMatch }?.let { match = it.match }
                else -> reader.skipField()
            }
        }
        return match
    }

    private fun parseTrack(data: ByteArray): ParsedTrack? {
        var isMatch = false
        var metadata: NativeNowPlayingMatch? = null
        val reader = ProtoReader(data)
        while (reader.hasNext()) {
            when (reader.nextField()) {
                1 -> metadata = parseMetadata(reader.readBytes())
                9 -> isMatch = reader.readVarint() != 0L
                else -> reader.skipField()
            }
        }
        return metadata?.let { ParsedTrack(isMatch, it) }
    }

    private fun parseMetadata(data: ByteArray): NativeNowPlayingMatch {
        var title = ""
        var artist = ""
        var googleId = ""
        val players = mutableListOf<String>()
        val reader = ProtoReader(data)
        while (reader.hasNext()) {
            when (reader.nextField()) {
                3 -> title = reader.readString()
                4 -> artist = reader.readString()
                8 -> googleId = reader.readString()
                9 -> parsePlayer(reader.readBytes())?.let(players::add)
                else -> reader.skipField()
            }
        }
        return NativeNowPlayingMatch(title, artist, googleId, players)
    }

    private fun parsePlayer(data: ByteArray): String? {
        val reader = ProtoReader(data)
        var url: String? = null
        while (reader.hasNext()) {
            when (reader.nextField()) {
                2 -> url = reader.readString()
                else -> reader.skipField()
            }
        }
        return url?.takeIf(String::isNotBlank)
    }

    private data class ParsedTrack(val isMatch: Boolean, val match: NativeNowPlayingMatch)

    private class ProtoReader(private val data: ByteArray) {
        private var position = 0
        private var wireType = 0

        fun hasNext(): Boolean = position < data.size

        fun nextField(): Int {
            val tag = readVarint().toInt()
            wireType = tag and 7
            return tag ushr 3
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun readBytes(): ByteArray {
            check(wireType == 2) { "Expected a length-delimited protobuf field" }
            val length = readVarint().toInt()
            check(length >= 0 && position + length <= data.size) { "Invalid protobuf length" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun readVarint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                check(position < data.size) { "Truncated protobuf varint" }
                val byte = data[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
            }
            error("Invalid protobuf varint")
        }

        fun skipField() {
            when (wireType) {
                0 -> readVarint()
                1 -> skip(8)
                2 -> skip(readVarint().toInt())
                5 -> skip(4)
                else -> error("Unsupported protobuf wire type: $wireType")
            }
        }

        private fun skip(length: Int) {
            check(length >= 0 && position + length <= data.size) { "Invalid protobuf field length" }
            position += length
        }
    }
}
