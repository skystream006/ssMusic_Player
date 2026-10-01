package com.ssytdlp.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class UiActivityGateTest {
    @Test fun `startup is paused and multiple resumed activities are tracked independently`() {
        val gate = UiActivityGate()
        val first = Any()
        val second = Any()
        assertFalse(gate.resumed.value)
        gate.activityResumed(first)
        gate.activityResumed(first)
        gate.activityResumed(second)
        gate.activityPaused(first)
        assertTrue(gate.resumed.value)
        gate.activityPaused(second)
        assertFalse(gate.resumed.value)
        gate.activityPaused(second)
        assertFalse(gate.resumed.value)
    }

    @Test fun `startup work waits for resume and completes only once`() = runBlocking {
        val gate = UiActivityGate()
        var calls = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            gate.readWhileResumed { ++calls }
        }
        yield()
        assertEquals(0, calls)
        gate.activityResumed(this)
        assertEquals(1, withTimeout(2_000) { result.await() })
        gate.activityPaused(this)
        gate.activityResumed(this)
        yield()
        assertEquals(1, calls)
    }

    @Test fun `safe reads cancel on pause and retry once on resume`() = runBlocking {
        val gate = UiActivityGate()
        gate.activityResumed(this)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val result = async {
            gate.readWhileResumed {
                if (++calls == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                "fresh"
            }
        }
        withTimeout(2_000) { started.await() }
        gate.activityPaused(this)
        withTimeout(2_000) { cancelled.await() }
        yield()
        assertFalse(result.isCompleted)
        assertEquals(1, calls)
        gate.activityResumed(this)
        assertEquals("fresh", withTimeout(2_000) { result.await() })
        assertEquals(2, calls)
    }

    @Test fun `caller cancellation removes deferred work and is never retried`() = runBlocking {
        val gate = UiActivityGate()
        var calls = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            gate.readWhileResumed { ++calls }
        }
        result.cancelAndJoin()
        gate.activityResumed(this)
        yield()
        assertEquals(0, calls)
    }

    @Test fun `nonrepeatable read is cancelled without replay on resume`() = runBlocking {
        val gate = UiActivityGate()
        gate.activityResumed(this)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val result = async {
            gate.onceWhileResumed {
                calls++
                started.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { started.await() }
        gate.activityPaused(this)
        withTimeout(2_000) { result.join() }
        gate.activityResumed(this)
        yield()
        assertTrue(result.isCancelled)
        assertEquals(1, calls)
    }
}
