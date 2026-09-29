package com.ssytdlp.app

enum class EdgeLightingStyle(val label: String) {
    OSCILLATION("Oscillation"),
    VIBRATION("Vibration"),
    CIRCULATING("Circulating"),
    AUDIO_WAVEFORM("Audio waveform");

    companion object {
        fun fromPreference(value: String?): EdgeLightingStyle =
            entries.firstOrNull { it.name == value } ?: OSCILLATION
    }
}
