package com.kraata.harmony.service

import android.os.SystemClock
import com.kraata.harmony.BuildConfig
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class MusicBrainzMetadata(
    val title: String,
    val artists: List<String>,
    val artistIds: List<String>,
    val album: String?,
    val albumArtists: List<String>,
    val albumArtistIds: List<String>,
    val genres: List<String>,
    val date: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val label: String?,
    val catalogNumber: String?,
    val barcode: String?,
    val isrcs: List<String>,
    val recordingId: String,
    val releaseId: String?,
    val releaseGroupId: String?,
    val releaseCountry: String?,
    val releaseStatus: String?,
    val releaseType: String?,
    val media: String?,
    val coverArtReleaseIds: List<String> = emptyList(),
)

internal class MusicMetadataClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun lookup(recordingId: String): MusicBrainzMetadata = withContext(Dispatchers.IO) {
        require(recordingId.isNotBlank()) { "MusicBrainz recording id is missing" }

        val recording = getJson(
            "https://musicbrainz.org/ws/2/recording/$recordingId" +
                "?inc=artist-credits+releases+genres+isrcs+work-rels+work-level-rels&fmt=json",
        )
        val release = chooseRelease(recording.optJSONArray("releases"))
        val releaseDetails = release?.optString("id")?.takeIf { it.isNotBlank() }?.let { releaseId ->
            try {
                withTimeoutOrNull(RELEASE_DETAILS_TIMEOUT_MS.milliseconds) {
                    getJson(
                        "https://musicbrainz.org/ws/2/release/$releaseId" +
                            "?inc=artist-credits+media+labels+release-groups+genres&fmt=json",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        }

        parseMusicBrainzMetadata(recording, releaseDetails ?: release, recordingId)
    }

    suspend fun lookupByTitleAndArtist(title: String, artist: String): MusicBrainzMetadata? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || artist.isBlank()) return@withContext null

            val query = "recording:\"${escapeQueryValue(title)}\" " +
                "AND artist:\"${escapeQueryValue(artist)}\""
            val search = getJson(
                "https://musicbrainz.org/ws/2/recording?query=" +
                    "${URLEncoder.encode(query, Charsets.UTF_8.name())}&limit=5&fmt=json",
            )
            val recordingId = search.optJSONArray("recordings")
                ?.let { recordings ->
                    (0 until recordings.length())
                        .mapNotNull { recordings.optJSONObject(it) }
                        .filter { it.optInt("score", 0) >= MIN_RECORDING_SEARCH_SCORE }
                        .maxByOrNull { it.optInt("score", 0) }
                        ?.optString("id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            recordingId?.let { lookup(it) }
        }

    suspend fun downloadCover(metadata: MusicBrainzMetadata): ByteArray? = withContext(Dispatchers.IO) {
        val urls = buildList {
            metadata.releaseId?.takeIf { it.isNotBlank() }?.let {
                add("https://coverartarchive.org/release/$it/front-500")
            }
            metadata.releaseGroupId?.takeIf { it.isNotBlank() }?.let {
                add("https://coverartarchive.org/release-group/$it/front-500")
            }
            metadata.coverArtReleaseIds
                .asSequence()
                .filterNot { it == metadata.releaseId }
                .take(MAX_ALTERNATIVE_COVER_RELEASES)
                .map { "https://coverartarchive.org/release/$it/front-500" }
                .forEach(::add)
        }

        for (url in urls) {
            val bytes = try {
                httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .build(),
                ).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (bytes != null && bytes.isNotEmpty()) return@withContext bytes
        }

        null
    }

    private suspend fun getJson(url: String): JSONObject = requestMutex.withLock {
        repeat(MAX_ATTEMPTS) { attempt ->
            val elapsed = SystemClock.elapsedRealtime() - lastRequestAt
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                delay((MIN_REQUEST_INTERVAL_MS - elapsed).milliseconds)
            }

            lastRequestAt = SystemClock.elapsedRealtime()
            var retryAfterMillis = 0L
            val result = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build(),
            ).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    JSONObject(body)
                } else {
                    retryAfterMillis = response.header("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1_000L)
                        ?: 0L
                    if (response.code !in RETRYABLE_CODES || attempt == MAX_ATTEMPTS - 1) {
                        error("MusicBrainz request failed: HTTP ${response.code} ($url)")
                    }
                    null
                }
            }

            if (result != null) return@withLock result
            if (retryAfterMillis > 0) delay(retryAfterMillis.milliseconds)
        }

        error("MusicBrainz request failed: $url")
    }

    private fun chooseRelease(releases: JSONArray?): JSONObject? {
        if (releases == null) return null
        val values = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
        return values.firstOrNull { it.optString("status").equals("Official", ignoreCase = true) }
            ?: values.firstOrNull()
    }

    private companion object {
        val requestMutex = Mutex()
        var lastRequestAt = 0L
        val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
        const val MAX_ATTEMPTS = 2
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val RELEASE_DETAILS_TIMEOUT_MS = 10_000L
        const val MAX_ALTERNATIVE_COVER_RELEASES = 3
        const val MIN_RECORDING_SEARCH_SCORE = 80
        const val USER_AGENT = "Harmony/${BuildConfig.VERSION_NAME} (local metadata updater)"
    }
}

