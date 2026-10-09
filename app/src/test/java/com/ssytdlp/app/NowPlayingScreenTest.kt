package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.Preferences
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Transcription
import java.security.Provider
import java.security.Security
import java.util.Collections
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class NowPlayingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val provider = object : Provider("PlayerTestKeyStore", 1.0, "Empty test session keystore") {}
    private lateinit var model: MusicViewModel
    private val isSongHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val isSongActionsPopup = isPopup() and hasAnyDescendant(hasText("Save file"))

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
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState()) { _, _ -> } } }
        compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
        compose.onNodeWithText("Choose a song from Library to start playing.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close player").assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
        compose.onNode(isPopup()).assertDoesNotExist()
        listOf("Edit metadata", "Edit lyrics", "View song metadata", "Transcribe lyrics",
            "Replace File", "Share Media", "Save file").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Audio visualizer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Visualizer style").assertDoesNotExist()
        compose.onAllNodes(isSongHeading).assertCountEquals(0)
    }

    @Test fun headerShowsCurrentSongAndFilenameFallbackAcrossTabs() {
        val track = Track("preview", "song.mp3", title = "Blue hour", playlistTitle = "Night Sessions")
        val fallback = Track("preview", "folder/Quiet signal.mp3", title = " ")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track, fallback)))
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        listOf(track to "Blue hour", track.copy(title = "Blue hour (live)") to "Blue hour (live)",
            fallback to "Quiet signal").forEach { (current, title) ->
            compose.runOnIdle {
                state.value = state.value.copy(track = current)
                Snapshot.sendApplyNotifications()
            }
            listOf("Player", "Lyrics", "Queue").forEach { tab ->
                compose.onNodeWithText(tab).performClick().assertIsSelected()
                compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
                    .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
                compose.onAllNodes(isSongHeading).assertCountEquals(1)
                songHeading(title).assertIsDisplayed().assertTextEquals(title)
                    .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
                compose.onNodeWithText(if (current == fallback) "Your queue" else "Night Sessions").assertIsDisplayed()
                compose.onNodeWithContentDescription("More song actions").assertIsDisplayed().assertHasClickAction()
                    .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
                compose.onNodeWithText("ssMusic").assertDoesNotExist()
                assertEquals(compose.onNodeWithTag("now-playing-top-bar").getUnclippedBoundsInRoot().bottom,
                    compose.onNodeWithTag("now-playing-pane").getUnclippedBoundsInRoot().top)
            }
        }
        compose.runOnIdle {
            state.value = PlaybackState()
            Snapshot.sendApplyNotifications()
        }
        compose.onAllNodes(isSongHeading).assertCountEquals(0)
        compose.onNodeWithText("Your queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun playerRatingKeepsShortLandscapeDetailsScrollable() {
        val track = Track("preview", "song.mp3", rating = 3)
        compose.setContent {
            MusicTheme {
                Box(Modifier.height(200.dp)) {
                    NowPlayingScreen(model, PlaybackState(track = track, duration = 60_000)) { _, _ -> }
                }
            }
        }
        compose.onNodeWithTag("landscape-player-artwork").assertIsDisplayed().assertHeightIsAtLeast(1.dp)
        compose.onNodeWithContentDescription("Audio visualizer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Rating: 3 out of 5").performScrollTo().assertIsDisplayed()
            .assertHasNoClickAction()
        compose.onNodeWithContentDescription("Audio visualizer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun longSongHeaderIsSingleLineAndKeepsOverflowAccessibleWithLargeText() {
        val track = Track("preview", "song.mp3", title = "A long song title that needs to scroll ".repeat(4),
            playlistTitle = "A long playlist name ".repeat(4))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val heading = songHeading(track.displayTitle).assertIsDisplayed()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        val titleLayout = titleLayouts.single()
        assertEquals(1, titleLayout.lineCount)
        assertEquals(false, titleLayout.isLineEllipsized(0))
        val context = compose.onNodeWithText(track.playlistTitle).assertIsDisplayed()
        val contextLayouts = mutableListOf<TextLayoutResult>()
        context.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(contextLayouts) }
        assertTrue(titleLayout.layoutInput.style.fontSize.value > contextLayouts.single().layoutInput.style.fontSize.value)
        val titleBounds = heading.getUnclippedBoundsInRoot()
        val contextBounds = context.getUnclippedBoundsInRoot()
        val labelBounds = compose.onNodeWithText("NOW PLAYING").getUnclippedBoundsInRoot()
        val overflow = compose.onNodeWithContentDescription("More song actions")
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction().getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed().getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription("Settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(titleBounds.left >= back.right && titleBounds.right <= refresh.left)
        assertTrue(titleBounds.right - titleBounds.left >= 48.dp)
        assertTrue(labelBounds.bottom <= titleBounds.top && titleBounds.bottom <= contextBounds.top)
        assertTrue(contextBounds.right <= refresh.left)
        assertTrue(refresh.right <= settings.left && settings.right <= overflow.left)
        assertTrue(overflow.right <= 320.dp && overflow.right - overflow.left >= 48.dp)
        assertTrue(contextBounds.bottom <= compose.onNodeWithText("Player").getUnclippedBoundsInRoot().top)
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(overflow, compose.onNodeWithContentDescription("More song actions").getUnclippedBoundsInRoot())
        heading.assertTextEquals(track.displayTitle)
        openSongActions()
        songAction("Save file").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun topBarNavigationRemainsAvailableWhileBusy() {
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        var wentBack = false
        var openedSettings = false
        compose.runOnIdle { busy.value = true }
        compose.setContent {
            MusicTheme {
                NowPlayingScreen(model, PlaybackState(), onBack = { wentBack = true },
                    onSettings = { openedSettings = true }) { _, _ -> }
            }
        }
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Settings").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(wentBack)
            assertTrue(openedSettings)
            busy.value = false
        }
        compose.onNodeWithContentDescription("Refresh").assertIsEnabled()
    }

    @Test fun visualizerSwitchAndDropdownRetainSelectionWithoutChangingPlayback() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val playback = PlaybackState(track = track, queue = listOf(track), position = 42_000)
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MusicTheme { if (visible.value) NowPlayingScreen(model, playback) { _, _ -> } } }
        compose.onNodeWithContentDescription("Audio visualizer").assertIsOff()
        compose.onNodeWithContentDescription("Album artwork unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Visualizer style").assertDoesNotExist()
        compose.onNodeWithContentDescription("Audio visualizer").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Album artwork unavailable").assertDoesNotExist()
        AudioVisualizerStyle.entries.forEach { style ->
            compose.onNodeWithContentDescription("Visualizer style").performClick()
            compose.onAllNodesWithText(style.label).onLast().performClick()
            compose.onNodeWithContentDescription("${style.label} audio visualizer").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Audio visualizer").assertIsOn()
        compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertIsDisplayed()
        listOf("Lyrics", "Queue").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            compose.onNodeWithContentDescription("Visualizer style").assertDoesNotExist()
            compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertDoesNotExist()
            compose.onNodeWithText("Player").performClick()
            compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Audio visualizer").performClick().assertIsOff()
        compose.onNodeWithContentDescription("Visualizer style").assertDoesNotExist()
        compose.onNodeWithContentDescription("Album artwork unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Audio visualizer").performClick()
        compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithContentDescription("Audio visualizer").assertIsOn()
        compose.onNodeWithContentDescription("Radial pulse audio visualizer").assertIsDisplayed()
        compose.onNodeWithText("0:42").assertIsDisplayed()
    }

    @Test fun visualizerPreferencesSurviveModelRecreationAndUnknownStylesFallBack() {
        val application = model.getApplication<MusicApplication>()
        val preferences = application.getSharedPreferences("settings", 0)
        compose.runOnIdle {
            assertEquals(false, model.audioVisualizerEnabled)
            assertEquals(AudioVisualizerStyle.WAVEFORM, model.audioVisualizerStyle)
            model.chooseAudioVisualizer(true)
            model.chooseAudioVisualizerStyle(AudioVisualizerStyle.RADIAL)
            val restored = MusicViewModel(application, connectPlayback = false)
            models.put("restored-visualizer", restored)
            assertTrue(restored.audioVisualizerEnabled)
            assertEquals(AudioVisualizerStyle.RADIAL, restored.audioVisualizerStyle)
            restored.chooseAudioVisualizer(false)
            val artwork = MusicViewModel(application, connectPlayback = false)
            models.put("restored-artwork", artwork)
            assertEquals(false, artwork.audioVisualizerEnabled)
            assertEquals(AudioVisualizerStyle.RADIAL, artwork.audioVisualizerStyle)
            preferences.edit().putString("audio_visualizer_style", "unknown").commit()
            val fallback = MusicViewModel(application, connectPlayback = false)
            models.put("fallback-visualizer", fallback)
            assertEquals(AudioVisualizerStyle.WAVEFORM, fallback.audioVisualizerStyle)
        }
    }

    @Test fun visualizerFollowsPlaybackAndLeavesVideoUnchanged() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val playback = mutableStateOf(PlaybackState(track = track, queue = listOf(track)))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicTheme { NowPlayingScreen(model, playback.value) { _, _ -> } } }
        compose.onNodeWithContentDescription("Audio visualizer").performClick()
        fun assertVisualizerState(description: String) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithContentDescription("Waveform audio visualizer")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
        }
        fun update(value: PlaybackState) = compose.runOnIdle {
            playback.value = value
            Snapshot.sendApplyNotifications()
        }
        assertVisualizerState("Idle")
        update(playback.value.copy(playing = true))
        assertVisualizerState("Playing")
        update(playback.value.copy(buffering = true))
        assertVisualizerState("Idle")
        update(playback.value.copy(buffering = false, error = "Playback failed"))
        assertVisualizerState("Idle")
        update(playback.value.copy(error = null, track = track.copy(name = "next.mp3")))
        assertVisualizerState("Playing")
        update(playback.value.copy(track = track.copy(name = "video.mp4", mediaType = "video")))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Audio visualizer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Visualizer style").assertDoesNotExist()
        compose.onNodeWithContentDescription("Waveform audio visualizer").assertDoesNotExist()
        update(playback.value.copy(track = track, playing = false))
        assertVisualizerState("Idle")
        compose.onNodeWithContentDescription("Audio visualizer").assertIsOn()
    }

    @Test fun nowPlayingActionsRespectEditingPermissionsAndBusyStateAcrossTabs() {
        val track = Track(jobId = "preview", name = "song.MP3", playlistTitle = "A long playlist title ".repeat(5))
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track)))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(
                Job("preview", initiatedBy = User(id = "owner"), contributors = listOf(User(id = "contributor"))))))
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        openSongActions()
        songAction("Transcribe lyrics").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        val editableActions = listOf("Edit metadata", "Transcribe lyrics", "Replace File", "Share Media", "Save file")
        listOf("Lyrics", "Queue", "Player").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            openSongActions()
            editableActions.forEach { songAction(it).assertIsDisplayed().assertIsEnabled() }
            songAction("Edit lyrics").assertDoesNotExist()
        }
        compose.runOnIdle { busy.value = true }
        editableActions.forEach { songAction(it).assertIsNotEnabled() }
        compose.runOnIdle { busy.value = false }
        compose.runOnIdle {
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"inactive"}}""").jsonObject
        }
        songAction("Transcribe lyrics").assertIsNotEnabled()
        compose.runOnIdle {
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        listOf(User(id = "contributor"), User(id = "admin", role = "admin")).forEach { user ->
            compose.runOnIdle { account.value = account.value!!.copy(user = user) }
            compose.onNode(isPopup()).assertDoesNotExist()
            openSongActions()
            editableActions.forEach { songAction(it).assertIsEnabled() }
        }
        listOf(User(id = "unrelated"), User(id = "owner", role = "Shared"),
            User(id = "owner", role = "SHARED")).forEach { user ->
            compose.runOnIdle { account.value = account.value!!.copy(user = user) }
            compose.onNode(isPopup()).assertDoesNotExist()
            openSongActions()
            listOf("Edit metadata", "Transcribe lyrics", "Replace File", "Share Media").forEach {
                songAction(it).assertDoesNotExist()
            }
            songAction("Save file").assertIsEnabled()
            if (user.isShared) {
                songAction("View song metadata").assertIsEnabled()
                compose.runOnIdle { busy.value = true }
                songAction("View song metadata").assertIsNotEnabled()
                songAction("Save file").assertIsNotEnabled()
                compose.runOnIdle { busy.value = false }
            } else songAction("View song metadata").assertDoesNotExist()
        }
        compose.runOnIdle {
            account.value = account.value!!.copy(user = User(id = "owner"))
            state.value = state.value.copy(track = track.copy(name = "video.mp4", mediaType = "video"))
        }
        openSongActions()
        listOf("Edit metadata", "Transcribe lyrics", "Replace File", "Share Media").forEach {
            songAction(it).assertDoesNotExist()
        }
        songAction("Save file").assertIsEnabled()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(role = "Shared")) }
        openSongActions()
        songAction("View song metadata").assertDoesNotExist()
        compose.runOnIdle { account.value = null; state.value = state.value.copy(track = track) }
        openSongActions()
        listOf("Edit metadata", "View song metadata", "Transcribe lyrics", "Replace File", "Share Media").forEach {
            songAction(it).assertDoesNotExist()
        }
        songAction("Save file").assertIsEnabled()
    }

    @Test fun nowPlayingTranscribesCurrentTrackWithCachedLockAndClosesStaleDialogs() {
        val track = Track(jobId = "preview", name = "first.mp3")
        val next = track.copy(name = "next.mp3")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track, next), position = 42_000))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(
                library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))),
                transcriptionLocks = mapOf(next.key to true))
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        listOf("Player", "Lyrics", "Queue").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            openSongActions()
            songAction("Transcribe lyrics").performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.onNodeWithText("Transcribe Song").assertIsDisplayed()
            compose.onNodeWithText("Transcribe").assertIsEnabled()
            compose.onNodeWithText("Generate NoVocals Only").assertIsOff()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        openSongActions()
        songAction("Transcribe lyrics").performClick()
        compose.runOnIdle {
            assertEquals(track, state.value.track)
            assertEquals(42_000L, state.value.position)
            state.value = state.value.copy(track = next, index = 1)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        openSongActions()
        songAction("Transcribe lyrics").assertIsEnabled().performClick()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Generate").assertIsEnabled()
        compose.onNodeWithContentDescription("Unlock transcription").assertIsEnabled()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "owner", role = "shared")) }
        compose.onNode(isDialog()).assertDoesNotExist()
        openSongActions()
        songAction("Transcribe lyrics").assertDoesNotExist()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "owner")) }
        openSongActions()
        songAction("Transcribe lyrics").performClick()
        compose.runOnIdle { account.value = account.value!!.copy(origin = "https://other.example") }
        compose.onNode(isDialog()).assertDoesNotExist()
        openSongActions()
        songAction("Transcribe lyrics").performClick()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
    }

    @Test fun saveFileUsesCurrentTracksDownloadUrlOrEncodedFallbackWithoutChangingPlayback() {
        val track = Track(jobId = "job id", name = "[NoVocals]/first song.mp3", downloadUrl = "/api/custom/download")
        val next = Track(jobId = "job id", name = "[NoVocals]/next song.mp3")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track, next), position = 42_000))
        val downloads = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, state.value) { path, name -> downloads.add(path to name) } }
        }
        openSongActions()
        songAction("Save file").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("/api/custom/download" to track.name), downloads)
            assertEquals(track, state.value.track)
            assertEquals(42_000L, state.value.position)
            state.value = state.value.copy(track = next, index = 1)
        }
        compose.onNodeWithText("Queue").performClick()
        openSongActions()
        songAction("Save file").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("/api/custom/download" to track.name,
                "/api/jobs/job%20id/download/%5BNoVocals%5D%2Fnext%20song.mp3" to next.name), downloads)
            assertEquals(1, state.value.index)
        }
    }

    @Test fun playerDockSongActionsMatchPermissionsBusyStateAndTranscriptionAvailability() {
        val track = Track(jobId = "preview", name = "song.MP3", transcriptionLocked = true)
        val state = mutableStateOf(PlaybackState(track = track))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        var unrelatedActions = 0
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(
                Job("preview", initiatedBy = User(id = "owner"), contributors = listOf(User(id = "contributor"))))))
            metadata.value = SongMetadata(canEdit = true, uslt = "Editable lyrics")
        }
        compose.setContent {
            MusicTheme {
                PlayerDock(state.value, model, { unrelatedActions++ }, { unrelatedActions++ }) { _, _ -> unrelatedActions++ }
            }
        }
        compose.onAllNodesWithContentDescription("More song actions").assertCountEquals(1)
        openSongActions()
        val editableActions = listOf("Edit metadata", "Transcribe lyrics", "Replace File", "Share Media")
        (editableActions + "Save file").forEach { songAction(it).assertIsDisplayed() }
        songAction("Transcribe lyrics").assertIsNotEnabled()
        songAction("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle {
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        (editableActions + "Save file").forEach { songAction(it).assertIsEnabled() }
        compose.runOnIdle { busy.value = true }
        (editableActions + "Save file").forEach { songAction(it).assertIsNotEnabled() }
        compose.runOnIdle { busy.value = false }
        songAction("Transcribe lyrics").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Transcribe Song").assertIsDisplayed()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        openSongActions()
        listOf(User(id = "contributor"), User(id = "admin", role = "admin"), User(id = "unrelated"),
            User(id = "owner", role = "Shared"), User(id = "owner", role = "SHARED"), null).forEach { user ->
            compose.runOnIdle {
                account.value = user?.let { Account("https://music.example", it, Session("test", "2099-01-01T00:00:00Z")) }
            }
            compose.onNode(isPopup()).assertDoesNotExist()
            openSongActions()
            val editable = user?.id == "contributor" || user?.role == "admin"
            editableActions.forEach {
                if (editable) songAction(it).assertIsEnabled() else songAction(it).assertDoesNotExist()
            }
            if (user?.isShared == true) {
                songAction("View song metadata").assertIsEnabled()
                compose.runOnIdle { busy.value = true }
                songAction("View song metadata").assertIsNotEnabled()
                songAction("Save file").assertIsNotEnabled()
                compose.runOnIdle { busy.value = false }
            } else songAction("View song metadata").assertDoesNotExist()
            songAction("Save file").assertIsEnabled()
            songAction("Edit lyrics").assertDoesNotExist()
        }
        listOf(User(id = "owner"), User(id = "owner", role = "Shared")).forEach { user ->
            compose.runOnIdle {
                account.value = Account("https://music.example", user, Session("test", "2099-01-01T00:00:00Z"))
                state.value = state.value.copy(track = track.copy(name = "video.mp4", mediaType = "video"))
            }
            openSongActions()
            (editableActions + "View song metadata").forEach { songAction(it).assertDoesNotExist() }
            songAction("Save file").assertIsEnabled()
        }
        compose.runOnIdle { assertEquals(0, unrelatedActions) }
    }

    @Test fun playerDockDownloadsCurrentTrackAndDismissesStaleActionsWithoutExpanding() {
        val track = Track(jobId = "job id", name = "[NoVocals]/first song.mp3", downloadUrl = "/api/custom/download")
        val next = Track(jobId = "job id", name = "[NoVocals]/next song.mp3")
        val state = mutableStateOf(PlaybackState())
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val downloads = mutableListOf<Pair<String, String>>()
        var expanded = 0
        var notifications = 0
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent {
            MusicTheme {
                PlayerDock(state.value, model, { expanded++ }, { notifications++ }) { path, name -> downloads.add(path to name) }
            }
        }
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
        compose.runOnIdle { state.value = PlaybackState(track = track, queue = listOf(track, next), position = 42_000) }
        openSongActions()
        songAction("Save file").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        openSongActions()
        compose.runOnIdle { state.value = state.value.copy(position = 43_000) }
        songAction("Save file").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(track = next, index = 1) }
        compose.onNode(isPopup()).assertDoesNotExist()
        openSongActions()
        songAction("Save file").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        val original = account.value!!
        listOf(original.copy(origin = "https://other.example"),
            original.copy(origin = "https://other.example", user = User(id = "other")),
            original.copy(origin = "https://other.example", user = User(id = "other", role = "admin")),
            null).forEach { changed ->
            openSongActions()
            compose.runOnIdle { account.value = changed }
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(listOf("/api/custom/download" to track.name,
                "/api/jobs/job%20id/download/%5BNoVocals%5D%2Fnext%20song.mp3" to next.name), downloads)
            assertEquals(PlaybackState(track = next, queue = listOf(track, next), position = 43_000, index = 1), state.value)
            assertEquals(0, expanded)
            assertEquals(0, notifications)
        }
        openSongActions()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
    }

    @Test fun songActionsDismissWhenTrackTabOrAccountIdentityChanges() {
        val track = Track(jobId = "preview", name = "first.mp3")
        val state = mutableStateOf(PlaybackState(track = track, position = 42_000))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        var downloads = 0
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> downloads++ } } }
        openSongActions()
        compose.runOnIdle { state.value = state.value.copy(position = 43_000) }
        songAction("Save file").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(track = track.copy(name = "next.mp3")) }
        compose.onNode(isPopup()).assertDoesNotExist()
        listOf("Lyrics", "Queue", "Player").forEach { tab ->
            openSongActions()
            compose.onNodeWithText(tab).performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.onNodeWithText(tab).assertIsSelected()
        }
        val original = account.value!!
        listOf(original.copy(origin = "https://other.example"),
            original.copy(origin = "https://other.example", user = User(id = "other")),
            original.copy(origin = "https://other.example", user = User(id = "other", role = "admin")),
            null).forEach { changed ->
            openSongActions()
            compose.runOnIdle { account.value = changed }
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        openSongActions()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, downloads) }
    }

    @Test fun songActionsDismissOnBackAndOutsideTapWithoutChangingPlayback() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = PlaybackState(track = track, queue = listOf(track), position = 42_000)
        var downloads = 0
        compose.setContent { MusicTheme { NowPlayingScreen(model, state) { _, _ -> downloads++ } } }
        openSongActions()
        // Popup dismissal is handled by its Android window, not Compose's key/touch dispatcher.
        compose.runOnUiThread {
            val popup = songActionsWindow()
            assertTrue(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertTrue(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        }
        waitForPopupDismissal()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Player").assertIsSelected()
        openSongActions()
        compose.runOnUiThread {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
            try {
                assertTrue(songActionsWindow().dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        waitForPopupDismissal()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertIsDisplayed()
        compose.onNodeWithText("0:42").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, downloads) }
    }

    @Test fun noVocalsOnlyHidesOtherOptionsAndRestoresItsSelection() {
        var submitted: TranscriptionOptions? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MusicTheme { TranscribeDialog({}, { submitted = it }) }
        }
        compose.onNodeWithText("Generate NoVocals Only").assertIsOff()
        compose.onNodeWithText("Generate NoVocals Only").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.onNodeWithText("Generate").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("""{"NoVocalsOnly":true}""", submitted!!.toRequestBody().toString())
        }
        compose.onNodeWithText("Generate NoVocals Only").performClick().assertIsOff()
        assertOrdinaryTranscriptionOptionsVisible()
    }

    @Test fun lockedTranscriptionDialogOnlyAllowsInstrumentalGenerationAndHonorsAvailability() {
        val available = mutableStateOf(true)
        val busy = mutableStateOf(false)
        var submitted: TranscriptionOptions? = null
        var lockCount = 0
        compose.setContent {
            MusicTheme {
                TranscribeDialog({}, { submitted = it }, available = available.value, busy = busy.value,
                    transcriptionLocked = true, lock = { lockCount++ })
            }
        }
        compose.onNodeWithContentDescription("Unlock transcription").assertIsDisplayed().assertIsOn()
        compose.onNodeWithContentDescription("Lock transcription").assertDoesNotExist()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn().assertIsNotEnabled()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.onNodeWithText("Generate").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(TranscriptionOptions(noVocalsOnly = true), submitted)
            assertEquals(0, lockCount)
            available.value = false
        }
        compose.onNodeWithText("Generate").assertIsNotEnabled()
        compose.onNodeWithText(INACTIVE_TRANSCRIPTION_MESSAGE).assertIsDisplayed()
        compose.runOnIdle { available.value = true; busy.value = true }
        compose.onNodeWithContentDescription("Unlock transcription").assertIsNotEnabled()
        compose.onNodeWithText("Generate").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun unlockedSongKeepsSeparateLockActionWithoutSubmittingTranscription() {
        var submissions = 0
        val locks = mutableListOf<Boolean>()
        compose.setContent {
            MusicTheme { TranscribeDialog({}, { submissions++ }, available = false, lock = { locks.add(it) }) }
        }
        compose.onNodeWithText("Generate NoVocals Only").performClick()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithText("Generate NoVocals Only").assertDoesNotExist()
        compose.onNodeWithText("Lock transcription").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(true), locks); assertEquals(0, submissions) }
    }

    @Test fun lockedSongCanConfirmUnlockWithoutTranscribingAndRestorePendingChange() {
        val busy = mutableStateOf(false)
        val locked = mutableStateOf(true)
        val locks = mutableListOf<Boolean>()
        var submissions = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MusicTheme {
                TranscribeDialog({}, { submissions++ }, busy = busy.value, available = false,
                    transcriptionLocked = locked.value, lock = { locks.add(it); locked.value = it })
            }
        }
        compose.onNodeWithContentDescription("Unlock transcription").performClick()
        compose.onNodeWithText("Generate NoVocals Only").assertDoesNotExist()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.onNodeWithText("Unlock transcription").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Lock transcription").assertIsOff()
        compose.onNodeWithText("Unlock transcription").assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), locks); assertEquals(0, submissions); busy.value = true }
        compose.onNodeWithContentDescription("Lock transcription").assertIsNotEnabled()
        compose.onNodeWithText("Unlock transcription").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("Unlock transcription").performClick()
        compose.runOnIdle { assertEquals(listOf(false), locks); assertEquals(0, submissions) }
        compose.onNodeWithContentDescription("Lock transcription").assertIsOff()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOff().assertIsEnabled()
        compose.onNodeWithText("Auto-detect").assertIsDisplayed()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
    }

    @Test fun undoingOrCancellingUnlockDoesNotChangeLockOrSubmitTranscription() {
        var submissions = 0
        var lockChanges = 0
        var dismissals = 0
        compose.setContent {
            MusicTheme {
                TranscribeDialog({ dismissals++ }, { submissions++ }, transcriptionLocked = true,
                    lock = { lockChanges++ })
            }
        }
        compose.onNodeWithContentDescription("Unlock transcription").performClick()
        compose.onNodeWithText("Unlock transcription").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithContentDescription("Unlock transcription").assertIsOn()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Generate").assertIsEnabled()
        compose.onNodeWithContentDescription("Unlock transcription").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, submissions)
            assertEquals(0, lockChanges)
        }
    }

    @Test fun lockedSongMenuOpensNoVocalsAndUnlockDialogOnlyForPermittedUsers() {
        val track = Track(jobId = "preview", name = "song.mp3", transcriptionLocked = true)
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))))
        }
        compose.setContent { MusicTheme { TrackMenu(model, track, 0) { _, _ -> } } }
        compose.onNodeWithContentDescription("Options for song").performClick()
        compose.onNodeWithText("Transcribe lyrics").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        compose.onNodeWithText("Transcribe lyrics").assertIsEnabled().performClick()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Generate").assertIsEnabled()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.onNodeWithContentDescription("Unlock transcription").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "owner", role = "shared")) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for song").performClick()
        compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "unrelated")) }
        compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
    }

    @Test fun libraryMenusDoNotOfferMoveUpOrMoveDown() {
        val files = listOf(Track(name = "[NoVocals]/first.mp3"), Track(name = "first.mp3"),
            Track(name = "[novocals]/last.mp3"), Track(name = "last.mp3"))
        val selected = mutableStateOf(0)
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            library.value = LibraryState(tracks = TrackPage(files = files))
        }
        compose.setContent {
            MusicTheme { TrackMenu(model, files[selected.value], selected.value) { _, _ -> } }
        }
        compose.onNodeWithContentDescription("Options for first").performClick()
        files.indices.forEach { index ->
            compose.runOnIdle { selected.value = index }
            compose.onNodeWithText("Move up").assertDoesNotExist()
            compose.onNodeWithText("Move down").assertDoesNotExist()
        }
    }

    @Test fun shareMediaIsAvailableForAuthorizedAudioSongsFromLibraryAndNowPlaying() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track)))
        val showMenu = mutableStateOf(false)
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"),
                Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))))
        }
        compose.setContent {
            MusicTheme {
                if (showMenu.value) TrackMenu(model, track, 0) { _, _ -> }
                else NowPlayingScreen(model, state.value) { _, _ -> }
            }
        }
        openSongActions()
        songAction("Share Media").assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Anyone with this link can listen", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Generate public link").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle { state.value = state.value.copy(track = track.copy(mediaType = "video")) }
        openSongActions()
        songAction("Share Media").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(track = track) }
        compose.runOnIdle { showMenu.value = true }
        compose.onNodeWithContentDescription("Options for song").performClick()
        compose.onNodeWithText("Share Media").assertIsDisplayed().performClick()
        compose.onNodeWithText("Generate public link").assertIsDisplayed()
    }

    @Test fun songActionsPopupMatcherIgnoresNonMenuPopups() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            MusicTheme {
                Box {
                    Popup { Text("More song actions") }
                    DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                        DropdownMenuItem(text = { Text("Save file") }, onClick = { expanded.value = false })
                    }
                }
            }
        }
        compose.onNode(isPopup()).assertExists()
        compose.onNode(isSongActionsPopup).assertDoesNotExist()
        compose.runOnIdle { expanded.value = true }
        compose.onAllNodes(isPopup()).assertCountEquals(2)
        compose.onNode(isSongActionsPopup).assertIsDisplayed()
        songAction("Save file").performClick()
        waitForPopupDismissal()
        compose.onNode(isSongActionsPopup).assertDoesNotExist()
        compose.onNode(isPopup()).assertExists()
    }

    private fun openSongActions() {
        compose.onNode(isSongActionsPopup).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(300)
        compose.onNode(isSongActionsPopup).assertIsDisplayed()
    }

    private fun songAction(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isSongActionsPopup))

    private fun songActionsWindow() = WindowInspector.getGlobalWindowViews().single {
        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
    }

    private fun waitForPopupDismissal() {
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(isSongActionsPopup).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun songHeading(title: String) = compose.onNode(hasText(title) and isSongHeading)

    private fun assertOnlySongOverflowBesideTitle(track: Track, showPlaylist: Boolean = true) {
        compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
        compose.onAllNodesWithContentDescription("More song actions").assertCountEquals(1)
        val titleBounds = songHeading(track.displayTitle).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val overflow = compose.onNodeWithContentDescription("More song actions")
            .assertIsDisplayed().assert(hasAnyAncestor(hasTestTag("now-playing-top-bar"))).fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.width > 0f)
        assertTrue(titleBounds.right <= overflow.left)
        assertTrue(titleBounds.center.y in overflow.top..overflow.bottom)
        if (showPlaylist) {
            val contextBounds = compose.onNodeWithText(track.playlistTitle).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(titleBounds.bottom <= contextBounds.top)
            assertTrue(contextBounds.right <= overflow.left)
        }
        listOf("Edit lyrics", "Edit metadata", "View song metadata", "Transcribe lyrics",
            "Replace File", "Share Media", "Save file").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun assertOrdinaryTranscriptionOptionsHidden() {
        listOf("Auto-detect", "Multilingual", "Create no-vocals version [Karaoke version]",
            "Viet Lyrics Fallback", "Add lyrics", "Lyrics mode", "Transcribe").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun assertOrdinaryTranscriptionOptionsVisible() {
        listOf("Auto-detect", "Multilingual", "Create no-vocals version [Karaoke version]",
            "Viet Lyrics Fallback", "Add lyrics", "Transcribe").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun sharedUsersCannotSeePlaylistTransferActionsEvenForTheirOwnSongs() {
        val track = Track(jobId = "preview", name = "song.mp3", playlistId = "source")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner", role = "Shared"),
                Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))))
        }
        compose.setContent { MusicTheme { TrackMenu(model, track, 0) { _, _ -> } } }
        compose.onNodeWithContentDescription("Options for song").performClick()
        listOf("Shared", "shared", "SHARED").forEach { role ->
            compose.runOnIdle { account.value = account.value!!.copy(user = account.value!!.user.copy(role = role)) }
            compose.onNodeWithText("Add to playlist").assertDoesNotExist()
            compose.onNodeWithText("Move to playlist").assertDoesNotExist()
            compose.onNodeWithText("Add to queue").assertIsDisplayed()
            compose.onNodeWithText("Save file").assertIsDisplayed()
            compose.onNodeWithText("Share Media").assertDoesNotExist()
            compose.onNodeWithText("View song metadata").assertIsDisplayed()
            compose.onNodeWithText("Edit song / rating").assertDoesNotExist()
            compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
        }
    }

    // Robolectric #8460: text fields in dialogs loop at widths above the default 320dp.
    @Config(qualifiers = "w320dp-h800dp")
    @Test fun nowPlayingMetadataActionOpensEditorAfterClosingMenu() {
        compose.mainClock.autoAdvance = false
        val track = Track(jobId = "source", name = "song.mp3")
        val inDock = mutableStateOf(false)
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val requests = metadataResponses { 200 to ApiJson.encodeToString(
            SongMetadata(title = "Editable song", canEdit = true)) }
        compose.runOnIdle {
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("source", initiatedBy = account.value!!.user))))
        }
        compose.setContent {
            MusicTheme {
                if (inDock.value) PlayerDock(PlaybackState(track = track), model, {}, {}) { _, _ -> }
                else NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> }
            }
        }
        listOf(false, true).forEach { dock ->
            compose.runOnIdle { inDock.value = dock; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            openSongActions()
            songAction("Edit metadata").performClick()
            waitForPopupDismissal()
            compose.onNode(isSongActionsPopup).assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.runOnIdle { Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText("Editable song").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(isDialog()).assertIsDisplayed()
            compose.onNodeWithText("Song information").assertIsDisplayed()
            compose.onNodeWithText("Title").assertTextContains("Editable song").assert(hasSetTextAction())
            compose.onNodeWithText("Save").assertIsEnabled()
            compose.onNodeWithText("Cancel").performClick()
            waitForPopupDismissal()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(isSongActionsPopup).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(List(2) { songPath(track, "lyrics") }, requests.map { it.url.encodedPath })
            assertTrue(requests.all { it.method == "GET" })
        }
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun sharedMetadataActionsReadAudioIncludingNoVocalsAndNonMp3WithoutWriting() {
        compose.mainClock.autoAdvance = false
        val track = mutableStateOf(Track(jobId = "source job", name = "song.mp3", playlistId = "shared"))
        val surface = mutableStateOf("Library")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val requests = metadataResponses { 200 to ApiJson.encodeToString(
            SongMetadata(title = "Fetched title", rating = 3, transcriptionLocked = true, canEdit = true)) }
        compose.runOnIdle {
            account.value = Account("https://music.example", User(id = "reader", role = "Shared"),
                Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent {
            MusicTheme {
                when (surface.value) {
                    "Library" -> TrackMenu(model, track.value, 0) { _, _ -> }
                    "Player" -> NowPlayingScreen(model, PlaybackState(track = track.value)) { _, _ -> }
                    else -> PlayerDock(PlaybackState(track = track.value), model, {}, {}) { _, _ -> }
                }
            }
        }
        val expectedPaths = mutableListOf<String>()
        for (location in listOf("Library", "Player", "Dock")) {
            compose.runOnIdle { surface.value = location; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            for (name in listOf("song.mp3", "[NoVocals]/song.MP3", "song.flac")) {
                compose.runOnIdle { track.value = track.value.copy(name = name); Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                if (location == "Library") {
                    compose.onNodeWithContentDescription("Options for ${track.value.displayTitle}").performClick()
                    compose.runOnIdle { Snapshot.sendApplyNotifications() }
                    compose.mainClock.advanceTimeBy(300)
                } else openSongActions()
                songAction("View song metadata").performClick()
                waitForPopupDismissal()
                compose.onNode(isSongActionsPopup).assertDoesNotExist()
                compose.waitUntil(5_000) {
                    compose.runOnIdle { Snapshot.sendApplyNotifications() }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithText("Fetched title").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(isDialog()).assertIsDisplayed()
                compose.onNodeWithText("Title").assertTextContains("Fetched title")
                compose.onNodeWithContentDescription("Transcription locked").assertHasNoClickAction()
                compose.onNode(hasSetTextAction()).assertDoesNotExist()
                compose.onNodeWithText("Save").assertDoesNotExist()
                compose.onNodeWithContentDescription("3 stars").assertDoesNotExist()
                compose.onNodeWithText("Close").performClick()
                waitForPopupDismissal()
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNode(isSongActionsPopup).assertDoesNotExist()
                expectedPaths += songPath(track.value, "lyrics")
            }
        }
        compose.runOnIdle {
            assertEquals(expectedPaths, requests.map { it.url.encodedPath })
            assertTrue(requests.all { it.method == "GET" })
            surface.value = "Library"
            track.value = track.value.copy(name = "movie.mp4", mediaType = "video")
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Options for movie").performClick()
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("View song metadata").assertDoesNotExist()
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun metadataViewerClearsPriorAccountDataAndShowsDeniedAccessWithoutSave() {
        compose.mainClock.autoAdvance = false
        val track = Track(jobId = "source", name = "song.mp3")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val requests = metadataResponses {
            if (model.sessions.account.value?.user?.id == "reader")
                200 to ApiJson.encodeToString(SongMetadata(title = "Granted song"))
            else 403 to """{"error":"Access denied"}"""
        }
        compose.runOnIdle {
            account.value = Account("https://music.example", User(id = "reader", role = "Shared"),
                Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicTheme { MetadataDialog(model, track) {} } }
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("Granted song").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "other", role = "shared")) }
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("Access denied").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Granted song").assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.method == "GET" && it.url.encodedPath == songPath(track, "lyrics") })
        }
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun metadataDialogHonorsServerEditingPermissionForLocalOwner() {
        compose.mainClock.autoAdvance = false
        val track = Track(jobId = "source", name = "song.mp3")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        metadataResponses { 200 to ApiJson.encodeToString(SongMetadata(title = "Read-only song", canEdit = false)) }
        compose.runOnIdle {
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("source", initiatedBy = account.value!!.user))))
        }
        compose.setContent { MusicTheme { MetadataDialog(model, track) {} } }
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("Read-only song").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("View song metadata").assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun metadataResponses(response: (Request) -> Pair<Int, String>): MutableList<Request> {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (status, body) = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test response")
                .body(body.toResponseBody()).build()
        }.build()
        compose.runOnIdle {
            model.viewModelScope.cancel()
            ReflectionHelpers.setField(model, "api", ServerApi({ model.sessions.account.value }, {}, client))
        }
        return requests
    }

    @Test fun nonSharedUsersKeepPlaylistTransferActionsOnlyForPlaylistTracks() {
        val track = mutableStateOf(Track(jobId = "preview", name = "song.mp3", playlistId = "source"))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(role = "user"), Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicTheme { TrackMenu(model, track.value, 0) { _, _ -> } } }
        compose.onNodeWithContentDescription("Options for song").performClick()
        listOf("user", "admin").forEach { role ->
            compose.runOnIdle { account.value = account.value!!.copy(user = User(role = role)) }
            compose.onNodeWithText("Add to playlist").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Move to playlist").assertIsDisplayed().assertIsEnabled()
        }
        compose.runOnIdle { track.value = track.value.copy(playlistId = null) }
        compose.onNodeWithText("Add to playlist").assertDoesNotExist()
        compose.onNodeWithText("Move to playlist").assertDoesNotExist()
    }

    @Test fun switchingToSharedRoleClosesAndClearsPlaylistTransferDialogs() {
        val track = Track(jobId = "preview", name = "song.mp3", playlistId = "source")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(role = "user"), Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicTheme { TrackMenu(model, track, 0) { _, _ -> } } }
        listOf("Add to playlist", "Move to playlist").forEach { action ->
            compose.onNodeWithContentDescription("Options for song").performClick()
            compose.onNodeWithText(action).performClick()
            compose.onNode(isDialog()).assertIsDisplayed()
            compose.onNodeWithText(action).assertIsDisplayed()
            compose.runOnIdle { account.value = account.value!!.copy(user = User(role = "Shared")) }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { account.value = account.value!!.copy(user = User(role = "user")) }
            compose.onNode(isDialog()).assertDoesNotExist()
        }
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
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        openSongActions()
        songAction("Edit lyrics").assertDoesNotExist()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        val overflow = compose.onNodeWithContentDescription("More song actions").assertIsDisplayed()
        assertTrue(overflow.fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithText("SYLT Lyrics").fetchSemanticsNode().boundsInRoot.top)
        openSongActions()
        songAction("Edit lyrics").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithTag("sylt-editor").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        listOf("Player", "Queue").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            openSongActions()
            songAction("Edit lyrics").assertDoesNotExist()
        }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("Plain line").assertIsDisplayed()
        openSongActions()
        songAction("Edit lyrics").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithTag("uslt-editor").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        compose.runOnIdle { busy.value = true }
        openSongActions()
        songAction("Edit lyrics").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        songAction("Edit lyrics").assertIsEnabled()
        compose.runOnIdle { metadata.value = metadata.value!!.copy(canEdit = false) }
        songAction("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle {
            metadata.value = metadata.value!!.copy(canEdit = true)
            account.value = account.value!!.copy(user = User(role = "Shared"))
        }
        openSongActions()
        songAction("Edit lyrics").assertDoesNotExist()
    }

    @Test fun lyricsEditingRequiresLoadedPermissionAndMp3AndClearsOnAccountOrPlaybackReset() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        compose.onNodeWithText("Lyrics").performClick()
        openSongActions()
        songAction("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle { metadata.value = SongMetadata(canEdit = true) }
        songAction("Edit lyrics").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(track = track.copy(name = "song.flac")) }
        openSongActions()
        songAction("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(track = track) }
        openSongActions()
        songAction("Edit lyrics").performClick()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:00:00.000] Unsaved draft")
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "another-owner")) }
        compose.onNode(isDialog()).assertDoesNotExist()
        openSongActions()
        songAction("Edit lyrics").assertDoesNotExist()
        compose.runOnIdle { metadata.value = SongMetadata(canEdit = true) }
        songAction("Edit lyrics").performClick()
        compose.onNodeWithTag("sylt-editor").assertTextContains("")
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("More song actions").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun compactHeaderKeepsOnlyOverflowBesideTitleAndAllLyricsActionsInMenu() {
        val track = Track(jobId = "preview", name = "song.mp3", playlistTitle = "A long playlist title ".repeat(5))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        val downloads = mutableListOf<Pair<String, String>>()
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))))
            metadata.value = SongMetadata(uslt = "Plain lyrics", canEdit = true)
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { path, name -> downloads.add(path to name) } }
        }
        compose.onNodeWithText("USLT Lyrics").performClick()
        assertOnlySongOverflowBesideTitle(track)
        openSongActions()
        val actions = listOf("Edit lyrics", "Edit metadata", "Transcribe lyrics", "Replace File",
            "Make song private", "Share Media", "Save file")
        actions.forEach {
            songAction(it).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(actions.size)
        compose.runOnIdle { busy.value = true }
        actions.forEach { songAction(it).assertIsNotEnabled() }
        compose.runOnIdle { busy.value = false }
        songAction("Save file").performClick()
        compose.runOnIdle { assertEquals(listOf(songPath(track, "download") to track.name), downloads) }
        compose.onNode(isPopup()).assertDoesNotExist()
        openSongActions()
        songAction("Replace File").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Choose replacement file").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        listOf("Player", "Queue").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            assertOnlySongOverflowBesideTitle(track)
            openSongActions()
            songAction("Edit lyrics").assertDoesNotExist()
            actions.filterNot { it == "Edit lyrics" }.forEach { songAction(it).assertIsDisplayed().assertIsEnabled() }
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h800dp-land")
    fun landscapeHeaderKeepsAllSongActionsInOverflowAcrossPlayerAndLyricsTabs() {
        val track = Track(jobId = "preview", name = "song.mp3", playlistTitle = "A long playlist title ".repeat(5))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val health = ReflectionHelpers.getField<MutableState<JsonObject?>>(model, "health\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("preview", initiatedBy = account.value!!.user))))
            metadata.value = SongMetadata(uslt = "Plain lyrics", canEdit = true)
            health.value = ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        compose.onNodeWithTag("now-playing-queue-pane").assertIsDisplayed()
        val topBar = compose.onNodeWithTag("now-playing-top-bar").getUnclippedBoundsInRoot()
        val playerPane = compose.onNodeWithTag("now-playing-pane").getUnclippedBoundsInRoot()
        val queuePane = compose.onNodeWithTag("now-playing-queue-pane").getUnclippedBoundsInRoot()
        assertEquals(topBar.bottom, playerPane.top)
        assertEquals(topBar.bottom, queuePane.top)
        assertEquals(topBar.right, queuePane.right)
        compose.onNodeWithText("Queue").assertHasNoClickAction()
        listOf("Player", "USLT Lyrics").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            assertOnlySongOverflowBesideTitle(track, showPlaylist = false)
            openSongActions()
            listOf("Edit metadata", "Transcribe lyrics", "Replace File", "Share Media", "Save file").forEach {
                songAction(it).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            }
            if (tab == "USLT Lyrics") songAction("Edit lyrics").assertIsDisplayed().assertIsEnabled()
            else songAction("Edit lyrics").assertDoesNotExist()
            songAction("View song metadata").assertDoesNotExist()
        }
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
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        compose.onNodeWithText("USLT Lyrics").performClick()
        openSongActions()
        songAction("Edit lyrics").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
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
        restoration.setContent { MusicTheme { NowPlayingScreen(model, state) { _, _ -> } } }
        compose.onNodeWithText("Player").assertIsSelected()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player").assertDoesNotExist()
        compose.onNodeWithText("Lyrics").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithText("Queue").performClick().assertIsSelected()
        compose.onNode(hasText("Blue hour") and !isSongHeading).assertIsDisplayed()
        compose.onNodeWithContentDescription("Options for Blue hour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove from queue").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Queue").assertIsSelected()
        compose.onNode(hasText("Blue hour") and !isSongHeading).assertIsDisplayed()
    }

    @Test fun queueMenusKeepAllowedLibraryActionsAndDownloadTheSelectedSong() {
        val first = Track("source", "first.mp3", playlistId = "playlist")
        val next = first.copy(name = "next.mp3", downloadUrl = "/api/download/next")
        val state = PlaybackState(track = first, queue = listOf(first, next, next))
        val downloads = mutableListOf<Pair<String, String>>()
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner"), Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(
                library = Library(jobs = listOf(Job("source", initiatedBy = account.value!!.user))),
                selectedId = "playlist", tracks = TrackPage(files = listOf(next, first)))
        }
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, state) { url, name -> downloads += url to name } }
        }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Remove from queue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for first").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Options for next").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Options for next")[1].performClick()
        listOf("Add to queue", "Move to playlist", "Remove from playlist", "Move up", "Move down").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        listOf("Save file", "Share Media", "Make song private", "Add to playlist", "Edit song / rating",
            "Transcribe lyrics", "Replace File", "Remove from queue").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Add to playlist").performScrollTo().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Add to playlist").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        listOf(first, next, next).forEachIndexed { index, track ->
            val occurrence = if (index == 2) 1 else 0
            compose.onAllNodesWithContentDescription("Options for ${track.displayTitle}")[occurrence].performClick()
            compose.onNodeWithText("Save file").performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        assertEquals(listOf(songPath(first, "download") to first.name,
            next.downloadUrl!! to next.name, next.downloadUrl!! to next.name), downloads)
        songHeading(first.displayTitle).assertIsDisplayed()
    }

    @Test fun queueMenusRespectSharedAndUnrelatedUserPermissions() {
        val track = Track("source", "song.mp3", playlistId = "playlist")
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User(id = "owner", role = "Shared"),
                Session("test", "2099-01-01T00:00:00Z"))
            library.value = LibraryState(library = Library(jobs = listOf(Job("source", initiatedBy = User(id = "owner")))))
        }
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> } }
        }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Options for song").performClick()
        listOf("Save file", "View song metadata", "Remove from queue").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Add to queue", "Add to playlist", "Move to playlist", "Remove from playlist",
            "Edit song / rating", "Transcribe lyrics", "Replace File", "Share Media", "Make song private").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Remove from queue").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "unrelated")) }
        compose.onNodeWithContentDescription("Options for song").performClick()
        listOf("Save file", "Add to playlist", "Remove from queue").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Add to queue", "Move to playlist", "Remove from playlist", "View song metadata",
            "Edit song / rating", "Transcribe lyrics", "Replace File", "Share Media", "Make song private").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun queueMenuRemovalUsesTheSelectedOccurrenceWithoutPlaylistRequests() {
        val track = Track("source", "song.mp3", playlistId = "playlist")
        val queue = mutableStateOf(listOf(track, track.copy(name = "other.mp3"), track))
        val removed = mutableListOf<Int>()
        val requests = metadataResponses { 500 to "{}" }
        compose.setContent {
            MusicTheme {
                Column {
                    queue.value.forEachIndexed { index, queued ->
                        TrackMenu(model, queued, index, onRemoveFromQueue = {
                            removed += index
                            queue.value = queue.value.filterIndexed { position, _ -> position != index }
                        }) { _, _ -> }
                    }
                }
            }
        }
        compose.onAllNodesWithContentDescription("Options for song")[1].performClick()
        compose.onNodeWithText("Remove from queue").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options for other").assertIsDisplayed()
        assertEquals(listOf(2), removed)
        assertEquals(listOf(track, track.copy(name = "other.mp3")), queue.value)
        assertTrue(requests.isEmpty())
    }

    @Test fun playbackAndTabsUpdateWhileSnackbarRemainsVisible() {
        val first = Track("preview", "first.mp3", title = "First song")
        val next = Track("preview", "next.mp3", title = "Next song")
        val state = mutableStateOf(PlaybackState(track = first, queue = listOf(first, next)))
        val snackbar = SnackbarHostState()
        compose.setContent {
            MusicTheme {
                NowPlayingScreen(model, state.value, snackbar = snackbar) { _, _ -> }
                LaunchedEffect(snackbar) {
                    snackbar.showSnackbar("Queue updated", duration = SnackbarDuration.Indefinite)
                }
            }
        }
        compose.onNodeWithText("Queue updated").assertIsDisplayed()
        compose.onNodeWithText("Lyrics").performClick().assertIsSelected()
        compose.runOnIdle {
            state.value = state.value.copy(track = next, index = 1)
            Snapshot.sendApplyNotifications()
        }
        songHeading("Next song").assertIsDisplayed()
        compose.onNodeWithText("Queue").performClick().assertIsSelected()
        compose.onNode(hasText("Next song") and !isSongHeading).assertIsDisplayed()
        compose.onNodeWithText("Player").performClick().assertIsSelected()
        compose.onNodeWithText("Queue updated").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test fun libraryUnratedSongOpensRatingDialogEvenWhenPlaybackIsDisconnected() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle { library.value = LibraryState(tracks = TrackPage(files = listOf(track))) }
        compose.setContent { MusicTheme { LibraryScreen(model, PlaybackState(), {}, { _, _ -> }) } }
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Song rating").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed()
    }

    @Test fun playerRatingStaysAboveProgressAndReflectsClearedRatingsAndTrackChanges() {
        val track = Track("preview", "song.mp3", rating = 4)
        val next = track.copy(name = "next.mp3", rating = 1)
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track, next),
            position = 42_000, duration = 60_000))
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        val rating = compose.onNodeWithContentDescription("Rating: 4 out of 5")
            .assertIsDisplayed().assertHasNoClickAction().getUnclippedBoundsInRoot()
        val progress = compose.onNodeWithContentDescription("Playback position").getUnclippedBoundsInRoot()
        assertTrue(rating.bottom <= progress.top)
        assertTrue(rating.bottom - rating.top >= 48.dp)
        compose.runOnIdle { library.value = library.value.copy(ratings = mapOf(track.key to 2)) }
        compose.onNodeWithContentDescription("Rating: 2 out of 5").assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.copy(ratings = mapOf(track.key to 0))
                .withTrackPage(TrackPage(files = listOf(Track("other", "other.mp3"))))
        }
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Lyrics").performClick()
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertDoesNotExist()
        compose.onNodeWithText("Queue").performClick()
        compose.onAllNodesWithContentDescription("Rating: 0 out of 5").assertCountEquals(1)
        compose.onNodeWithText("Player").performClick()
        compose.runOnIdle { state.value = state.value.copy(track = next, index = 1) }
        compose.onNodeWithContentDescription("Rating: 1 out of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertDoesNotExist()
        compose.onNodeWithText("0:42").assertIsDisplayed()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNodeWithContentDescription("Rating: 1 out of 5").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun playerRatingFitsAboveProgressAlongsideLandscapeQueue() {
        val track = Track("preview", "song.mp3", rating = 3)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track),
                duration = 60_000)) { _, _ -> } }
        }
        val rating = compose.onNode(hasContentDescription("Rating: 3 out of 5") and
            hasAnyAncestor(hasTestTag("now-playing-pane"))).assertIsDisplayed().assertHasNoClickAction()
        val progress = compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        assertTrue(rating.getUnclippedBoundsInRoot().bottom <= progress.getUnclippedBoundsInRoot().top)
        (1..5).forEach { compose.onNodeWithContentDescription("$it stars").assertIsDisplayed() }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNode(hasContentDescription("Rating: 3 out of 5") and
            hasAnyAncestor(hasTestTag("now-playing-queue-pane"))).assertIsDisplayed()
        compose.onNodeWithText("Lyrics").performClick()
        rating.assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Rating: 3 out of 5").assertCountEquals(1)
    }

    @Test fun playerRatingStarsSelectAndClearDirectly() {
        val rating = mutableStateOf(3)
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<Int>()
        compose.setContent {
            MusicTheme {
                PlayerRating(rating.value, enabled.value) { changes.add(it); rating.value = it }
            }
        }
        listOf(5, 2, 1, 4, 3).forEach { value ->
            compose.onNodeWithContentDescription("$value stars").performClick().assertIsOn()
            compose.onNodeWithContentDescription("Rating: $value out of 5").assertIsDisplayed()
            (1..5).forEach { star ->
                val node = compose.onNodeWithContentDescription("$star stars")
                    .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                if (star <= value) node.assertIsOn() else node.assertIsOff()
            }
            compose.onNodeWithContentDescription("$value stars").performClick().assertIsOff()
            compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        compose.runOnIdle { enabled.value = false }
        (1..5).forEach { compose.onNodeWithContentDescription("$it stars").assertIsNotEnabled().performClick() }
        compose.runOnIdle { assertEquals(listOf(5, 0, 2, 0, 1, 0, 4, 0, 3, 0), changes) }
    }

    @Test fun playerRatingHonorsSharedServerAndFileEditingRestrictions() {
        val track = mutableStateOf(Track("source", "song.mp3", rating = 3))
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val busy = ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate")
        val requests = metadataResponses { 500 to "{}" }
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track.value)) { _, _ -> } } }
        listOf(Triple(User("owner", role = "Shared"), "song.mp3", true),
            Triple(User("owner"), "song.mp3", false),
            Triple(User("other"), "song.mp3", true),
            Triple(User("owner"), "song.flac", true)).forEach { (user, name, canEdit) ->
            compose.runOnIdle {
                account.value = Account("https://music.example", user, Session("test", "2099-01-01T00:00:00Z"))
                library.value = LibraryState(library = Library(jobs = listOf(Job("source", initiatedBy = User("owner")))))
                track.value = track.value.copy(name = name)
                metadata.value = SongMetadata(rating = 3, canEdit = canEdit)
            }
            compose.onNodeWithContentDescription("Rating: 3 out of 5").assertIsDisplayed()
            (1..5).forEach { compose.onNodeWithContentDescription("$it stars").assertIsNotEnabled().performClick() }
            compose.onNodeWithText("Save").assertDoesNotExist()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        compose.runOnIdle {
            track.value = track.value.copy(name = "song.mp3")
            metadata.value = null
        }
        compose.onNodeWithContentDescription("3 stars").assertIsNotEnabled()
        compose.runOnIdle { metadata.value = SongMetadata(canEdit = true) }
        compose.onNodeWithContentDescription("3 stars").assertIsEnabled()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithContentDescription("3 stars").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun queueRatingOpensOnlyOneDialogAndCanBeDismissed() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val next = track.copy(name = "next.mp3", rating = 4)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track, next))) { _, _ -> } }
        }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Rating: 4 out of 5").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Song rating").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options for next").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun libraryAndQueueLoadVersionedArtworkForActiveAndRepeatedTracks() {
        val image = java.io.ByteArrayOutputStream().use { output ->
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths.add(request.url.encodedPath + "?" + request.url.encodedQuery)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Artwork")
                .body(image.toResponseBody()).build()
        }.build()
        val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        val queued = Track("preview", "song.mp3", artworkUrl = "/api/jobs/preview/artwork/song.mp3?v=1")
        val refreshed = queued.copy(artworkUrl = "/api/jobs/preview/artwork/song.mp3?v=2")
        val showLibrary = mutableStateOf(true)
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = Account("https://music.example", User("shared", role = "shared"), Session("test", "2099-01-01T00:00:00Z"))
            ReflectionHelpers.setField(model, "api", ServerApi({ account.value }, {}, client))
            library.value = LibraryState().withTrackPage(TrackPage(files = listOf(refreshed)))
        }
        compose.setContent {
            MusicTheme {
                if (showLibrary.value) LibraryScreen(model, PlaybackState(connected = true), {}) { _, _ -> }
                else NowPlayingScreen(model, PlaybackState(track = queued, queue = listOf(queued, queued))) { _, _ -> }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Album artwork for song", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        compose.runOnIdle { showLibrary.value = false }
        compose.onNodeWithText("Queue").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Album artwork for song", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithContentDescription("Options for song").assertCountEquals(2)
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it == refreshed.artworkUrl })
        compose.runOnIdle {
            library.value = library.value.withTrackPage(TrackPage(files = listOf(refreshed.copy(artworkUrl = null))))
        }
        compose.onAllNodesWithContentDescription("Album artwork for song", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun queueRatingReflectsSavedAndClearedValuesOutsideTheCurrentLibraryPage() {
        val track = Track(jobId = "preview", name = "song.mp3", rating = 4)
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> } } }
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
                Box(Modifier.fillMaxSize().testTag("page")) { NowPlayingScreen(model, state.value) { _, _ -> } }
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabsAndLyricsUseSolidSurfacesAcrossThemesAndSkins() {
        val track = Track("preview", "song.mp3")
        val preferences = mutableStateOf(Preferences())
        val wave = mutableStateOf(true)
        val skin = mutableStateOf<AppSkin?>(null)
        var surface = Color.Unspecified
        compose.setContent {
            MusicTheme(preferences.value, waveAppearance = wave.value, skin = skin.value) {
                surface = MaterialTheme.colorScheme.surface
                SkinBackground(Modifier.fillMaxSize()) {
                    NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> }
                }
            }
        }
        compose.onNodeWithText("Lyrics").performClick()
        val appearances = listOf(Preferences() to true) +
            listOf("midnight", "royal-purple", "gold", "green", "pink", "black").flatMap { theme ->
                listOf("light", "dark").map { mode -> Preferences(theme = theme, mode = mode) to false }
            }
        appearances.forEach { (selected, blueWave) ->
            (listOf(null) + AppSkin.entries).forEach { option ->
                compose.runOnIdle {
                    preferences.value = selected
                    wave.value = blueWave
                    skin.value = option
                }
                assertSolidPlayerSurfaces { surface }
            }
        }
        listOf("Player", "Queue", "Lyrics").forEach { tab ->
            compose.onNodeWithText(tab).performClick().assertIsSelected()
            assertSolidPlayerSurfaces(lyricsVisible = tab == "Lyrics") { surface }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lyricsSurfaceCoversSynchronizedPlainAndFallbackStates() {
        val track = Track("preview", "song.mp3")
        val state = mutableStateOf(PlaybackState(track = track))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val error = ReflectionHelpers.getField<MutableState<String?>>(model, "metadataError\$delegate")
        var surface = Color.Unspecified
        compose.runOnIdle { model.viewModelScope.cancel() }
        compose.setContent {
            MusicTheme(Preferences(theme = "black", mode = "light"), waveAppearance = false,
                skin = AppSkin.CHERRY_BLOSSOM) {
                surface = MaterialTheme.colorScheme.surface
                SkinBackground(Modifier.fillMaxSize()) {
                    NowPlayingScreen(model, state.value) { _, _ -> }
                }
            }
        }
        compose.onNodeWithText("Lyrics").performClick()
        compose.onNodeWithText("Loading lyrics...").assertIsDisplayed()
        assertSolidPlayerSurfaces { surface }
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Timed lyrics")), uslt = "Plain lyrics")
        }
        compose.onNodeWithText("Timed lyrics").assertIsDisplayed().assertHasClickAction()
        assertSolidPlayerSurfaces { surface }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed().assertHasNoClickAction()
        assertSolidPlayerSurfaces { surface }
        compose.runOnIdle { metadata.value = SongMetadata() }
        compose.onNodeWithText("No lyrics available").assertIsDisplayed()
        assertSolidPlayerSurfaces { surface }
        compose.runOnIdle { metadata.value = null; error.value = "Lyrics and artwork are unavailable." }
        compose.onNodeWithText("Lyrics and artwork are unavailable.").assertIsDisplayed()
        assertSolidPlayerSurfaces { surface }
        compose.runOnIdle { error.value = null; state.value = state.value.copy(track = track.copy(mediaType = "video")) }
        compose.onNodeWithText("No lyrics available").assertIsDisplayed()
        assertSolidPlayerSurfaces { surface }
    }

    private fun assertSolidPlayerSurfaces(lyricsVisible: Boolean = true, surface: () -> Color) {
        val tabs = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .fetchSemanticsNode().boundsInRoot
        val transport = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val image = content.drawToBitmap()
            try {
                val points = listOf(tabs.left + 4f, tabs.center.x, tabs.right - 4f).map { x ->
                    Offset(x, tabs.top + 4f)
                } + if (lyricsVisible) listOf(
                    Offset(tabs.left + 4f, tabs.bottom + 4f),
                    Offset(tabs.left + 4f, (tabs.bottom + transport.top) / 2f),
                    Offset(tabs.center.x, transport.top - 4f),
                    Offset(tabs.right - 4f, transport.top - 4f)
                ) else emptyList()
                points.forEach { point ->
                    assertEquals("Expected an opaque theme surface at $point", surface().toArgb(),
                        image.getPixel(point.x.toInt(), point.y.toInt()))
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test fun fullscreenButtonOverlaysLyricsWithoutReducingContentHeight() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val text = "A long lyric line that wraps near the fullscreen button without being hidden underneath it"
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("lyrics-section")) {
                    Lyrics(model, PlaybackState(track = track), Modifier.fillMaxSize())
                }
            }
        }
        listOf(null, SongMetadata(), SongMetadata(uslt = text),
            SongMetadata(sylt = listOf(LyricLine(0.0, text)))).forEach { lyrics ->
            compose.runOnIdle { metadata.value = lyrics }
            val section = compose.onNodeWithTag("lyrics-section").fetchSemanticsNode().boundsInRoot
            val content = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            val fullscreen = compose.onNodeWithContentDescription("Expand lyrics to full screen")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(section, content)
            assertTrue(fullscreen.top in section.top..(section.top + fullscreen.height))
            assertTrue(fullscreen.right in (section.right - fullscreen.width)..section.right)
            if (lyrics?.uslt == text || lyrics?.sylt?.isNotEmpty() == true) {
                assertTrue(compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.right <= fullscreen.left)
            }
            compose.onNodeWithContentDescription("Edit lyrics").assertDoesNotExist()
        }
        compose.onNodeWithText(text).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").performClick()
        fullScreenText(text).assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen lyrics").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun lyricsExpandToFullScreenAndCollapseToTheSelectedSource() {
        val track = Track(jobId = "preview", name = "song.mp3", title = "Blue hour", artist = "Northbound")
        val state = PlaybackState(track = track, queue = listOf(track), position = 42_000, playing = true)
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Timed lyrics")), uslt = "Plain lyrics")
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state) { _, _ -> } } }
        compose.onNodeWithContentDescription("Expand lyrics to full screen").assertDoesNotExist()
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed().assertWidthIsAtLeast(360.dp).assertHeightIsAtLeast(700.dp)
        fullScreenText("Blue hour").assertIsDisplayed()
        fullScreenText("Northbound").assertIsDisplayed()
        fullScreenText("Timed lyrics").assertIsDisplayed().assertHasClickAction()
        compose.onNode(hasContentDescription("Pause") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch to USLT lyrics").performClick()
        fullScreenText("Plain lyrics").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription("Exit full screen lyrics").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.onNodeWithText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(42_000L, state.position)
            assertTrue(state.playing)
        }
    }

    @Test fun fullScreenLyricsRestoreAndBackReturnsToLyricsWithoutLeavingNowPlaying() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle { metadata.value = SongMetadata(uslt = "Plain lyrics") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").performClick()
        restoration.emulateSavedInstanceStateRestore()
        fullScreenText("Plain lyrics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to SYLT lyrics").assertDoesNotExist()
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
    }

    @Test fun fullScreenLyricsFollowPlaybackAndUpdateWhenTheTrackChangesOrIsCleared() {
        val track = Track(jobId = "preview", name = "song.mp3", title = "First song")
        val state = mutableStateOf(PlaybackState(track = track, position = 200_000))
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = List(80) { LyricLine(it * 10.0, "Line $it") }, uslt = "Plain lyrics")
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
        compose.onNodeWithText("SYLT Lyrics").performClick()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").performClick()
        fullScreenText("Line 20").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(position = 600_000) }
        fullScreenText("Line 60").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Switch to USLT lyrics").performClick()
        fullScreenText("Plain lyrics").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(track = track.copy(name = "next.mp3", title = "Next song"), position = 0)
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Next timed lyrics")), uslt = "Next plain lyrics")
        }
        fullScreenText("Next song").assertIsDisplayed()
        fullScreenText("Next timed lyrics").assertIsDisplayed()
        fullScreenText("Plain lyrics").assertDoesNotExist()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Choose a song from Library to start playing.").assertIsDisplayed()
    }

    @Test fun fullScreenLyricsPinchSharesTheSavedTextScaleWithTheInlineView() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle {
            metadata.value = SongMetadata(uslt = List(80) { "Plain lyric $it" }.joinToString("\n"))
        }
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        compose.onNodeWithText("USLT Lyrics").performClick()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").performClick()
        compose.onNode(isDialog()).performTouchInput {
            pinch(start0 = center - Offset(20f, 0f), end0 = center - Offset(60f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(60f, 0f), durationMillis = 600)
        }
        val scale = compose.runOnIdle { model.lyricsTextScale }
        assertTrue(scale > 1f)
        compose.onNode(isDialog()).performTouchInput { swipeUp(durationMillis = 1_000) }
        compose.onNodeWithContentDescription("Exit full screen lyrics").performClick()
        compose.onNodeWithText("USLT Lyrics").assertIsSelected()
        compose.runOnIdle { assertEquals(scale, model.lyricsTextScale, 0f) }
    }

    @Test fun fullScreenLyricsShowLoadingErrorAndEmptyStates() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val error = ReflectionHelpers.getField<MutableState<String?>>(model, "metadataError\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        compose.onNodeWithText("Lyrics").performClick()
        compose.onNodeWithContentDescription("Expand lyrics to full screen").performClick()
        fullScreenText("Loading lyrics...").assertIsDisplayed()
        compose.runOnIdle { error.value = "Lyrics request failed" }
        fullScreenText("Lyrics request failed").assertIsDisplayed()
        compose.runOnIdle { error.value = null; metadata.value = SongMetadata() }
        fullScreenText("No lyrics available").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen lyrics").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun fullScreenText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

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
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> } } }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithText("Lyrics Included").assertDoesNotExist()
        val status = compose.onNodeWithContentDescription("Lyrics Included", substring = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val rating = compose.onNodeWithContentDescription("Rating: 0 out of 5").getUnclippedBoundsInRoot()
        assertTrue(status.right <= rating.left)
        assertEquals((rating.top + rating.bottom) / 2, (status.top + status.bottom) / 2)
        compose.onNodeWithContentDescription("Lyrics Included", substring = true).performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close transcription details").performClick()
        compose.runOnIdle {
            library.value = library.value.copy(pendingTranscriptions = mapOf(track.key to Transcription(status = "sent")))
        }
        compose.onNodeWithContentDescription("Transcription request sent", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Transcription request sent").assertDoesNotExist()
    }

    @Test fun playerTabShowsTranscriptionStatusButHidesNotTranscribed() {
        val track = Track(jobId = "source", name = "song.mp3", artist = "Northbound")
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
        compose.onNodeWithText("Northbound").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not transcribed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transcription status unknown", substring = true).assertDoesNotExist()

        compose.runOnIdle {
            library.value = LibraryState(library = Library(jobs = listOf(Job(id = "source",
                transcriptions = mapOf(track.name to Transcription(status = "transcribed", lyricsIncluded = true))))))
        }
        compose.onNodeWithText("Lyrics Included").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Lyrics Included", substring = true).assertIsDisplayed()
    }

    @Test fun queueShowsRefreshedStatusInsteadOfItsOriginalTrackSnapshot() {
        val track = Track(jobId = "source", name = "song.mp3", transcription = Transcription(status = "sent"))
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> } } }
        compose.onNodeWithText("Queue").performClick()
        compose.onNodeWithContentDescription("Transcription request sent", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.withTrackPage(TrackPage(files = listOf(
                track.copy(transcription = Transcription(status = "transcribed", lyricsIncluded = true)))))
        }
        compose.onNodeWithText("Lyrics Included").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lyrics Included", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            library.value = library.value.withTrackPage(TrackPage(files = listOf(Track("other", "another.mp3"))))
                .withTranscriptions(Job(id = "source", transcriptions = mapOf(
                    track.name to Transcription(status = "failed"))), listOf(track))
        }
        compose.onNodeWithText("Transcription failed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transcription failed", substring = true).assertIsDisplayed()
    }

    @Test fun lyricsTabTracksDisplayedSourceWithoutChangingSelection() {
        val track = Track(jobId = "preview", name = "song.mp3")
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> } } }
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
        restoration.setContent { MusicTheme { NowPlayingScreen(model, state.value) { _, _ -> } } }
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
                    NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> }
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
        swipeBothWaysAt(compose.onNode(hasText("Blue hour") and !isSongHeading))
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
                    NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track))) { _, _ -> }
                }
            }
        }
        val artwork = compose.onNodeWithContentDescription("Album artwork unavailable")
        val original = artwork.fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Blue hour") and !isSongHeading).fetchSemanticsNode().boundsInRoot
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
                assertEquals(title, compose.onNode(hasText("Blue hour") and !isSongHeading).fetchSemanticsNode().boundsInRoot)
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
                Box(Modifier.fillMaxSize().testTag("page")) { NowPlayingScreen(model, state.value) { _, _ -> } }
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
