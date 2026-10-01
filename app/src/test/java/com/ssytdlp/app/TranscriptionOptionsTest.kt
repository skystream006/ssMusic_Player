package com.ssytdlp.app

import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.SavedTranscriptionOptions
import com.ssytdlp.app.core.Transcription
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.*
import org.junit.Test

class TranscriptionOptionsTest {
    @Test fun noVocalsOnlyIgnoresAndOmitsEveryOrdinaryOption() {
        val options = TranscriptionOptions(language = "invalid", multilingual = true, noVocals = true,
            vietLyricsFallback = true, addLyrics = true, lyricsMode = "invalid", lyrics = " ".repeat(100_001),
            noVocalsOnly = true)
        assertTrue(options.isValid)
        assertEquals("""{"NoVocalsOnly":true}""", options.toRequestBody().toString())
        assertEquals(SavedTranscriptionOptions(noVocalsOnly = true),
            ApiJson.decodeFromJsonElement<SavedTranscriptionOptions>(options.toRequestBody()))
        assertFalse(options.copy(noVocalsOnly = false).isValid)
    }

    @Test fun normalDefaultsKeepTheExistingRequestAndDoNotSendNoVocalsOnly() {
        assertEquals("""{"Multilingual":false,"NoVocals":false,"VietLyricsFallback":false}""",
            TranscriptionOptions().toRequestBody().toString())
    }

    @Test fun normalTranscriptionKeepsLanguageLyricsAndKaraokeOptions() {
        val options = TranscriptionOptions(language = "en", multilingual = true, noVocals = true,
            vietLyricsFallback = true, addLyrics = true, lyricsMode = "correct", lyrics = "  Lyrics\n ")
        assertTrue(options.isValid)
        assertEquals(
            """{"Multilingual":true,"NoVocals":true,"VietLyricsFallback":true,"language":"vi","lyrics":"Lyrics","lyrics_mode":"correct"}""",
            options.toRequestBody().toString())
        assertFalse(options.copy(lyrics = " ").isValid)
        assertFalse(options.copy(lyrics = "a".repeat(100_001)).isValid)
        assertFalse(options.copy(lyricsMode = "unsupported").isValid)
    }

    @Test fun noVocalsOnlyStatusesDoNotClaimLyricsWereTranscribedEvenWithLegacyFlags() {
        val options = SavedTranscriptionOptions(language = "vi", multilingual = true, noVocals = true,
            vietLyricsFallback = true, lyricsMode = "align", noVocalsOnly = true)
        mapOf(
            "sent" to "No-vocals request sent",
            "transcribed" to "No-vocals version generated",
            "failed" to "No-vocals generation failed",
            "interrupted" to "No-vocals generation interrupted",
            "processing" to "No-vocals status unknown"
        ).forEach { (status, label) ->
            val record = Transcription(status = status, lyricsIncluded = true, options = options)
            assertEquals(label, record.label)
            val details = record.tooltip { it ?: "Unknown" }
            assertTrue(details.contains("Generate NoVocals Only: On"))
            assertTrue(details.contains("Lyrics: Unchanged (not transcribed)"))
            assertFalse(details.contains("Add lyrics:"))
            assertFalse(details.contains("Lyrics mode:"))
            assertFalse(details.contains("Language:"))
            assertFalse(details.contains("Multilingual:"))
        }
        assertEquals("AI Transcribed",
            Transcription(status = "transcribed", options = options.copy(noVocalsOnly = false)).label)
    }
}
