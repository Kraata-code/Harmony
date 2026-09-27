package com.kraata.harmony.service

import android.util.Log
import com.kraata.harmony.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

private const val TAG = "AcoustIdClient"
private const val CONSENSUS_MIN_SCORE = 0.90

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

    suspend fun lookup(
        fingerprint: String,
        durationMs: Long?,
        fileNameHint: String? = null,
    ): AcoustIdMatch? = withContext(Dispatchers.IO) {
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
            val body = response.body.string()
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
            parseAcoustIdResponse(
                body = body,
                durationMs = durationMs,
                fileNameHint = fileNameHint,
            )
        }
    }

    private companion object {
        const val LOOKUP_URL = "https://api.acoustid.org/v2/lookup"
    }
}

internal fun parseAcoustIdResponse(
    body: String,
    durationMs: Long? = null,
    fileNameHint: String? = null,
): AcoustIdMatch? {
    val root = JSONObject(body)
    if (root.optString("status") != "ok") {
        val message = root.optJSONObject("error")?.optString("message").orEmpty()
        error(message.ifBlank { "AcoustID returned an error" })
    }

    val results = root.optJSONArray("results") ?: return null
    val candidates = ArrayList<AcoustIdMatch>()
    val expectedDurationMs = durationMs?.takeIf { it > 0L } ?: -1L
    val maxDurationDriftMs = expectedDurationMs.takeIf { it > 0L }?.let { maxOf(10_000L, it / 20L) }
    if (BuildConfig.DEBUG) {
        Log.d(
            TAG,
            "results=${results.length()} expectedDuration=${durationMs ?: "unknown"}ms " +
                "maxDrift=${maxDurationDriftMs ?: "unchecked"}ms",
        )
    }

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
            val durationDeltaMs = recordingLengthMs
                .takeIf { it > 0L && expectedDurationMs > 0L }
                ?.let { kotlin.math.abs(it - expectedDurationMs) }
            val durationRejected = maxDurationDriftMs != null &&
                recordingLengthMs > 0L && durationDeltaMs != null && durationDeltaMs > maxDurationDriftMs
            val fieldsValid = title.isNotEmpty() && artistNames.isNotEmpty() &&
                acoustId.isNotEmpty() && recordingId.isNotEmpty()
            if (BuildConfig.DEBUG) {
                val status = when {
                    durationRejected -> "REJECTED(duration-mismatch)"
                    title.isEmpty() -> "REJECTED(missing-title)"
                    artistNames.isEmpty() -> "REJECTED(missing-artist)"
                    acoustId.isEmpty() -> "REJECTED(missing-acoustid)"
                    recordingId.isEmpty() -> "REJECTED(missing-recording-id)"
                    else -> "ELIGIBLE"
                }
                Log.d(
                    TAG,
                    "candidate score=$score recording=$recordingId title=$title " +
                        "artist=${artistNames.joinToString(", ")} " +
                        "length=${recordingLengthMs.takeIf { it > 0L } ?: "unknown"}ms " +
                    "delta=${durationDeltaMs ?: "unknown"}ms status=$status",
                )
            }
            if (!durationRejected && fieldsValid) {
                val match = AcoustIdMatch(
                    artistNames = artistNames,
                    title = title,
                    score = score,
                    acoustId = acoustId,
                    recordingId = recordingId,
                )
                candidates += match
            }
        }
    }

    val bestMatch = repeatedFilenameMatch(candidates, fileNameHint)
        ?: candidates.maxByOrNull(AcoustIdMatch::score)
    if (BuildConfig.DEBUG) {
        Log.d(
            TAG,
            "selected recording=${bestMatch?.recordingId ?: "none"} " +
                "score=${bestMatch?.score ?: "none"}",
        )
    }
    return bestMatch
}

private fun repeatedFilenameMatch(
    candidates: List<AcoustIdMatch>,
    fileNameHint: String?,
): AcoustIdMatch? {
    val repeatedGroups = candidates.groupBy(::candidateKey).values.filter { group ->
        group.distinctBy(AcoustIdMatch::acoustId).size > 1
    }
    val filenameGroups = repeatedGroups.filter { group ->
        fileNameHint != null && group.any { matchesFileName(it, fileNameHint) }
    }
    val consensusGroups = repeatedGroups.filter { group ->
        group.filter { it.score >= CONSENSUS_MIN_SCORE }
            .distinctBy(AcoustIdMatch::acoustId)
            .size > 1
    }
    val groups = filenameGroups.ifEmpty { consensusGroups }
    return groups
        .maxWithOrNull(
            compareBy<List<AcoustIdMatch>> {
                it.distinctBy(AcoustIdMatch::acoustId).size
            }.thenBy { group -> group.maxOf(AcoustIdMatch::score) },
        )
        ?.maxByOrNull(AcoustIdMatch::score)
}

private fun candidateKey(match: AcoustIdMatch): String =
    normalizedTokens("${match.artist} ${match.title}").sorted().joinToString(" ")

private fun matchesFileName(match: AcoustIdMatch, fileName: String): Boolean {
    val candidateTokens = normalizedTokens("${match.artist} ${match.title}")
    val fileTokens = normalizedTokens(fileName)
    return candidateTokens.isNotEmpty() && candidateTokens.all(fileTokens::contains)
}

private fun normalizedTokens(value: String): Set<String> =
    value.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 }
        .toSet()
