package com.kraata.harmony.service

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
                  "isrcs": ["US-AAA-24-00001"]
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
