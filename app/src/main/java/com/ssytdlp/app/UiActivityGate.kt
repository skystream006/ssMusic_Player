package com.ssytdlp.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI work is allowed only while at least one activity is resumed, not merely visible. */
class UiActivityGate {
    private val activities = mutableSetOf<Any>()
    private val mutableResumed = MutableStateFlow(false)
    val resumed = mutableResumed.asStateFlow()

    internal fun activityResumed(activity: Any) {
        activities.add(activity)
        mutableResumed.value = activities.isNotEmpty()
    }

    internal fun activityPaused(activity: Any) {
        activities.remove(activity)
        mutableResumed.value = activities.isNotEmpty()
    }

    suspend fun awaitResumed() {
        resumed.first { it }
    }

    /** Only restart operations whose results have no externally visible partial side effects. */
    suspend fun <T> readWhileResumed(block: suspend () -> T): T {
        while (true) {
            try {
                return onceWhileResumed(block)
            } catch (_: UiPaused) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    suspend fun <T> onceWhileResumed(block: suspend () -> T): T {
        awaitResumed()
        return coroutineScope {
            val read = async { block() }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                resumed.first { !it }
                read.cancel(UiPaused())
            }
            try { read.await() } finally { observer.cancel() }
        }
    }

    internal class UiPaused : CancellationException("UI paused")
}
