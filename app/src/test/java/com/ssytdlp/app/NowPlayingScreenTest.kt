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
import com.ssytdlp.app.core.ApiJson
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    @Test fun noVocalsOnlyHidesOtherOptionsAndRestoresTheirDraftWhenUnchecked() {
        var submitted: TranscriptionOptions? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MusicTheme { TranscribeDialog({}, { submitted = it }) }
        }
        compose.onNodeWithText("Generate NoVocals Only").assertIsOff()
        compose.onNodeWithText("Add lyrics").performClick()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNodeWithText("Generate NoVocals Only").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Generate NoVocals Only").assertIsOn()
        assertOrdinaryTranscriptionOptionsHidden()
        compose.onNodeWithText("Generate").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("""{"NoVocalsOnly":true}""", submitted!!.toRequestBody().toString())
        }
        compose.onNodeWithText("Generate NoVocals Only").performClick().assertIsOff()
        compose.onNodeWithText("Add lyrics").assertIsOn()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Restored draft")
        compose.onNodeWithText("Transcribe").assertIsEnabled()
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
        compose.onNodeWithContentDescription("Transcription locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unlock transcription").assertDoesNotExist()
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
        compose.onNodeWithText("Generate").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun unlockedSongKeepsSeparateLockActionWithoutSubmittingTranscription() {
        var submissions = 0
        var locks = 0
        compose.setContent {
            MusicTheme { TranscribeDialog({}, { submissions++ }, available = false, lock = { locks++ }) }
        }
        compose.onNodeWithText("Generate NoVocals Only").performClick()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithText("Generate NoVocals Only").assertDoesNotExist()
        compose.onNodeWithText("Lock transcription").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, locks); assertEquals(0, submissions) }
    }

    @Test fun lockedSongMenuKeepsTranscriptionButOnlyOpensNoVocalsDialogForPermittedUsers() {
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
        compose.onNodeWithContentDescription("Unlock transcription").assertDoesNotExist()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "owner", role = "shared")) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for song").performClick()
        compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
        compose.runOnIdle { account.value = account.value!!.copy(user = User(id = "unrelated")) }
        compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
    }

    @Test fun reorderTargetsStayWithinVisibleGroupsAndPreserveRawServerOrder() {
        val files = listOf(
            Track(name = "[NoVocals]/first.mp3"), Track(name = "first.mp3"),
            Track(name = "[novocals]/middle.mp3"), Track(name = "last.mp3"),
            Track(name = "[NOVOCALS]/last.mp3"))
        assertEquals(null to files[2], trackReorderNeighbors(files, 0))
        assertEquals(null to files[3], trackReorderNeighbors(files, 1))
        assertEquals(files[0] to files[4], trackReorderNeighbors(files, 2))
        assertEquals(files[1] to null, trackReorderNeighbors(files, 3))
        assertEquals(files[2] to null, trackReorderNeighbors(files, 4))
        assertEquals(null to null, trackReorderNeighbors(files, -1))
        assertEquals(null to null, trackReorderNeighbors(files, files.size))
        assertEquals(listOf("[NoVocals]/first.mp3", "first.mp3", "[novocals]/middle.mp3",
            "last.mp3", "[NOVOCALS]/last.mp3"), files.map { it.name })
    }

    @Test fun reorderMenuHidesActionsAtEachGroupBoundaryInsteadOfCrossingHiddenSongs() {
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
        listOf(0, 1).forEach { index ->
            compose.runOnIdle { selected.value = index }
            compose.onNodeWithText("Move up").assertDoesNotExist()
            compose.onNodeWithText("Move down").assertIsDisplayed()
        }
        listOf(2, 3).forEach { index ->
            compose.runOnIdle { selected.value = index }
            compose.onNodeWithText("Move up").assertIsDisplayed()
            compose.onNodeWithText("Move down").assertDoesNotExist()
        }
    }

    private fun assertOrdinaryTranscriptionOptionsHidden() {
        listOf("Auto-detect", "Multilingual", "Create no-vocals version [Karaoke version]",
            "Viet Lyrics Fallback", "Add lyrics", "Lyrics mode", "Transcribe").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
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
        }
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
