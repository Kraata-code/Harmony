package com.kraata.harmony.service

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicMetadataClientTest {
    @Test
    fun prefersOriginalWorkTitleForTranslatedRecording() {
        val metadata = parseMusicBrainzMetadata(
            recording = JSONObject(
                """
                {
                  "id": "recording-id",
                  "title": "Sie liebt dich",
                  "artist-credit": [{"name": "The Beatles"}],
                  "relations": [{
                    "target-type": "work",
                    "work": {
                      "title": "Sie liebt dich",
                      "relations": [{
                        "type": "other version",
                        "direction": "backward",
                        "attributes": ["translated"],
                        "work": {"title": "She Loves You"}
                      }]
                    }
                  }]
                }
                """.trimIndent(),
            ),
            release = null,
            recordingId = "recording-id",
        )

        assertEquals("She Loves You", metadata.title)
    }

    @Test
    fun parsesRecordingAndReleaseMetadata() {
        val metadata = parseMusicBrainzMetadata(
            recording = JSONObject(
                """
                {
                  "id": "recording-id",
                  "title": "Song title",
                  "artist-credit": [
                    {"name": "Artist One", "artist": {"id": "artist-one", "name": "Artist One"}},
                    {"name": "Artist Two", "artist": {"id": "artist-two", "name": "Artist Two"}}
                  ],
                  "genres": [{"name": "Rock"}],
                  "isrcs": ["US-AAA-24-00001"],
                  "releases": [
                    {"id": "release-id"},
                    {"id": "alternate-release-id"},
                    {"id": "release-id"}
                  ]
                }
                """.trimIndent(),
            ),
            release = JSONObject(
                """
                {
                  "id": "release-id",
                  "title": "Album title",
                  "date": "2024-05-06",
                  "country": "ES",
                  "status": "Official",
                  "artist-credit": [
                    {"name": "Artist One", "artist": {"id": "artist-one"}}
                  ],
                  "genres": [{"name": "Alternative"}],
                  "label-info": [{"catalog-number": "CAT-01", "label": {"name": "Label"}}],
                  "barcode": "1234567890123",
                  "release-group": {"id": "release-group-id", "primary-type": "Album"},
                  "media": [{
                    "position": 2,
                    "title": "Disc two",
                    "tracks": [{"position": 3, "recording": {"id": "recording-id"}}]
                  }]
                }
                """.trimIndent(),
            ),
            recordingId = "recording-id",
        )

        assertEquals("Song title", metadata.title)
        assertEquals(listOf("Artist One", "Artist Two"), metadata.artists)
        assertEquals(listOf("artist-one", "artist-two"), metadata.artistIds)
        assertEquals("Album title", metadata.album)
        assertEquals(listOf("Artist One"), metadata.albumArtists)
        assertEquals(listOf("Rock", "Alternative"), metadata.genres)
        assertEquals("2024-05-06", metadata.date)
        assertEquals(3, metadata.trackNumber)
        assertEquals(2, metadata.discNumber)
        assertEquals("Disc two", metadata.media)
        assertEquals("Label", metadata.label)
        assertEquals("CAT-01", metadata.catalogNumber)
        assertEquals("1234567890123", metadata.barcode)
        assertEquals(listOf("US-AAA-24-00001"), metadata.isrcs)
        assertEquals("release-id", metadata.releaseId)
        assertEquals("release-group-id", metadata.releaseGroupId)
        assertEquals(listOf("release-id", "alternate-release-id"), metadata.coverArtReleaseIds)
    }

    @Test
    fun downloadsCoverFromAnAlternateRelease() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requestedPaths += path
                val found = path == "/release/alternate-release-id/front-500"
                val code = when {
                    found -> 200
                    path == "/release/release-id/front-500" -> 503
                    else -> 404
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (found) "OK" else "Not Found")
                    .body(
                        if (found) {
                            "cover".toByteArray().toResponseBody("image/jpeg".toMediaType())
                        } else {
                            ByteArray(0).toResponseBody(null)
                        },
                    )
                    .build()
            }
            .build()
        val metadata = parseMusicBrainzMetadata(
            recording = JSONObject(
                """
                {
                  "id": "recording-id",
                  "title": "Song",
                  "artist-credit": [{"name": "Artist"}],
                  "releases": [{"id": "release-id"}, {"id": "alternate-release-id"}]
                }
                """.trimIndent(),
            ),
            release = JSONObject(
                """
                {"id": "release-id", "release-group": {"id": "release-group-id"}}
                """.trimIndent(),
            ),
            recordingId = "recording-id",
        )

        val cover = MusicMetadataClient(client).downloadCover(metadata)

        assertEquals("cover", cover?.toString(Charsets.UTF_8))
        assertEquals(
            listOf(
                "/release/release-id/front-500",
                "/release-group/release-group-id/front-500",
                "/release/alternate-release-id/front-500",
            ),
            requestedPaths,
        )
    }

    @Test
    fun keepsMissingOptionalReleaseDataEmpty() {
        val metadata = parseMusicBrainzMetadata(
            recording = JSONObject(
                """{"id":"recording-id","title":"Song","artist-credit":[{"name":"Artist"}]}""",
            ),
            release = null,
            recordingId = "recording-id",
        )

        assertEquals("Song", metadata.title)
        assertEquals(listOf("Artist"), metadata.artists)
        assertNull(metadata.album)
        assertNull(metadata.releaseId)
        assertEquals(emptyList<String>(), metadata.genres)
    }
}
