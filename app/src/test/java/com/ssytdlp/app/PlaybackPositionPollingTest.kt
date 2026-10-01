package com.ssytdlp.app

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionPollingTest {
    @Test fun `position polling sleeps while paused and immediately catches up on resume`() = runBlocking {
        val resumed = MutableStateFlow(false)
        val updates = Channel<Long>(Channel.UNLIMITED)
        var position = 10L
        val polling = launch { pollPlaybackPosition(resumed, intervalMillis = 20) { updates.trySend(position) } }
        delay(60)
        assertTrue(updates.tryReceive().isFailure)
        resumed.value = true
        assertEquals(10L, withTimeout(2_000) { updates.receive() })
        resumed.value = false
        delay(60)
        while (updates.tryReceive().isSuccess) { /* Drain a possible transition-time update. */ }
        position = 500L
        delay(60)
        assertTrue(updates.tryReceive().isFailure)
        resumed.value = true
        assertEquals(500L, withTimeout(2_000) { updates.receive() })
        polling.cancelAndJoin()
        delay(60)
        assertTrue(updates.tryReceive().isFailure)
    }
}