private fun escapeQueryValue(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

internal fun parseMusicBrainzMetadata(
    recording: JSONObject,
    release: JSONObject?,
    recordingId: String,
): MusicBrainzMetadata {
    val recordingArtists = parseArtistCredit(recording.optJSONArray("artist-credit"))
    val releaseArtists = parseArtistCredit(release?.optJSONArray("artist-credit"))
    val genres = (parseNames(recording.optJSONArray("genres")) + parseNames(release?.optJSONArray("genres")))
        .distinct()
    val releaseGroup = release?.optJSONObject("release-group")
    val track = findTrack(release, recordingId)
    val releaseId = release?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
    val coverArtReleaseIds = buildList {
        releaseId?.let(::add)
        recording.optJSONArray("releases")?.let { releases ->
            for (index in 0 until releases.length()) {
                releases.optJSONObject(index)
                    ?.optString("id")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
            }
        }
    }.distinct()
    val labelInfo = release?.optJSONArray("label-info")?.optJSONObject(0)
    val label = labelInfo?.optJSONObject("label")?.optString("name")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val title = translatedOriginalTitle(recording)
        ?: recording.optString("title").trim().ifBlank { error("MusicBrainz title is missing") }

    return MusicBrainzMetadata(
        title = title,
        artists = recordingArtists.names.ifEmpty { releaseArtists.names },
        artistIds = recordingArtists.ids.ifEmpty { releaseArtists.ids },
        album = release?.optString("title")?.trim()?.takeIf { it.isNotEmpty() },
        albumArtists = releaseArtists.names.ifEmpty { recordingArtists.names },
        albumArtistIds = releaseArtists.ids.ifEmpty { recordingArtists.ids },
        genres = genres,
        date = release?.optString("date")?.trim()?.takeIf { it.isNotEmpty() },
        trackNumber = track?.first,
        discNumber = track?.second,
        label = label,
        catalogNumber = labelInfo?.optString("catalog-number")?.trim()?.takeIf { it.isNotEmpty() },
        barcode = release?.optString("barcode")?.trim()?.takeIf { it.isNotEmpty() },
        isrcs = parseStrings(recording.optJSONArray("isrcs")),
        recordingId = recording.optString("id").trim().ifBlank { recordingId },
        releaseId = releaseId,
        releaseGroupId = releaseGroup?.optString("id")?.trim()?.takeIf { it.isNotEmpty() },
        releaseCountry = release?.optString("country")?.trim()?.takeIf { it.isNotEmpty() },
        releaseStatus = release?.optString("status")?.trim()?.takeIf { it.isNotEmpty() },
        releaseType = releaseGroup?.optString("primary-type")?.trim()?.takeIf { it.isNotEmpty() },
        media = track?.third,
        coverArtReleaseIds = coverArtReleaseIds,
    )
}

private fun translatedOriginalTitle(recording: JSONObject): String? {
    val relations = recording.optJSONArray("relations") ?: return null
    for (relationIndex in 0 until relations.length()) {
        val work = relations.optJSONObject(relationIndex)
            ?.takeIf { it.optString("target-type") == "work" }
            ?.optJSONObject("work")
            ?: continue
        val workRelations = work.optJSONArray("relations") ?: continue
        for (workRelationIndex in 0 until workRelations.length()) {
            val relation = workRelations.optJSONObject(workRelationIndex) ?: continue
            if (relation.optString("type") != "other version" ||
                relation.optString("direction") != "backward" ||
                "translated" !in parseStrings(relation.optJSONArray("attributes"))
            ) {
                continue
            }
            return relation.optJSONObject("work")
                ?.optString("title")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}

private data class ArtistCredit(
    val names: List<String>,
    val ids: List<String>,
)

private fun parseArtistCredit(credits: JSONArray?): ArtistCredit {
    if (credits == null) return ArtistCredit(emptyList(), emptyList())

    val names = ArrayList<String>()
    val ids = ArrayList<String>()
    for (index in 0 until credits.length()) {
        val credit = credits.optJSONObject(index) ?: continue
        val artist = credit.optJSONObject("artist")
        val name = (credit.optString("name").ifBlank { artist?.optString("name").orEmpty() })
            .trim()
        if (name.isNotEmpty()) names += name
        artist?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }?.let { ids += it }
    }
    return ArtistCredit(names.distinct(), ids.distinct())
}

private fun parseNames(values: JSONArray?): List<String> {
    if (values == null) return emptyList()
    return (0 until values.length()).mapNotNull { index ->
        values.optJSONObject(index)
            ?.optString("name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.distinct()
}

private fun parseStrings(values: JSONArray?): List<String> {
    if (values == null) return emptyList()
    return (0 until values.length()).mapNotNull { index ->
        values.optString(index).trim().takeIf { it.isNotEmpty() }
    }.distinct()
}

private fun findTrack(release: JSONObject?, recordingId: String): Triple<Int?, Int?, String?>? {
    val media = release?.optJSONArray("media") ?: return null
    for (mediaIndex in 0 until media.length()) {
        val medium = media.optJSONObject(mediaIndex) ?: continue
        val tracks = medium.optJSONArray("tracks") ?: continue
        for (trackIndex in 0 until tracks.length()) {
            val track = tracks.optJSONObject(trackIndex) ?: continue
            if (track.optJSONObject("recording")?.optString("id") != recordingId) continue
            val trackNumber = track.optInt("position", 0).takeIf { it > 0 }
            val discNumber = medium.optInt("position", mediaIndex + 1).takeIf { it > 0 }
            val mediaTitle = medium.optString("title").trim().takeIf { it.isNotEmpty() }
            return Triple(trackNumber, discNumber, mediaTitle)
        }
    }
    return null
}
