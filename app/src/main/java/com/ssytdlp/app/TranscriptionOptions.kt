package com.ssytdlp.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val transcriptionLanguages = listOf(
    "" to "Auto-detect",
    "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic",
    "hy" to "Armenian", "az" to "Azerbaijani", "eu" to "Basque", "be" to "Belarusian",
    "bn" to "Bengali", "bs" to "Bosnian", "bg" to "Bulgarian", "my" to "Burmese",
    "ca" to "Catalan", "zh" to "Chinese", "hr" to "Croatian", "cs" to "Czech",
    "da" to "Danish", "nl" to "Dutch", "en" to "English", "et" to "Estonian",
    "fi" to "Finnish", "fr" to "French", "gl" to "Galician", "ka" to "Georgian",
    "de" to "German", "el" to "Greek", "gu" to "Gujarati", "ht" to "Haitian Creole",
    "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian", "is" to "Icelandic",
    "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "kn" to "Kannada",
    "kk" to "Kazakh", "km" to "Khmer", "ko" to "Korean", "lo" to "Lao",
    "la" to "Latin", "lv" to "Latvian", "lt" to "Lithuanian", "mk" to "Macedonian",
    "ms" to "Malay", "ml" to "Malayalam", "mt" to "Maltese", "mr" to "Marathi",
    "mn" to "Mongolian", "ne" to "Nepali", "no" to "Norwegian", "ps" to "Pashto",
    "fa" to "Persian", "pl" to "Polish", "pt" to "Portuguese", "pa" to "Punjabi",
    "ro" to "Romanian", "ru" to "Russian", "sr" to "Serbian", "si" to "Sinhala",
    "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali", "es" to "Spanish",
    "sw" to "Swahili", "sv" to "Swedish", "tl" to "Tagalog", "ta" to "Tamil",
    "te" to "Telugu", "th" to "Thai", "bo" to "Tibetan", "tr" to "Turkish",
    "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek", "vi" to "Vietnamese",
    "cy" to "Welsh", "yi" to "Yiddish"
)

internal val transcriptionLyricsModes = listOf(
    "prompt" to "Prompt", "align" to "Align", "correct" to "Correct"
)

data class TranscriptionOptions(
    val language: String = "",
    val multilingual: Boolean = false,
    val noVocals: Boolean = false,
    val vietLyricsFallback: Boolean = false,
    val addLyrics: Boolean = false,
    val lyricsMode: String = "prompt",
    val lyrics: String = ""
) {
    val isValid: Boolean
        get() = transcriptionLanguages.any { it.first == language } &&
            (!addLyrics || (lyrics.isNotBlank() && lyrics.length <= 100_000 &&
                transcriptionLyricsModes.any { it.first == lyricsMode }))

    fun toRequestBody() = buildJsonObject {
        require(isValid) { "Select valid transcription options and nonblank lyrics of at most 100,000 characters." }
        put("Multilingual", multilingual)
        put("NoVocals", noVocals)
        put("VietLyricsFallback", vietLyricsFallback)
        if (vietLyricsFallback) put("language", "vi")
        else if (language.isNotEmpty()) put("language", language)
        if (addLyrics) {
            put("lyrics", lyrics.trim())
            put("lyrics_mode", lyricsMode)
        }
    }
}
