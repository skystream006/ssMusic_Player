package com.ssytdlp.app

enum class EdgeLightingStyle(val label: String) {
    OSCILLATION("Oscillation"),
    VIBRATION("Vibration"),
    CIRCULATING("Circulating"),
    AUDIO_WAVEFORM("Audio waveform"),
    CIRCULATING_WAVEFORM("Circulating Waveform");

    internal val usesWaveform: Boolean
        get() = this == AUDIO_WAVEFORM || this == CIRCULATING_WAVEFORM

    internal val usesCirculatingGradient: Boolean
        get() = this == CIRCULATING || this == CIRCULATING_WAVEFORM

    companion object {
        fun fromPreference(value: String?): EdgeLightingStyle =
            entries.firstOrNull { it.name == value } ?: CIRCULATING_WAVEFORM
    }
}
