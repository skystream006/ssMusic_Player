package com.ssytdlp.app

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DebugEvent {
    LOGGING_ENABLED, APP_STARTED, API_STATUS, API_FAILURE, API_INVALID_RESPONSE,
    PLAYBACK_CONNECTING, PLAYBACK_CONNECTED, PLAYBACK_DISCONNECTED, PLAYBACK_FAILURE, APP_CRASH,
    UPDATE_CHECK_STARTED, UPDATE_CHECK_COMPLETED, UPDATE_DOWNLOAD_STARTED, UPDATE_DOWNLOAD_COMPLETED,
    UPDATE_PERMISSION_REQUIRED, UPDATE_INSTALL_REQUESTED, UPDATE_FAILURE
}

enum class DebugLogMode { FULL, REACTIVE }

object DebugLog {
    @Volatile private var store: DebugLogStore? = null
    // Bound both queued work and disk usage; diagnostics must never back-pressure playback.
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "debug-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private val mutableMode = MutableStateFlow(DebugLogMode.FULL)
    val mode = mutableMode.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        val logger = DebugLogStore(context.applicationContext)
        store = logger
        mutableEnabled.value = logger.enabled
        mutableMode.value = logger.mode
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(logger.crashHandler(previous))
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean, mode: DebugLogMode = mutableMode.value): Boolean {
        val logger = store ?: return false
        val saved = logger.setEnabled(enabled, mode)
        mutableEnabled.value = logger.enabled
        mutableMode.value = logger.mode
        return saved
    }

    fun event(event: DebugEvent, status: Int? = null, error: Throwable? = null) {
        val logger = store ?: return
        if (!logger.enabled) return
        val generation = logger.generation
        runCatching { writer.execute { logger.event(event, status, error, generation) } }
    }

    @Synchronized
    fun read(): String = store?.read().orEmpty()

    @Synchronized
    fun clear(): Boolean = store?.clear() ?: true

    fun shareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "ssMusic Player debug log")
        putExtra(Intent.EXTRA_TEXT, text.take(DebugLogStore.MAX_EXPORT_BYTES))
    }
}

internal class DebugLogStore(context: Context) {
    private val preferences = context.getSharedPreferences("debug_logging", Context.MODE_PRIVATE)
    private val directory = File(context.noBackupFilesDir, "debug_logs")
    private val current = File(directory, "events.log")
    private val previous = File(directory, "previous.log")
    @Volatile var enabled: Boolean = preferences.getBoolean("enabled", false)
        private set
    @Volatile var generation: Long = 0
        private set
    private var frozen = false
    var mode: DebugLogMode = DebugLogMode.entries.firstOrNull {
        it.name == preferences.getString("mode", null)
    } ?: DebugLogMode.FULL
        private set

    @Synchronized
    fun setEnabled(value: Boolean, mode: DebugLogMode = this.mode): Boolean {
        if (!preferences.edit().putBoolean("enabled", value).putString("mode", mode.name).commit()) return false
        generation++
        enabled = value
        this.mode = mode
        if (value) event(DebugEvent.LOGGING_ENABLED)
        return true
    }

    @Synchronized
    fun event(event: DebugEvent, status: Int? = null, error: Throwable? = null, expectedGeneration: Long? = null) {
        if (frozen || !enabled || expectedGeneration != null && expectedGeneration != generation) return
        // Only enum labels, numbers and class names can enter the file. Never stringify a throwable.
        runCatching {
            val line = buildString {
                append(System.currentTimeMillis()).append(' ').append(event.name)
                status?.let { append(" status=").append(it) }
                error?.let {
                    append(" exception=").append(it.javaClass.name
                        .filter { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "._$" }
                        .take(160))
                }
                append('\n')
            }.toByteArray(Charsets.UTF_8)
            check(directory.isDirectory || directory.mkdirs())
            if (mode == DebugLogMode.REACTIVE) {
                val retained = read().lineSequence().filter { it.isNotEmpty() }.toList().takeLast(MAX_REACTIVE_ENTRIES - 1)
                check(!previous.exists() || previous.delete())
                val file = AtomicFile(current)
                val output = file.startWrite()
                try {
                    output.write((retained.joinToString("", transform = { "$it\n" }) + line.toString(Charsets.UTF_8)).toByteArray())
                    file.finishWrite(output)
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
                return@runCatching
            }
            if (current.length() + line.size > MAX_FILE_BYTES) {
                check(!previous.exists() || previous.delete())
                check(!current.exists() || current.renameTo(previous))
            }
            current.appendBytes(line)
        }
    }

    @Synchronized
    fun read(): String = listOf(previous, current).joinToString("") { file ->
        runCatching {
            AtomicFile(file).openRead().use { input ->
                val bytes = ByteArray(MAX_FILE_BYTES)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                val text = String(bytes, 0, size, Charsets.UTF_8)
                text.substring(0, text.lastIndexOf('\n') + 1)
            }
        }.getOrDefault("")
    }

    @Synchronized
    fun clear(): Boolean = runCatching {
        generation++
        listOf(previous, current).forEach { AtomicFile(it).delete() }
        directory.listFiles()?.isEmpty() ?: !directory.exists()
    }.getOrDefault(false)

    fun crashHandler(previousHandler: Thread.UncaughtExceptionHandler) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                synchronized(this) {
                    generation++
                    try {
                        event(DebugEvent.APP_CRASH, error = error)
                    } finally {
                        frozen = true
                    }
                }
            } finally {
                previousHandler.uncaughtException(thread, error)
            }
        }

    companion object {
        const val MAX_FILE_BYTES = 32 * 1024
        const val MAX_EXPORT_BYTES = 2 * MAX_FILE_BYTES
        const val MAX_REACTIVE_ENTRIES = 100
    }
}
