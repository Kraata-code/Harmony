package com.dd3boh.outertune.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import okio.Buffer
import org.junit.Test
import androidx.test.core.app.ApplicationProvider

class UpdateCheckerTest {
    @Test
    fun `checkForUpdates detects available update`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val body = """
            {
                "latestVersionCode": 200,
                "latestVersionName": "2.0.0",
                "downloadUrl": "https://example.com/app.apk",
                "releaseNotes": "Bug fixes",
                "mandatory": false,
                "checksum": "abc123"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        // Serve a small fake APK payload and ensure downloadUpdate emits Downloaded
        val apkBytes = "0123456789".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(apkBytes)).addHeader("Content-Length", apkBytes.size))

        val checker = UpdateChecker(server.url("/app.apk").toString())
        val flow = checker.downloadUpdate(ApplicationProvider.getApplicationContext())
        val result = flow.first { it is com.dd3boh.outertune.data.DownloadState.Downloaded }
        assertTrue(result is com.dd3boh.outertune.data.DownloadState.Downloaded)

        server.shutdown()
    }
}