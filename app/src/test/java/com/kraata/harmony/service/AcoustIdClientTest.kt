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
}
