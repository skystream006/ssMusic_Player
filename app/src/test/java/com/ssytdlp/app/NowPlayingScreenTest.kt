package com.ssytdlp.app

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Transcription
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class NowPlayingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val provider = object : Provider("PlayerTestKeyStore", 1.0, "Empty test session keystore") {}
    private lateinit var model: MusicViewModel

    @Before fun setup() {
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val application = MusicApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
        application.onCreate()
        compose.runOnUiThread {
            model = MusicViewModel(application)
            models.put("player", model)
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun emptyPageExplainsHowToStartPlayback() {
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState()) } }
        compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
        compose.onNodeWithText("Choose a song from Library to start playing.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close player").assertDoesNotExist()
    }

    @Test fun pageShowsPlayerLyricsAndQueueAndRestoresSelectedTab() {
        val track = Track(jobId = "preview", name = "song.mp3", title = "Blue hour", artist = "Northbound")
        val state = PlaybackState(track = track, queue = listOf(track), duration = 180_000)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MusicTheme { NowPlayingScreen(model, state) } }
        compose.onNodeWithText("Player").assertIsSelected()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player").assertDoesNotExist()
        compose.onNodeWithText("Lyrics").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithText("Queue").performClick().assertIsSelected()
        compose.onNodeWithText("Blue hour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove from queue").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Queue").assertIsSelected()
        compose.onNodeWithText("Blue hour").assertIsDisplayed()
    }

    @Test fun queueShowsSavedAndPendingTranscriptionStatus() {
        val track = Track(jobId = "source", name = "song.mp3")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            library.value = LibraryState(library = Library(jobs = listOf(Job(id = "source",
                transcriptions = mapOf(track.name to Transcription(status = "transcribed", lyricsIncluded = true))))))
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) } }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithText("Lyrics Included").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close transcription details").performClick()
        compose.runOnIdle {
            library.value = library.value.copy(pendingTranscriptions = mapOf(track.key to Transcription(status = "sent")))
        }
        compose.onNodeWithContentDescription("Transcription request sent", substring = true).assertIsDisplayed()
    }

    @Test fun queueShowsRefreshedStatusInsteadOfItsOriginalTrackSnapshot() {
        val track = Track(jobId = "source", name = "song.mp3", transcription = Transcription(status = "sent"))
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) } }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Transcription request sent", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.withTrackPage(TrackPage(files = listOf(
                track.copy(transcription = Transcription(status = "transcribed", lyricsIncluded = true)))))
        }
        compose.onNodeWithText("Lyrics Included").assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.withTrackPage(TrackPage(files = listOf(Track("other", "another.mp3"))))
                .withTranscriptions(Job(id = "source", transcriptions = mapOf(
                    track.name to Transcription(status = "failed"))), listOf(track))
        }
        compose.onNodeWithText("Transcription failed").assertIsDisplayed()
    }

    @Test fun lyricsTabTracksDisplayedSourceWithoutChangingSelection() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) } }
        compose.onNodeWithText("Lyrics").performClick().assertIsSelected()
        compose.onNodeWithText("Loading lyrics...").assertIsDisplayed()

        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Synchronized line")), uslt = "Plain lyrics")
        }
        compose.onNodeWithText("SYLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Synchronized line").assertIsDisplayed()
        compose.onNodeWithText("Plain lyrics").assertDoesNotExist()
        compose.onNodeWithText("USLT Lyrics").assertDoesNotExist()

        compose.runOnIdle { metadata.value = metadata.value!!.copy(sylt = emptyList()) }
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithText("SYLT Lyrics").assertDoesNotExist()
        compose.onNodeWithText("USLT Lyrics").performClick().assertIsSelected()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()

        compose.runOnIdle { metadata.value = SongMetadata(uslt = " ") }
        compose.onNodeWithText("Lyrics").assertIsSelected()
        compose.onNodeWithText("No lyrics available").assertIsDisplayed()
        compose.onNodeWithText("USLT Lyrics").assertDoesNotExist()
    }

    @Test fun reselectingLyricsTogglesSourcesAndResetsForANewTrack() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Synchronized line")), uslt = "Plain lyrics")
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MusicTheme { NowPlayingScreen(model, state.value) } }
        compose.onNodeWithText("SYLT Lyrics").performClick().assertIsSelected()
        compose.onNodeWithText("Synchronized line").assertIsDisplayed()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithText("Synchronized line").assertDoesNotExist()
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.onNodeWithText("SYLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Synchronized line").assertIsDisplayed()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithText("Player").performClick()
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(track = track.copy(name = "another.mp3")) }
        compose.onNodeWithText("SYLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Synchronized line").assertIsDisplayed()
        compose.runOnIdle { metadata.value = metadata.value!!.copy(uslt = " ") }
        compose.onNodeWithText("SYLT Lyrics").performClick().assertIsSelected()
        compose.onNodeWithText("Synchronized line").assertIsDisplayed()
    }

    @Test fun onlyPlayerTabConsumesHorizontalTrackSwipes() {
        val track = Track(jobId = "preview", name = "song.mp3", title = "Blue hour")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle { metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Synchronized line"))) }
        var unhandledSwipes = 0
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("page").playerTrackSwipes(true, true,
                    previous = { unhandledSwipes++ }, next = { unhandledSwipes++ })) {
                    NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track)))
                }
            }
        }
        fun swipeBothWaysAt(node: SemanticsNodeInteraction) {
            val y = node.fetchSemanticsNode().boundsInRoot.center.y
            compose.onNodeWithTag("page").performTouchInput {
                swipe(Offset(width * 0.9f, y), Offset(width * 0.1f, y))
                swipe(Offset(width * 0.1f, y), Offset(width * 0.9f, y))
            }
        }
        swipeBothWaysAt(compose.onNodeWithContentDescription("Album artwork unavailable"))
        compose.runOnIdle { assertEquals(0, unhandledSwipes) }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        swipeBothWaysAt(compose.onNodeWithText("Synchronized line"))
        compose.runOnIdle { assertEquals(2, unhandledSwipes) }
        compose.onNodeWithText("Queue").performClick()
        swipeBothWaysAt(compose.onNodeWithText("Blue hour"))
        compose.runOnIdle { assertEquals(4, unhandledSwipes) }
        compose.onNodeWithText("Player").performClick()
        swipeBothWaysAt(compose.onNodeWithContentDescription("Album artwork unavailable"))
        compose.runOnIdle { assertEquals(4, unhandledSwipes) }
    }

    @Test fun artworkFollowsTheDragAndSpringsBackAfterReleaseOrCancellation() {
        val track = Track(jobId = "preview", name = "song.mp3", title = "Blue hour")
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("page")) {
                    NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track)))
                }
            }
        }
        val artwork = compose.onNodeWithContentDescription("Album artwork unavailable")
        val original = artwork.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Blue hour").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            listOf(100f, -100f).forEach { distance ->
                compose.onNodeWithTag("page").performTouchInput {
                    down(original.center)
                    moveBy(Offset(distance * 0.2f, 0f), delayMillis = 16)
                    moveBy(Offset(distance * 0.8f, 0f), delayMillis = 16)
                }
                compose.runOnIdle { Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                val moved = artwork.fetchSemanticsNode().boundsInRoot.left - original.left
                assertTrue(moved * distance > 0f)
                assertTrue(kotlin.math.abs(moved) > 50f)
                assertEquals(title, compose.onNodeWithText("Blue hour").fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithTag("page").performTouchInput { if (distance > 0f) up() else cancel() }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                assertTrue(kotlin.math.abs(artwork.fetchSemanticsNode().boundsInRoot.left - original.left) > 1f)
                compose.mainClock.advanceTimeBy(2_000)
                compose.waitForIdle()
                assertEquals(original.left, artwork.fetchSemanticsNode().boundsInRoot.left, 1f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun changingTracksOrTabsDuringADragDoesNotLeaveArtworkDisplaced() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track))
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("page")) { NowPlayingScreen(model, state.value) }
            }
        }
        val artwork = compose.onNodeWithContentDescription("Album artwork unavailable")
        val original = artwork.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("page").performTouchInput {
                down(original.center)
                moveBy(Offset(100f, 0f), delayMillis = 300)
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                state.value = state.value.copy(track = track.copy(name = "another.mp3"))
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeBy(64)
            assertEquals(original.left, artwork.fetchSemanticsNode().boundsInRoot.left, 1f)
            compose.onNodeWithTag("page").performTouchInput { cancel() }
            compose.onNodeWithTag("page").performTouchInput {
                down(original.center)
                moveBy(Offset(-100f, 0f), delayMillis = 300)
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("page").performTouchInput { cancel() }
            compose.onNodeWithText("Queue").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText("Player").performClick()
            compose.mainClock.advanceTimeBy(64)
            assertEquals(original.left, artwork.fetchSemanticsNode().boundsInRoot.left, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
