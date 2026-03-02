package com.kraata.harmony.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `checkForUpdates detects available update from asset version when tag is build`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "build-2026-02-28_12-00-00",
                  "body": "Bug fixes",
                  "assets": [
                    {
                      "name": "Harmony-1.1.0-core-release-2.apk",
                      "browser_download_url": "https://example.com/Harmony-1.1.0-core-release-2.apk"
                    },
                    {
                      "name": "Harmony-1.1.0-full-release-2.apk",
                      "browser_download_url": "https://example.com/Harmony-1.1.0-full-release-2.apk"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val checker = createChecker(server)
        val state = checker.checkForUpdates(
            context = ApplicationProvider.getApplicationContext(),
            currentVersionName = "1.0.0"
        ).last()

        assertTrue(state is UpdateCheckState.UpdateAvailable)
        val info = (state as UpdateCheckState.UpdateAvailable).info
        assertEquals("1.1.0", info.latestVersionName)
        assertEquals(
            "https://example.com/Harmony-1.1.0-core-release-2.apk",
            info.downloadUrl
        )

        server.shutdown()
    }

    @Test
    fun `checkForUpdates returns up to date when remote version is equal`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "build-2026-02-28_12-00-00",
                  "assets": [
                    {
                      "name": "Harmony-1.1.0-core-release-2.apk",
                      "browser_download_url": "https://example.com/Harmony-1.1.0-core-release-2.apk"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val checker = createChecker(server)
        val state = checker.checkForUpdates(
            context = ApplicationProvider.getApplicationContext(),
            currentVersionName = "1.1.0"
        ).last()

        assertTrue(state is UpdateCheckState.UpToDate)
        server.shutdown()
    }

    @Test
    fun `checkForUpdates returns update available when only one apk exists`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "build-2026-02-28_12-00-00",
                  "assets": [
                    {
                      "name": "Harmony-1.1.0-full-release-2.apk",
                      "browser_download_url": "https://example.com/Harmony-1.1.0-full-release-2.apk"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val checker = createChecker(server)
        val state = checker.checkForUpdates(
            context = ApplicationProvider.getApplicationContext(),
            currentVersionName = "1.0.0"
        ).last()

        assertTrue(state is UpdateCheckState.UpdateAvailable)
        val info = (state as UpdateCheckState.UpdateAvailable).info
        assertEquals(
            "https://example.com/Harmony-1.1.0-full-release-2.apk",
            info.downloadUrl
        )
        server.shutdown()
    }

    @Test
    fun `checkForUpdates prefers universal apk when multiple assets are present`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "v1.2.0",
                  "assets": [
                    {
                      "name": "Harmony-1.2.0-core-arm64-v8a-release-3.apk",
                      "browser_download_url": "https://example.com/Harmony-1.2.0-core-arm64-v8a-release-3.apk"
                    },
                    {
                      "name": "Harmony-1.2.0-core-universal-release-3.apk",
                      "browser_download_url": "https://example.com/Harmony-1.2.0-core-universal-release-3.apk"
                    },
                    {
                      "name": "Harmony-1.2.0-full-universal-release-3.apk",
                      "browser_download_url": "https://example.com/Harmony-1.2.0-full-universal-release-3.apk"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val checker = createChecker(server)
        val state = checker.checkForUpdates(
            context = ApplicationProvider.getApplicationContext(),
            currentVersionName = "1.1.0"
        ).last()

        assertTrue(state is UpdateCheckState.UpdateAvailable)
        val info = (state as UpdateCheckState.UpdateAvailable).info
        assertEquals(
            "https://example.com/Harmony-1.2.0-core-universal-release-3.apk",
            info.downloadUrl
        )
        server.shutdown()
    }

    @Test
    fun `downloadUpdate uses explicit url and emits downloaded`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val apkBytes = "0123456789".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(apkBytes))
                .addHeader("Content-Length", apkBytes.size)
        )

        val checker = UpdateChecker("https://github.com/Kraata-code/Harmony/releases/latest/download/app-release.apk")
        val states = checker.downloadUpdate(
            context = ApplicationProvider.getApplicationContext(),
            downloadUrl = server.url("/custom.apk").toString()
        ).toList()

        assertTrue(states.any { it is DownloadState.Downloaded })
        val recordedRequest = server.takeRequest()
        assertEquals("/custom.apk", recordedRequest.path)
        server.shutdown()
    }

    @Test
    fun `downloadUpdate emits error on http failure`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))

        val checker = UpdateChecker("https://github.com/Kraata-code/Harmony/releases/latest/download/app-release.apk")
        val states = checker.downloadUpdate(
            context = ApplicationProvider.getApplicationContext(),
            downloadUrl = server.url("/missing.apk").toString()
        ).toList()

        val errorState = states.last() as DownloadState.Error
        assertTrue(errorState.exception.message?.contains("Error al descargar") == true)
        server.shutdown()
    }

    @Test
    fun `downloadUpdate emits error on empty body`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
                .addHeader("Content-Length", 0)
        )

        val checker = UpdateChecker("https://github.com/Kraata-code/Harmony/releases/latest/download/app-release.apk")
        val states = checker.downloadUpdate(
            context = ApplicationProvider.getApplicationContext(),
            downloadUrl = server.url("/empty.apk").toString()
        ).toList()

        val errorState = states.last() as DownloadState.Error
        assertTrue(errorState.exception.message?.contains("Tamaño de archivo inválido") == true)
        server.shutdown()
    }

    private fun createChecker(server: MockWebServer): UpdateChecker {
        return UpdateChecker(
            apkUrl = "https://github.com/Kraata-code/Harmony/releases/latest/download/app-release.apk",
            client = OkHttpClient.Builder().build(),
            githubApiBaseUrl = server.url("/").toString().trimEnd('/')
        )
    }
}
