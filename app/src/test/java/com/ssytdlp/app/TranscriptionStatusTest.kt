package com.ssytdlp.app

import com.ssytdlp.app.core.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptionStatusTest {
    @Test fun statusLabelsMatchTheServerAndUnknownStatesAreHidden() {
        assertEquals("Transcription request sent", Transcription(status = "sent").label)
        assertEquals("AI transcription", Transcription(status = "transcribed").label)
        assertEquals("Lyrics included", Transcription(status = "transcribed", lyricsIncluded = true).label)
        assertEquals("Transcription failed", Transcription(status = "failed").label)
        assertEquals("Interrupted", Transcription(status = "interrupted").label)
        assertNull(Transcription().label)
        assertNull(Transcription(status = "future").label)
    }

    @Test fun tooltipIncludesSavedSettingsTimestampsAndErrorInServerOrder() {
        val record = Transcription("failed", "start", "end", true,
            SavedTranscriptionOptions("vi", false, true, true, "align"), "Service unavailable")
        assertEquals("""
            Requested: start
            Finished: end
            Language: Vietnamese
            Multilingual: Off
            No vocals (karaoke): On
            Viet Lyrics Fallback: On
            Add lyrics: Yes
            Lyrics mode: Align
            Service unavailable
        """.trimIndent(), record.tooltip { it.orEmpty() })
    }

    @Test fun legacyRecordsDoNotInventOptionsAndEmptyOptionsUseServiceDefaults() {
        assertEquals("Requested: Unknown", Transcription(status = "sent").tooltip())
        assertEquals("""
            Requested: Unknown
            Language: Auto-detect
            Multilingual: Service default
            No vocals (karaoke): Service default
            Viet Lyrics Fallback: Service default
            Add lyrics: No
        """.trimIndent(), Transcription(status = "sent", options = SavedTranscriptionOptions()).tooltip())
    }

    @Test fun unknownSettingsAndMalformedDatesRemainReadableAndLyricsAreNeverDisplayed() {
        val record = ApiJson.decodeFromString<Transcription>("""{
            "status":"transcribed","requestedAt":"invalid date","lyricsIncluded":true,
            "options":{"language":"future-language","lyrics_mode":"future-mode","lyrics":"private lyrics"}
        }""")
        val tooltip = record.tooltip()
        assertTrue(tooltip.contains("Requested: invalid date"))
        assertTrue(tooltip.contains("Language: future-language"))
        assertTrue(tooltip.contains("Lyrics mode: future-mode"))
        assertFalse(tooltip.contains("private lyrics"))
        assertFalse(tooltip.contains("Finished:"))
        assertFalse(Transcription(requestedAt = "2026-09-28T12:00:00Z").tooltip().contains("Unknown"))
    }

    @Test fun linkedTracksUseSourceJobAndExactFilenameWithLocalPendingPrecedence() {
        val complete = Transcription(status = "transcribed")
        val failure = Transcription(status = "failed")
        val track = Track(jobId = "source", name = "folder/song.mp3", playlistId = "destination")
        val state = LibraryState(library = Library(jobs = listOf(
            Job(id = "source", transcriptions = mapOf(track.name to complete)),
            Job(id = "destination", transcriptions = mapOf(track.name to failure))
        )))
        assertEquals(complete, state.transcription(track))
        assertNull(state.transcription(track.copy(name = "song.mp3")))
        assertNull(state.transcription(track.copy(jobId = "other")))
        val pending = Transcription(status = "sent")
        val submitting = state.copy(pendingTranscriptions = mapOf(track.key to pending))
        assertEquals(pending, submitting.transcription(track))
        assertEquals(complete, submitting.copy(pendingTranscriptions = emptyMap()).transcription(track))
    }
}
