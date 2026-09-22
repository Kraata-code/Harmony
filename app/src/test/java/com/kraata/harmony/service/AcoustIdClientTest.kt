package com.kraata.harmony.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcoustIdClientTest {
    @Test
    fun parsesHighestScoredRecording() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-low", "score": 0.4, "recordings": [{"id": "recording-low", "title": "Low", "artists": [{"name": "Artist"}]}]},
                {"id": "acoust-high", "score": 0.9, "recordings": [{"id": "recording-high", "title": "High", "artists": [{"name": "Artist"}]}]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Artist", match?.artist)
        assertEquals(listOf("Artist"), match?.artistNames)
        assertEquals("High", match?.title)
        assertEquals(0.9, match?.score)
        assertEquals("acoust-high", match?.acoustId)
        assertEquals("recording-high", match?.recordingId)
    }

    @Test
    fun returnsNullWhenThereAreNoRecordings() {
        assertNull(parseAcoustIdResponse("""{"status":"ok","results":[]}"""))
    }

    @Test
    fun ignoresHighestScoredRecordingWhenDurationDoesNotMatch() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-wrong", "score": 0.99, "recordings": [{"id": "recording-wrong", "length": 139706, "title": "Wrong", "artists": [{"name": "Artist"}]}]},
                {"id": "acoust-right", "score": 0.9, "recordings": [{"id": "recording-right", "length": 242000, "title": "Right", "artists": [{"name": "Artist"}]}]}
              ]
            }
            """.trimIndent(),
            durationMs = 242996L,
        )

        assertEquals("recording-right", match?.recordingId)
    }

    @Test
    fun acceptsHigherScoreWhenRecordingDurationIsMissing() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-unknown", "score": 0.99, "recordings": [{"id": "recording-unknown", "title": "Unknown", "artists": [{"name": "Artist"}]}]},
                {"id": "acoust-known", "score": 0.9, "recordings": [{"id": "recording-known", "length": 242000, "title": "Known", "artists": [{"name": "Artist"}]}]}
              ]
            }
            """.trimIndent(),
            durationMs = 242996L,
        )

        assertEquals("recording-unknown", match?.recordingId)
    }

    @Test
    fun acceptsSingleHighConfidenceRecordingWithoutDuration() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-one", "score": 0.956, "recordings": [
                  {"id": "recording-one", "title": "Song", "artists": [{"name": "Artist"}]}
                ]}
              ]
            }
            """.trimIndent(),
            durationMs = 222_888L,
        )

        assertEquals("recording-one", match?.recordingId)
    }

    @Test
    fun prefersRepeatedFilenameCandidateWhenDurationsAreMissing() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-one", "score": 0.97, "recordings": [
                  {"id": "recording-wrong", "title": "Little Red Rooster", "artists": [{"name": "The Doors"}]},
                  {"id": "recording-right-one", "title": "Alabama Song (Whisky Bar)", "artists": [{"name": "The Doors"}]}
                ]},
                {"id": "acoust-two", "score": 0.96, "recordings": [
                  {"id": "recording-right-two", "title": "Alabama Song (Whisky Bar)", "artists": [{"name": "The Doors"}]}
                ]}
              ]
            }
            """.trimIndent(),
            durationMs = 197544L,
            fileNameHint = "The_Doors_-_Whisky_Bar_(Alabama_Song_)(256k).mp3",
        )

        assertEquals("Alabama Song (Whisky Bar)", match?.title)
    }

    @Test
    fun countsAcoustIdResultsInsteadOfRecordingsAsVotes() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-wrong", "score": 0.99, "recordings": [
                  {"id": "recording-wrong-one", "title": "Wrong Song", "artists": [{"name": "Artist"}]},
                  {"id": "recording-wrong-two", "title": "Wrong Song", "artists": [{"name": "Artist"}]},
                  {"id": "recording-wrong-three", "title": "Wrong Song", "artists": [{"name": "Artist"}]}
                ]},
                {"id": "acoust-right-one", "score": 0.96, "recordings": [
                  {"id": "recording-right-one", "title": "Right Song", "artists": [{"name": "Artist"}]}
                ]},
                {"id": "acoust-right-two", "score": 0.95, "recordings": [
                  {"id": "recording-right-two", "title": "Right Song", "artists": [{"name": "Artist"}]}
                ]}
              ]
            }
            """.trimIndent(),
            durationMs = 200_000L,
            fileNameHint = "track-01.mp3",
        )

        assertEquals("Right Song", match?.title)
    }

    @Test
    fun usesRepeatedHighConfidenceCandidateWhenFilenameDoesNotMatch() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [
                {"id": "acoust-one", "score": 0.97, "recordings": [
                  {"id": "recording-one", "title": "A Hard Day's Night", "artists": [{"name": "The Beatles"}]}
                ]},
                {"id": "acoust-two", "score": 0.96, "recordings": [
                  {"id": "recording-two", "title": "A Hard Day's Night", "artists": [{"name": "The Beatles"}]}
                ]}
              ]
            }
            """.trimIndent(),
            durationMs = 154224L,
            fileNameHint = "track-01.mp3",
        )

        assertEquals("A Hard Day's Night", match?.title)
        assertEquals("The Beatles", match?.artist)
    }

    @Test
    fun acceptsAcoustIdMetadataWithoutNativeIdentityFilter() {
        val match = parseAcoustIdResponse(
            """
            {
              "status": "ok",
              "results": [{"id": "acoust-one", "score": 0.9858, "recordings": [
                {"id": "recording-one", "title": "ばかみたい【Taxi Driver Edition】", "artists": [{"name": "桐生一馬"}, {"name": "黒田崇矢"}]}
              ]}]
            }
            """.trimIndent(),
        )

        assertEquals("ばかみたい【Taxi Driver Edition】", match?.title)
        assertEquals("桐生一馬, 黒田崇矢", match?.artist)
        assertEquals(0.9858, match?.score)
        assertEquals("acoust-one", match?.acoustId)
    }

    @Test
    fun fallbackMetadataUsesAcoustIdFieldsOnly() {
        val metadata = AcoustIdMatch(
            artistNames = listOf("The Doors"),
            title = "Alabama Song (Whisky Bar)",
            score = 0.97,
            acoustId = "acoust-id",
            recordingId = "recording-id",
        ).toFallbackMetadata()

        assertEquals("Alabama Song (Whisky Bar)", metadata.title)
        assertEquals(listOf("The Doors"), metadata.artists)
        assertEquals("recording-id", metadata.recordingId)
        assertNull(metadata.album)
        assertEquals(emptyList<String>(), metadata.genres)
    }
}
