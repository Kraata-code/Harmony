package com.kraata.harmony.service

import android.util.Log
import com.kraata.harmony.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class AcoustIdMatch(
    val artistNames: List<String>,
    val title: String,
    val score: Double,
    val acoustId: String,
    val recordingId: String,
) {
    val artist: String
        get() = artistNames.joinToString(", ")
}

internal class AcoustIdClient(
    private val clientKey: String = BuildConfig.ACOUSTID_CLIENT_KEY,
) {
    private val httpClient = OkHttpClient()

    suspend fun lookup(fingerprint: String, durationMs: Long?): AcoustIdMatch? = withContext(Dispatchers.IO) {
        require(clientKey.isNotBlank()) { "AcoustID client key is not configured" }
        require(fingerprint.isNotBlank()) { "Audio fingerprint cannot be empty" }

        val requestDurationMs = durationMs ?: 1_000L
        val request = Request.Builder()
            .url(LOOKUP_URL)
            .header("User-Agent", "Harmony/${BuildConfig.VERSION_NAME}")
            .post(
                FormBody.Builder()
                    .add("client", clientKey)
                    .add("duration", maxOf(1L, requestDurationMs / 1_000L).toString())
                    .add("fingerprint", fingerprint)
                    .add("meta", "recordings")
                    .add("format", "json")
                    .build(),
            )
            .build()

        if (BuildConfig.DEBUG) Log.d(TAG, "lookup duration=${durationMs}ms")
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (BuildConfig.DEBUG) Log.d(TAG, "response HTTP ${response.code}")
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error(
                    "AcoustID request failed: HTTP ${response.code}" +
                        message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                )
            }
            parseAcoustIdResponse(body, durationMs)
        }
    }

    private companion object {
        const val LOOKUP_URL = "https://api.acoustid.org/v2/lookup"
        const val TAG = "AcoustIdClient"
    }
}

internal fun parseAcoustIdResponse(body: String, durationMs: Long? = null): AcoustIdMatch? {
    val root = JSONObject(body)
    if (root.optString("status") != "ok") {
        val message = root.optJSONObject("error")?.optString("message").orEmpty()
        error(message.ifBlank { "AcoustID returned an error" })
    }

    val results = root.optJSONArray("results") ?: return null
    var bestScore = Double.NEGATIVE_INFINITY
    var bestMatch: AcoustIdMatch? = null
    // ponytail: reject clearly wrong recordings while allowing trim/remaster drift.
    val expectedDurationMs = durationMs?.takeIf { it > 0L } ?: -1L
    val maxDurationDriftMs = expectedDurationMs.takeIf { it > 0L }?.let { maxOf(10_000L, it / 20L) }

    for (resultIndex in 0 until results.length()) {
        val result = results.optJSONObject(resultIndex) ?: continue
        val score = result.optDouble("score", 0.0)
        val acoustId = result.optString("id").trim()
        val recordings = result.optJSONArray("recordings") ?: continue
        for (recordingIndex in 0 until recordings.length()) {
            val recording = recordings.optJSONObject(recordingIndex) ?: continue
            val title = recording.optString("title").trim()
            val recordingId = recording.optString("id").trim()
            val recordingLengthMs = recording.optLong("length", -1L)
            if (maxDurationDriftMs != null && recordingLengthMs > 0L &&
                kotlin.math.abs(recordingLengthMs - expectedDurationMs) > maxDurationDriftMs
            ) {
                continue
            }
            val artists = recording.optJSONArray("artists")
            val artistNames = buildList {
                if (artists != null) {
                    for (artistIndex in 0 until artists.length()) {
                        artists.optJSONObject(artistIndex)
                            ?.optString("name")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(::add)
                    }
                }
            }
            if (score > bestScore && title.isNotEmpty() && artistNames.isNotEmpty() &&
                acoustId.isNotEmpty() && recordingId.isNotEmpty()
            ) {
                bestScore = score
                bestMatch = AcoustIdMatch(
                    artistNames = artistNames,
                    title = title,
                    score = score,
                    acoustId = acoustId,
                    recordingId = recordingId,
                )
            }
        }
    }

    return bestMatch
}
