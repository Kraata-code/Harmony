package com.kraata.harmony.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.harmony.music.identifier.NativeNowPlayingMatcher
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingAssetsDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        NativeNowPlayingMatcher.componentDirectory(context, NativeNowPlayingMatcher.CORE_COMPONENT)
            .parentFile
            ?.deleteRecursively()
    }

    @After
    fun tearDown() {
        NativeNowPlayingMatcher.componentDirectory(context, NativeNowPlayingMatcher.CORE_COMPONENT)
            .parentFile
            ?.deleteRecursively()
        server.shutdown()
    }

    @Test
    fun installsCoreAndSelectedRegionsFromArchives() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                zip(
                    NativeNowPlayingMatcher.CORE_DATABASE to "database",
                    NativeNowPlayingMatcher.CONFIG_FILE to "config",
                ),
            ),
        )
        server.enqueue(MockResponse().setBody(zip("MXshard" to "shard")))
        server.enqueue(MockResponse().setBody(zip("USXA shard" to "shard")))
        val downloader = NowPlayingAssetsDownloader(
            client = OkHttpClient(),
            releaseBaseUrl = server.url("/").toString().trimEnd('/'),
        )

        downloader.download(context, NowPlayingAssetsDownloader.Component.CORE).collect()
        downloader.download(context, NowPlayingAssetsDownloader.Component.MX).collect()
        downloader.download(context, NowPlayingAssetsDownloader.Component.US_XA).collect()

        assertTrue(downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE))
        assertTrue(downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.MX))
        assertTrue(downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.US_XA))
        assertTrue(NativeNowPlayingMatcher.isReady(context))
    }

    @Test
    fun deletesOnlyTheRequestedRecognitionComponent() = runBlocking {
        val mxDirectory = NativeNowPlayingMatcher.componentDirectory(
            context,
            NativeNowPlayingMatcher.MX_SHARD_GROUP,
        )
        val usXaDirectory = NativeNowPlayingMatcher.componentDirectory(
            context,
            NativeNowPlayingMatcher.US_XA_SHARD_GROUP,
        )
        listOf(mxDirectory, usXaDirectory).forEach { directory ->
            directory.mkdirs()
            directory.resolve("data").writeText("data")
        }

        NowPlayingAssetsDownloader().delete(context, NowPlayingAssetsDownloader.Component.MX)

        assertFalse(mxDirectory.exists())
        assertTrue(usXaDirectory.exists())
    }

    @Test
    fun coreCanBeDeletedOnlyAfterRegionsAreGone() = runBlocking {
        val coreDirectory = NativeNowPlayingMatcher.componentDirectory(
            context,
            NativeNowPlayingMatcher.CORE_COMPONENT,
        )
        val mxDirectory = NativeNowPlayingMatcher.componentDirectory(
            context,
            NativeNowPlayingMatcher.MX_SHARD_GROUP,
        )
        coreDirectory.mkdirs()
        coreDirectory.resolve(NativeNowPlayingMatcher.CORE_DATABASE).writeText("database")
        coreDirectory.resolve(NativeNowPlayingMatcher.CONFIG_FILE).writeText("config")
        coreDirectory.resolve(NativeNowPlayingMatcher.COMPLETE_MARKER).writeText("1")
        mxDirectory.mkdirs()
        mxDirectory.resolve(NativeNowPlayingMatcher.COMPLETE_MARKER).writeText("1")
        mxDirectory.resolve("data").writeText("data")

        var failed = false
        try {
            NowPlayingAssetsDownloader().delete(context, NowPlayingAssetsDownloader.Component.CORE)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(coreDirectory.exists())

        val downloader = NowPlayingAssetsDownloader()
        downloader.delete(context, NowPlayingAssetsDownloader.Component.MX)
        downloader.delete(context, NowPlayingAssetsDownloader.Component.CORE)
        assertFalse(coreDirectory.exists())
    }

    @Test
    fun rejectsArchivePathTraversal() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("../outside" to "bad")))
        val downloader = NowPlayingAssetsDownloader(
            client = OkHttpClient(),
            releaseBaseUrl = server.url("/").toString().trimEnd('/'),
        )

        var failed = false
        try {
            downloader.download(context, NowPlayingAssetsDownloader.Component.CORE).collect()
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(downloader.isInstalled(context, NowPlayingAssetsDownloader.Component.CORE))
    }

    @Test
    fun sumsApproximateSizes() {
        assertEquals(
            "424 MB",
            NowPlayingAssetsDownloader.totalApproximateSize(
                listOf(
                    NowPlayingAssetsDownloader.Component.MX,
                    NowPlayingAssetsDownloader.Component.US_XA,
                ),
            ),
        )
    }

    private fun zip(vararg entries: Pair<String, String>): Buffer {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return Buffer().write(bytes.toByteArray())
    }
}
