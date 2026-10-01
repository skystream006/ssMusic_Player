package com.ssytdlp.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

internal suspend fun pollPlaybackPosition(
    uiResumed: StateFlow<Boolean>,
    intervalMillis: Long = 300,
    update: () -> Unit
) {
    uiResumed.collectLatest { resumed ->
        if (!resumed) return@collectLatest
        while (true) {
            update()
            delay(intervalMillis)
        }
    }
}
