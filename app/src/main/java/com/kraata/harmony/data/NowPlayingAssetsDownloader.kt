package com.kraata.harmony.data

import com.kraata.harmony.BuildConfig
import com.harmony.music.identifier.NativeNowPlayingMatcher
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class NowPlayingAssetsDownloader(
        private val client: OkHttpClient = defaultClient(),
        private val releaseBaseUrl: String = BuildConfig.NOW_PLAYING_BASE_URL,
) {
    enum class Component(
        val directory: String,
        val archiveName: String,
        val approximateSizeMb: Int,
    ) {
        CORE(NativeNowPlayingMatcher.CORE_COMPONENT, "harmony-now-playing-core.zip", 52),
        MX(NativeNowPlayingMatcher.MX_SHARD_GROUP, "harmony-now-playing-mx.zip", 211),
        US_XA(NativeNowPlayingMatcher.US_XA_SHARD_GROUP, "harmony-now-playing-us-xa.zip", 213),
    }

    fun isInstalled(context: android.content.Context, component: Component): Boolean =
        NativeNowPlayingMatcher.isComponentInstalled(context, component.directory)

    fun download(context: android.content.Context, component: Component): Flow<Int> = channelFlow {
        withContext(Dispatchers.IO) {
            downloadBlocking(context, component) { progress ->
                trySend(progress)
            }
        }
    }

    private fun downloadBlocking(
        context: android.content.Context,
        component: Component,
        onProgress: (Int) -> Unit,
    ) {
        onProgress(0)

        val destination = NativeNowPlayingMatcher.componentDirectory(context, component.directory)
        val root = requireNotNull(destination.parentFile)
        root.mkdirs()
        val staging = File(root, ".${component.directory}.staging")
        val backup = File(root, ".${component.directory}.backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        staging.mkdirs()

        try {
            val request = Request.Builder()
                .url("${releaseBaseUrl.trimEnd('/')}/${component.archiveName}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Now Playing download failed: HTTP ${response.code}" }
                val body = requireNotNull(response.body) { "Now Playing download returned an empty body" }
                val totalBytes = body.contentLength()
                val input = ProgressInputStream(body.byteStream(), totalBytes) { progress ->
                    onProgress(progress)
                }

                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val name = entry.name
                            require(name.isNotBlank() && name != NativeNowPlayingMatcher.COMPLETE_MARKER) {
                                "Invalid Now Playing archive entry"
                            }
                            val target = safeTarget(staging, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }

            check(isComplete(component, staging)) { "Now Playing archive is incomplete" }
            File(staging, NativeNowPlayingMatcher.COMPLETE_MARKER).writeText("1")

            if (destination.exists() && !destination.renameTo(backup)) {
                error("Could not replace the existing Now Playing component")
            }
            if (!staging.renameTo(destination)) {
                backup.renameTo(destination)
                error("Could not install the Now Playing component")
            }
            backup.deleteRecursively()
            onProgress(100)
        } finally {
            staging.deleteRecursively()
            backup.deleteRecursively()
        }
    }

    private fun isComplete(component: Component, directory: File): Boolean = when (component) {
        Component.CORE -> listOf(
            NativeNowPlayingMatcher.CORE_DATABASE,
            NativeNowPlayingMatcher.CONFIG_FILE,
        ).all { File(directory, it).isFile && File(directory, it).length() > 0 }
        else -> directory.listFiles().orEmpty().any {
            it.isFile && it.name != NativeNowPlayingMatcher.COMPLETE_MARKER && it.length() > 0
        }
    }

    private fun safeTarget(root: File, entryName: String): File {
        val target = File(root, entryName)
        require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Invalid Now Playing archive path"
        }
        return target
    }

    private class ProgressInputStream(
        input: InputStream,
        private val totalBytes: Long,
        private val onProgress: (Int) -> Unit,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L
        private var lastProgress = -1

        override fun read(): Int = super.read().also { countProgress(if (it == -1) 0 else 1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { countProgress(it.coerceAtLeast(0)) }

        private fun countProgress(count: Int) {
            if (count == 0 || totalBytes <= 0) return
            bytesRead += count
            val progress = (bytesRead * 100 / totalBytes).toInt().coerceAtMost(100)
            if (progress != lastProgress) {
                lastProgress = progress
                onProgress(progress)
            }
        }
    }

    companion object {
        fun totalApproximateSize(components: Collection<Component>): String =
            "${components.sumOf { it.approximateSizeMb }} MB"

        fun defaultShardGroup(): String = if (Locale.getDefault().country.equals("MX", ignoreCase = true)) {
            NativeNowPlayingMatcher.MX_SHARD_GROUP
        } else {
            NativeNowPlayingMatcher.US_XA_SHARD_GROUP
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
