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
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Transcription
import java.security.Provider
import java.security.Security
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun lyricsViewOffersEditingForPermittedMp3EvenWhenTranscriptionIsLocked() {
        val track = Track(jobId = "preview", name = "song.mp3", transcriptionLocked = true)
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            metadata.value = SongMetadata(sylt = listOf(LyricLine(1.234, "Timed line")), uslt = "Plain line",
                transcriptionLocked = true, canEdit = true)
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) } }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("Edit lyrics").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("Plain line").assertIsDisplayed()
        compose.onNodeWithText("Edit lyrics").performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { metadata.value = metadata.value!!.copy(canEdit = false) }
        compose.onNodeWithText("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle {
            metadata.value = metadata.value!!.copy(canEdit = true)
            account.value = account.value!!.copy(user = User(role = "Shared"))
        }
        compose.onNodeWithText("Edit lyrics").assertDoesNotExist()
    }

    @Test fun advancingPlaybackKeepsOpenLyricsDraftBoundToOriginalSong() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            metadata.value = SongMetadata(uslt = "Original lyrics", canEdit = true)
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) } }
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.onNodeWithText("Edit lyrics").performClick()
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Unsaved draft")
        compose.runOnIdle {
            state.value = state.value.copy(track = Track(jobId = "preview", name = "next.mp3"))
            metadata.value = SongMetadata(uslt = "Next song lyrics", canEdit = false)
        }
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithTag("uslt-editor").assertTextContains("Unsaved draft")
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Next song lyrics").assertIsDisplayed()
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

    @Test fun libraryUnratedSongOpensRatingDialogEvenWhenPlaybackIsDisconnected() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle { library.value = LibraryState(tracks = TrackPage(files = listOf(track))) }
        compose.setContent { MusicTheme { LibraryScreen(model, PlaybackState(), {}, { _, _ -> }) } }
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Rate song").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed()
    }

    @Test fun queueRatingOpensOnlyOneDialogAndCanBeDismissed() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val next = track.copy(name = "next.mp3", rating = 4)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track, next))) }
        }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Rating: 4 out of 5").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Rate song").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Remove from queue").assertCountEquals(2)
    }

    @Test fun queueRatingReflectsSavedAndClearedValuesOutsideTheCurrentLibraryPage() {
        val track = Track(jobId = "preview", name = "song.mp3", rating = 4)
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) } }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Rating: 4 out of 5").assertIsDisplayed()
        compose.runOnIdle { library.value = library.value.copy(ratings = mapOf(track.key to 2)) }
        compose.onNodeWithContentDescription("Rating: 2 out of 5").assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.copy(ratings = mapOf(track.key to 0))
                .withTrackPage(TrackPage(files = listOf(Track("other", "other.mp3"))))
        }
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun lyricsPinchPersistsAcrossTabsTracksAndViewModelRecreation() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track)))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Zoomable lyrics")), uslt = "Plain lyrics")
            assertEquals(1f, model.lyricsTextScale, 0f)
        }
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("page")) { NowPlayingScreen(model, state.value) }
            }
        }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        val center = compose.onNodeWithText("Zoomable lyrics").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("page").performTouchInput {
            pinch(start0 = center - Offset(20f, 0f), end0 = center - Offset(60f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(60f, 0f), durationMillis = 600)
        }
        val savedScale = compose.runOnIdle { model.lyricsTextScale }
        assertTrue(savedScale > 1f)
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.runOnIdle { state.value = state.value.copy(track = track.copy(name = "next.mp3")) }
        compose.onNodeWithText("Zoomable lyrics").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(savedScale, model.lyricsTextScale, 0f)
            val application = model.getApplication<MusicApplication>()
            val preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
            assertEquals(savedScale, preferences.getFloat("lyrics_text_scale", 1f), 0f)
            val restored = MusicViewModel(application)
            models.put("restored", restored)
            assertEquals(savedScale, restored.lyricsTextScale, 0f)
        }
    }

    @Test fun lyricsZoomAccumulatesChangesAndClampsToReadableBounds() {
        compose.runOnIdle {
            model.zoomLyrics(1.2f)
            model.zoomLyrics(1.25f)
            assertEquals(1.5f, model.lyricsTextScale, 0.0001f)
            model.zoomLyrics(100f)
            assertEquals(MAX_LYRICS_TEXT_SCALE, model.lyricsTextScale, 0f)
            model.zoomLyrics(0.001f)
            assertEquals(MIN_LYRICS_TEXT_SCALE, model.lyricsTextScale, 0f)
            listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
                model.zoomLyrics(invalid)
                assertEquals(MIN_LYRICS_TEXT_SCALE, model.lyricsTextScale, 0f)
            }
            val preferences = model.getApplication<MusicApplication>().getSharedPreferences("settings", Context.MODE_PRIVATE)
            assertEquals(MIN_LYRICS_TEXT_SCALE, preferences.getFloat("lyrics_text_scale", 1f), 0f)
        }
    }

    @Test fun savedLyricsZoomIsValidatedWhenLoading() {
        compose.runOnIdle {
            val application = model.getApplication<MusicApplication>()
            val preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
            listOf(Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, Float.NEGATIVE_INFINITY to 1f,
                0f to MIN_LYRICS_TEXT_SCALE, 20f to MAX_LYRICS_TEXT_SCALE, 1.4f to 1.4f).forEach { (saved, expected) ->
                preferences.edit().putFloat("lyrics_text_scale", saved).commit()
                val restored = MusicViewModel(application)
                models.put("restored", restored)
                assertEquals(expected, restored.lyricsTextScale, 0f)
            }
        }
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

    @Test fun playerTabShowsTranscriptionStatusButHidesNotTranscribed() {
        val track = Track(jobId = "source", name = "song.mp3", artist = "Northbound")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) } }
        compose.onNodeWithText("Northbound").assertIsDisplayed()
        compose.onNodeWithText("Not transcribed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transcription status unknown", substring = true).assertDoesNotExist()

        compose.runOnIdle {
            library.value = LibraryState(library = Library(jobs = listOf(Job(id = "source",
                transcriptions = mapOf(track.name to Transcription(status = "transcribed", lyricsIncluded = true))))))
        }
        compose.onNodeWithText("Lyrics Included").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lyrics Included", substring = true).assertIsDisplayed()
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
