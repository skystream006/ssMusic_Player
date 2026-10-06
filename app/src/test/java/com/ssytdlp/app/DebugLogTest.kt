package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DebugLogTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var store: DebugLogStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_logging", Context.MODE_PRIVATE).edit().clear().commit()
        directory = File(context.noBackupFilesDir, "debug_logs")
        directory.deleteRecursively()
        store = DebugLogStore(context)
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h800dp")
    class DebugLogUiTest {
        @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
        private var previousHandler: Thread.UncaughtExceptionHandler? = null

        @Before fun setup() {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            DebugLog.initialize(ApplicationProvider.getApplicationContext())
            DebugLog.setEnabled(false)
            DebugLog.clear()
        }

        @After fun teardown() {
            DebugLog.setEnabled(false)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }

        @Test fun cancellingModeChoiceKeepsLoggingOff() {
            showSettings()
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithContentDescription("Debug logging").performClick()
            compose.onNodeWithText("Choose debug logging mode").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Debug logging").assertIsOff()
            assertFalse(DebugLog.enabled.value)
            assertEquals("", DebugLog.read())
        }

        @Test fun reactiveChoiceEnablesLoggingAndSavedLogsRemainViewableWhenDisabled() {
            showSettings()
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithContentDescription("Debug logging").performClick()
            compose.onNodeWithText("Reactive").performClick()
            compose.waitUntil { DebugLog.enabled.value }
            assertEquals(DebugLogMode.REACTIVE, DebugLog.mode.value)
            compose.onNodeWithContentDescription("Debug logging").performClick()
            compose.waitUntil { !DebugLog.enabled.value }
            compose.onNodeWithText("View logs").assertIsDisplayed().assertIsEnabled().performClick()
            waitForLogDialog()
            compose.onNodeWithText("Refresh").assertIsDisplayed()
            compose.onNodeWithText("Share snapshot").assertIsDisplayed()
            compose.onNodeWithText("LOGGING_ENABLED", substring = true).assertIsDisplayed()
        }

        @Test fun disabledLoggingStartsCollapsedAndSavedLogsCanBeOpenedManually() {
            DebugLog.setEnabled(true)
            DebugLog.setEnabled(false)
            showSettings()
            compose.onNodeWithText("View logs").assertDoesNotExist()
            compose.onNodeWithContentDescription("Debug logging").assertDoesNotExist()
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithContentDescription("Debug logging").assertIsOff()
            compose.onNodeWithText("View logs").assertIsDisplayed().assertIsEnabled().performClick()
            waitForLogDialog()
            compose.onNodeWithText("LOGGING_ENABLED", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithText("View logs").assertDoesNotExist()
            assertFalse(DebugLog.enabled.value)
        }

        @Test fun enabledLoggingStartsExpandedAndCanBeManuallyCollapsed() {
            DebugLog.setEnabled(true)
            showSettings()
            compose.onNodeWithContentDescription("Debug logging").assertIsOn()
            compose.onNodeWithText("View logs").assertIsDisplayed()
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithText("View logs").assertDoesNotExist()
            assertTrue(DebugLog.enabled.value)
            compose.onNodeWithText("Debug logging").performClick()
            compose.onNodeWithContentDescription("Debug logging").assertIsOn()
        }

        private fun showSettings() {
            compose.setContent { MaterialTheme { DebugLogSettings() } }
        }

        private fun waitForLogDialog() {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Refresh").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun loggingIsOffByDefaultAndCreatesNoFiles() {
        store.event(DebugEvent.APP_STARTED)
        store.event(DebugEvent.APP_CRASH, error = IOException("secret"))
        assertFalse(store.enabled)
        assertEquals("", store.read())
        assertFalse(directory.exists())
    }

    @Test fun artworkTimingsRecordOnlyNumericDurationsAndRespectLoggingState() {
        store.event(DebugEvent.ARTWORK_DECODED, durationMicros = 120)
        assertEquals("", store.read())
        store.setEnabled(true)
        store.event(DebugEvent.METADATA_DOWNLOAD, durationMicros = 120)
        store.event(DebugEvent.ARTWORK_DISPLAYED, durationMicros = -1)
        assertTrue(store.read().contains("METADATA_DOWNLOAD duration_us=120"))
        assertTrue(store.read().contains("ARTWORK_DISPLAYED duration_us=0"))
        val saved = store.read()
        store.setEnabled(false)
        store.event(DebugEvent.ARTWORK_DECODED, durationMicros = 120)
        assertEquals(saved, store.read())
    }

    @Test fun preferencePersistsAndDisablingStopsWritesWithoutRemovingSavedLogs() {
        assertTrue(store.setEnabled(true))
        assertTrue(DebugLogStore(context).enabled)
        store.event(DebugEvent.APP_STARTED)
        val saved = store.read()
        assertTrue(store.setEnabled(false))
        store.event(DebugEvent.API_STATUS, status = 200)
        assertEquals(saved, store.read())
        assertFalse(DebugLogStore(context).enabled)
    }

    @Test fun diagnosticOutputNeverIncludesMessagesCausesStacksOrPayloads() {
        store.setEnabled(true)
        val secret = "****** https://private.example?code=secret&codeVerifier=secret user@private.example song-title"
        val error = ApiException(401, secret).apply {
            initCause(IOException(secret))
            stackTrace = arrayOf(StackTraceElement(secret, secret, secret, 1))
            addSuppressed(IOException(secret))
        }
        store.event(DebugEvent.API_FAILURE, status = 401, error = error)
        val text = store.read()
        assertTrue(text.contains("API_FAILURE status=401 exception=com.ssytdlp.app.ApiException"))
        listOf("Bearer", "private-token", "https://", "code=", "codeVerifier", "user@", "song-title")
            .forEach { assertFalse(it, text.contains(it)) }
        assertFalse(text.contains("IOException"))
    }

    @Test fun filesAndExportAreBoundedAndNewestEventsSurviveRotation() {
        store.setEnabled(true)
        repeat(4000) { store.event(DebugEvent.API_STATUS, status = it) }
        store.event(DebugEvent.PLAYBACK_FAILURE, status = 99999)
        val files = directory.listFiles()!!
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= DebugLogStore.MAX_FILE_BYTES })
        assertTrue(files.sumOf { it.length() } <= DebugLogStore.MAX_EXPORT_BYTES)
        val text = DebugLogStore(context).read()
        assertTrue(text.toByteArray().size <= DebugLogStore.MAX_EXPORT_BYTES)
        assertTrue(text.endsWith("PLAYBACK_FAILURE status=99999\n"))
        assertFalse(text.contains("LOGGING_ENABLED"))
        assertTrue(text.lineSequence().filter { it.isNotEmpty() }
            .all { it.matches(Regex("\\d+ (API_STATUS|PLAYBACK_FAILURE) status=\\d+")) })
    }

    @Test fun concurrentWritesRemainCompleteAndBounded() {
        store.setEnabled(true)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..4).map { status ->
                workers.submit { repeat(200) { store.event(DebugEvent.API_STATUS, status = status) } }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        val lines = store.read().lineSequence().filter { it.contains("API_STATUS") }.toList()
        assertEquals(800, lines.size)
        assertTrue(lines.all { it.matches(Regex("\\d+ API_STATUS status=[1-4]")) })
    }

    @Test fun clearingRemovesAllLogFilesButKeepsOptIn() {
        store.setEnabled(true)
        repeat(1500) { store.event(DebugEvent.API_STATUS, status = 200) }
        assertTrue(store.clear())
        assertEquals("", store.read())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(DebugLogStore(context).enabled)
        store.event(DebugEvent.APP_STARTED)
        assertTrue(store.read().contains("APP_STARTED"))
    }

    @Test fun reactiveModePersistsAndKeepsTheLatest100CompleteEntries() {
        assertTrue(store.setEnabled(true, DebugLogMode.REACTIVE))
        repeat(250) { store.event(DebugEvent.API_STATUS, status = it) }
        val restored = DebugLogStore(context)
        assertTrue(restored.enabled)
        assertEquals(DebugLogMode.REACTIVE, restored.mode)
        val lines = restored.read().lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(100, lines.size)
        assertTrue(lines.first().endsWith("API_STATUS status=150"))
        assertTrue(lines.last().endsWith("API_STATUS status=249"))
        restored.setEnabled(false)
        assertEquals(DebugLogMode.REACTIVE, DebugLogStore(context).mode)
        val snapshot = restored.read()
        restored.event(DebugEvent.APP_STARTED)
        assertEquals(snapshot, restored.read())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun switchingFromFullToReactiveTrimsBothRotatedFiles() {
        store.setEnabled(true, DebugLogMode.FULL)
        repeat(2500) { store.event(DebugEvent.API_STATUS, status = it) }
        assertEquals(2, directory.listFiles()!!.size)
        store.setEnabled(false)
        store.setEnabled(true, DebugLogMode.REACTIVE)
        val lines = store.read().lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(100, lines.size)
        assertTrue(lines.first().endsWith("API_STATUS status=2401"))
        assertTrue(lines.last().endsWith("LOGGING_ENABLED"))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun incompleteTrailingEventIsNotReadOrRetainedByReactiveMode() {
        store.setEnabled(true, DebugLogMode.REACTIVE)
        store.event(DebugEvent.API_STATUS, status = 200)
        File(directory, "events.log").appendText("123 API_FAILURE")
        assertFalse(store.read().contains("API_FAILURE"))
        store.event(DebugEvent.APP_STARTED)
        assertFalse(store.read().contains("API_FAILURE"))
        assertTrue(store.read().endsWith("APP_STARTED\n"))
    }

    @Test fun clearingInvalidatesPreviouslyQueuedEvents() {
        store.setEnabled(true)
        val queuedGeneration = store.generation
        assertTrue(store.clear())
        store.event(DebugEvent.API_STATUS, status = 200, expectedGeneration = queuedGeneration)
        assertEquals("", store.read())
        store.event(DebugEvent.APP_STARTED, expectedGeneration = store.generation)
        assertTrue(store.read().contains("APP_STARTED"))
    }

    @Test fun disablingAndReenablingCannotWritePreviouslyQueuedEvents() {
        store.setEnabled(true)
        val queuedGeneration = store.generation
        store.setEnabled(false)
        store.setEnabled(true)
        store.event(DebugEvent.API_STATUS, status = 200, expectedGeneration = queuedGeneration)
        assertFalse(store.read().contains("API_STATUS"))
    }

    @Test fun crashIsRecordedBeforeOriginalHandlerReceivesSameThreadAndThrowable() {
        store.setEnabled(true)
        val error = IllegalStateException("private server response")
        val thread = Thread.currentThread()
        var delegated = 0
        val handler = store.crashHandler { actualThread, actualError ->
            delegated++
            assertSame(thread, actualThread)
            assertSame(error, actualError)
            val text = store.read()
            assertTrue(text.contains("APP_CRASH exception=java.lang.IllegalStateException"))
            assertFalse(text.contains("private server response"))
        }
        handler.uncaughtException(thread, error)
        assertEquals(1, delegated)
    }

    @Test fun disabledCrashStillDelegatesWithoutWriting() {
        var delegated = false
        store.crashHandler { _, _ -> delegated = true }
            .uncaughtException(Thread.currentThread(), IllegalStateException("secret"))
        assertTrue(delegated)
        assertEquals("", store.read())
        assertFalse(directory.exists())
    }

    @Test fun crashFreezesQueuedAndNewEventsWithoutChangingPersistedOptIn() {
        store.setEnabled(true, DebugLogMode.REACTIVE)
        val queuedGeneration = store.generation
        var delegated = false
        store.crashHandler { _, _ ->
            delegated = true
            repeat(150) {
                store.event(DebugEvent.API_STATUS, status = it, expectedGeneration = queuedGeneration)
                store.event(DebugEvent.API_STATUS, status = it)
            }
            assertTrue(store.read().endsWith("APP_CRASH exception=java.lang.IllegalStateException\n"))
        }.uncaughtException(Thread.currentThread(), IllegalStateException("secret"))
        assertTrue(delegated)
        assertTrue(DebugLogStore(context).enabled)
        assertEquals(DebugLogMode.REACTIVE, DebugLogStore(context).mode)
    }

    @Test fun interruptedReactiveRewritePreservesCommittedSnapshotAndClearRemovesSidecars() {
        store.setEnabled(true, DebugLogMode.REACTIVE)
        val snapshot = store.read()
        val file = android.util.AtomicFile(File(directory, "events.log"))
        file.startWrite().use { it.write("interrupted".toByteArray()) }
        assertEquals(snapshot, DebugLogStore(context).read())
        file.startWrite().use { it.write("another interruption".toByteArray()) }
        assertTrue(store.clear())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun storageFailureCannotPreventOriginalCrashHandler() {
        directory.writeText("not a directory")
        store.setEnabled(true)
        var delegated = false
        store.crashHandler { _, _ -> delegated = true }
            .uncaughtException(Thread.currentThread(), IOException("secret"))
        assertTrue(delegated)
        assertEquals("", store.read())
    }

    @Test fun sharingUsesBoundedPlainTextWithoutFileUrisOrGrants() {
        val intent = DebugLog.shareIntent("x".repeat(DebugLogStore.MAX_EXPORT_BYTES + 100))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(DebugLogStore.MAX_EXPORT_BYTES, intent.getStringExtra(Intent.EXTRA_TEXT)!!.length)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNull(intent.data)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
