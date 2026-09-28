package com.ssytdlp.app

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TranscriptionOptionsTest {
    @Test fun defaultsMatchServerDialogAndOmitAutoDetect() {
        assertEquals(json("Multilingual" to false, "NoVocals" to false, "VietLyricsFallback" to false),
            TranscriptionOptions().toRequestBody())
    }

    @Test fun allLanguageChoicesUseSupportedCodes() {
        assertEquals(79, transcriptionLanguages.size)
        assertEquals(79, transcriptionLanguages.map { it.first }.toSet().size)
        transcriptionLanguages.drop(1).forEach { (code, _) ->
            assertEquals(JsonPrimitive(code), TranscriptionOptions(language = code).toRequestBody()["language"])
        }
        assertFalse(TranscriptionOptions(language = "auto").isValid)
        assertFalse(TranscriptionOptions(language = "invalid").isValid)
    }

    @Test fun optionsPreserveBooleanTypesAndForceVietnameseForFallback() {
        assertEquals(json("Multilingual" to true, "NoVocals" to true, "VietLyricsFallback" to true, "language" to "vi"),
            TranscriptionOptions(language = "en", multilingual = true, noVocals = true, vietLyricsFallback = true).toRequestBody())
    }

    @Test fun everyLyricsModeSendsTrimmedLyrics() {
        listOf("prompt", "align", "correct").forEach { mode ->
            assertEquals(json("Multilingual" to false, "NoVocals" to false, "VietLyricsFallback" to false,
                "language" to "en", "lyrics" to "First line\nSecond line", "lyrics_mode" to mode),
                TranscriptionOptions(language = "en", addLyrics = true, lyricsMode = mode,
                    lyrics = "  First line\nSecond line\n ").toRequestBody())
        }
    }

    @Test fun disabledLyricsAreOmittedEvenWhenPreviouslyEntered() {
        assertEquals(TranscriptionOptions().toRequestBody(),
            TranscriptionOptions(lyrics = "Previous lyrics", lyricsMode = "correct").toRequestBody())
    }

    @Test fun lyricsRequireNonblankTextWithinLimitAndAValidMode() {
        listOf("", " \n\t", "x".repeat(100_001)).forEach { lyrics ->
            val options = TranscriptionOptions(addLyrics = true, lyrics = lyrics)
            assertFalse(options.isValid)
            assertThrows(IllegalArgumentException::class.java) { options.toRequestBody() }
        }
        assertTrue(TranscriptionOptions(addLyrics = true, lyrics = "x".repeat(100_000)).isValid)
        assertFalse(TranscriptionOptions(addLyrics = true, lyrics = "Words", lyricsMode = "invalid").isValid)
    }
}
