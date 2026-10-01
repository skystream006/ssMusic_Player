package com.ssytdlp.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.drawToBitmap
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.Preferences
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist
import com.ssytdlp.app.core.SongMetadata
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun waveAppearanceUsesBlackAndCyanEvenWithLightServerPreferences() {
        lateinit var colors: ColorScheme
        compose.setContent {
            MusicTheme(Preferences(theme = "light", mode = "light")) {
                colors = MaterialTheme.colorScheme
            }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF030508), colors.background)
            assertEquals(Color(0xFF68DEFF), colors.primary)
            assertEquals(Color(0xFFEDF3FC), colors.onSurface)
        }
    }

    @Test fun serverAppearanceStillHonorsSavedLightMode() {
        lateinit var colors: ColorScheme
        compose.setContent {
            MusicTheme(Preferences(theme = "light", mode = "light"), waveAppearance = false) {
                colors = MaterialTheme.colorScheme
            }
        }
        compose.runOnIdle { assertEquals(Color(0xFFF6F8F6), colors.background) }
    }

    @Test fun loginWaveArtworkAndPasskeyControlsRenderOnPhone() {
        var signIn = false
        compose.setContent {
            MusicTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LoginContent("https://music.example.com", false, false, onSignIn = { signIn = true }, onCancel = {}, onRegister = {})
                }
            }
        }
        compose.onNodeWithText("Sign in with passkey").assertIsDisplayed()
        compose.onNodeWithText("music.example.com").assertIsDisplayed()
        savePreview("login-phone")
        compose.onNodeWithText("Sign in with passkey").performClick()
        compose.runOnIdle { assertTrue(signIn) }
    }

    @Test fun updatesAreAccessibleBeforeEnteringAServer() {
        var settingsOpened = false
        var continued = false
        compose.setContent {
            MusicTheme {
                ServerSetupContent("", null, false, onValueChange = {},
                    onContinue = { continued = true }, onAppSettings = { settingsOpened = true })
            }
        }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Updates and diagnostics").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(settingsOpened)
            assertFalse(continued)
        }
    }

    @Test fun libraryTopBarLeavesRoomForTracksAndStaysVisibleWhileScrolling() {
        showLibraryPreview()
        compose.onNodeWithText("All Music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play this page").assertIsDisplayed()
        compose.onNodeWithText("Quiet signal").assertIsDisplayed()
        compose.onNodeWithText("LIBRARY").assertDoesNotExist()
        compose.onNodeWithText("ssMusic").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Browse library").assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val browse = compose.onNodeWithContentDescription("Browse library").getUnclippedBoundsInRoot()
        val play = compose.onNodeWithContentDescription("Play this page").getUnclippedBoundsInRoot()
        val search = compose.onNode(hasSetTextAction()).assertIsDisplayed().getUnclippedBoundsInRoot()
        val tracks = compose.onNodeWithText("TRACKS").getUnclippedBoundsInRoot()
        assertTrue(browse.right <= play.left)
        assertTrue(browse.bottom <= search.top)
        assertTrue(play.bottom <= search.top)
        assertTrue(search.bottom <= tracks.top)
        assertTrue("Tracks should start directly below the compact top bar", tracks.top <= 176.dp)
        savePreview("library-phone")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        compose.onNodeWithText("Slow motion").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Play this page").assertIsDisplayed()
        assertEquals(browse, compose.onNodeWithContentDescription("Browse library").assertIsDisplayed().getUnclippedBoundsInRoot())
    }

    @Test fun libraryTopBarControlsKeepTheirActionsAndSearchCanBeCleared() {
        val state = mutableStateOf(LibraryState(tracks = TrackPage(files = previewTracks(), total = 8)))
        var browsed = false
        var selected = -1
        var refreshed = false
        var settingsOpened = false
        compose.setContent {
            MusicTheme {
                LibraryTopBar(state.value, PlaybackState(connected = true), true,
                    onBrowse = { browsed = true }, onSearch = { state.value = state.value.copy(search = it) },
                    onPlay = { selected = it }, onRefresh = { refreshed = true }, onSettings = { settingsOpened = true })
            }
        }
        compose.onNodeWithContentDescription("Browse library").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Play this page").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Quiet")
        compose.runOnIdle { assertEquals("Quiet", state.value.search) }
        compose.onNodeWithContentDescription("Clear search").assertIsDisplayed().performClick()
        compose.onNodeWithText("Search your music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("", state.value.search)
            assertTrue(browsed)
            assertEquals(0, selected)
            assertTrue(refreshed)
            assertTrue(settingsOpened)
        }
    }

    @Test fun libraryTopBarDisablesPlayForEmptyLoadingOrDisconnectedPages() {
        val state = mutableStateOf(LibraryState())
        val playback = mutableStateOf(PlaybackState(connected = true))
        var plays = 0
        var settingsOpened = false
        compose.setContent {
            MusicTheme {
                LibraryTopBar(state.value, playback.value, !state.value.loading, {}, {}, { plays++ }, {},
                    onSettings = { settingsOpened = true })
            }
        }
        val play = compose.onNodeWithContentDescription("Play this page")
        play.assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(tracks = TrackPage(files = previewTracks(), total = 8))
            playback.value = playback.value.copy(connected = false)
        }
        play.assertIsNotEnabled()
        compose.runOnIdle { playback.value = playback.value.copy(connected = true) }
        play.assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        play.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Settings").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Browse library").assertIsEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, plays); assertTrue(settingsOpened) }
    }

    @Test fun libraryTopBarShowsSelectedPlaylistFolderAndFallbackTitles() {
        val state = mutableStateOf(LibraryState(library = Library(
            entries = listOf(LibraryEntry("playlist", "playlist", "Playlist entry"),
                LibraryEntry("folder", "folder", "My folder")),
            playlists = listOf(LibraryPlaylist("playlist", "Night Sessions")))))
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                LibraryTopBar(state.value, PlaybackState(), true, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("All Music").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedId = "playlist") }
        compose.onNodeWithText("Night Sessions").assertIsDisplayed()
        compose.onNodeWithText("Playlist entry").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(library = state.value.library.copy(
            playlists = listOf(LibraryPlaylist("playlist", "")))) }
        compose.onNodeWithText("Playlist entry").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedId = "folder") }
        compose.onNodeWithText("My folder").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectedId = "removed") }
        compose.onNodeWithText("All Music").assertIsDisplayed()
    }

    @Test fun navigationShowsLibraryAndNowPlayingWithoutJobsOrSettings() {
        var selected = -1
        compose.setContent { MusicTheme { MusicNavigation(0) { selected = it } } }
        compose.onNodeWithText("Downloads").assertDoesNotExist()
        compose.onNodeWithText("Jobs").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Library").assertIsSelected()
        compose.onNodeWithText("Now Playing").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Library").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun nowPlayingNavigationCanBeSelected() {
        compose.setContent { MusicTheme { MusicNavigation(1) {} } }
        compose.onNodeWithText("Now Playing").assertIsSelected()
        compose.onNodeWithText("Library").assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun settingsIsToTheRightOfRefreshAndWorksWhileRefreshIsDisabled() {
        var settingsOpened = false
        var refreshed = false
        var backed = false
        compose.setContent {
            MusicTheme {
                MusicTopBar("Alex", false, onRefresh = { refreshed = true },
                    onSettings = { settingsOpened = true }, onBack = { backed = true })
            }
        }
        val refresh = compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().assertIsNotEnabled()
        val settings = compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val refreshBounds = refresh.getUnclippedBoundsInRoot()
        val settingsBounds = settings.getUnclippedBoundsInRoot()
        assertTrue(settingsBounds.left >= refreshBounds.right)
        assertTrue(settingsBounds.right <= 320.dp)
        settings.performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertTrue(settingsOpened)
            assertTrue(backed)
            assertFalse(refreshed)
        }
    }

    @Test fun settingsJobsEntryOpensJobs() {
        var jobsOpened = false
        compose.setContent { MusicTheme { JobsSetting { jobsOpened = true } } }
        compose.onNodeWithText("Jobs").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(jobsOpened) }
    }

    @Test fun songSubtitlesDoNotDisplayPlaylistTitlesWhenArtistIsMissing() {
        val track = Track("preview", "song.mp3", title = "Blue hour", playlistTitle = "Night Sessions")
        val playback = PlaybackState(track = track, queue = listOf(track))
        compose.setContent {
            MusicTheme {
                Column {
                    TrackRow(track, onClick = {})
                    MiniPlayer(playback, null, {}, {}, {}, {})
                    PlayerArtwork(playback, null)
                }
            }
        }
        compose.onAllNodesWithText("Blue hour").assertCountEquals(3)
        compose.onAllNodesWithText("Unknown artist").assertCountEquals(3)
        compose.onNodeWithText("Night Sessions").assertDoesNotExist()
    }

    @Test fun nowPlayingSwipesSkipExactlyOneSongInEachDirection() {
        var previous = 0
        var next = 0
        showSwipePlayer(previous = { previous++ }, next = { next++ })
        compose.onNodeWithTag("swipe-artwork").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, next); assertEquals(0, previous) }
        compose.onNodeWithTag("swipe-artwork").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, next); assertEquals(1, previous) }
    }

    @Test fun nowPlayingIgnoresShortCancelledAndVerticalSwipes() {
        var skips = 0
        showSwipePlayer(previous = { skips++ }, next = { skips++ })
        val artwork = compose.onNodeWithTag("swipe-artwork")
        artwork.performTouchInput { swipe(center, center + Offset(30f, 0f)) }
        artwork.performTouchInput {
            down(centerRight)
            moveTo(centerLeft, delayMillis = 200)
            cancel()
        }
        artwork.performTouchInput { swipeUp() }
        artwork.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, skips) }
        artwork.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, skips) }
    }

    @Test fun seekingDoesNotSkipSongs() {
        var skips = 0
        var sought: Long? = null
        showSwipePlayer(previous = { skips++ }, next = { skips++ }, onSeek = { sought = it })
        compose.onNodeWithContentDescription("Playback position").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(0.5f))
        }
        compose.runOnIdle { assertNotNull(sought); assertEquals(0, skips) }
    }

    @Test fun libraryMiniPlayerSeeksByTouchAndAccessibilityWithoutTriggeringOtherControls() {
        val seeks = mutableListOf<Long>()
        var expanded = false
        var toggled = false
        var skipped = false
        val playback = previewPlayback()
        compose.setContent {
            MusicTheme {
                Scaffold(bottomBar = {
                    Column {
                        MiniPlayer(playback, null, { expanded = true }, { toggled = true },
                            { skipped = true }, seeks::add)
                        MusicNavigation(0) {}
                    }
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                        LibraryContent(LibraryState(), playback, {}, {}) { _, _ -> }
                    }
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        slider.performTouchInput { swipe(center, Offset(width * 0.8f, centerY)) }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() in 150_000..210_000)
        }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(117_000f)) }
        compose.runOnIdle {
            assertEquals(117_000L, seeks.last())
            assertFalse(expanded)
            assertFalse(toggled)
            assertFalse(skipped)
        }
        compose.onNodeWithText("Library").assertIsSelected()
        compose.onNodeWithText("Blue hour").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next track").performClick()
        compose.runOnIdle {
            assertTrue(expanded)
            assertTrue(toggled)
            assertTrue(skipped)
        }
    }

    @Test fun miniPlayerDisablesSeekingUntilDurationIsKnownAndHidesWhenEmpty() {
        val state = mutableStateOf(previewPlayback().copy(duration = 0))
        var sought = false
        compose.setContent {
            MusicTheme { MiniPlayer(state.value, null, {}, {}, {}, { sought = true }) }
        }
        compose.onNodeWithContentDescription("Playback position").assertIsNotEnabled()
            .performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertFalse(sought)
            state.value = state.value.copy(duration = 234_000)
        }
        compose.onNodeWithContentDescription("Playback position").assertIsEnabled()
        compose.runOnIdle { state.value = PlaybackState() }
        compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
    }

    @Test fun miniPlayerKeepsDraggedPositionWhilePlaybackUpdatesAndSeeksOnRelease() {
        val state = mutableStateOf(previewPlayback())
        val seeks = mutableListOf<Long>()
        compose.setContent { MusicTheme { MiniPlayer(state.value, null, {}, {}, {}, seeks::add) } }
        val slider = compose.onNodeWithContentDescription("Playback position")
        slider.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 300)
        }
        val dragged = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            state.value = state.value.copy(position = 5_000)
        }
        assertEquals(dragged, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(dragged.toLong()), seeks) }
    }

    @Test fun singleSongQueueDoesNotSwipeNext() {
        var previous = 0
        var next = 0
        val playback = previewPlayback()
        showSwipePlayer(playback.copy(queue = listOf(playback.track!!)), { previous++ }, { next++ })
        compose.onNodeWithTag("swipe-artwork").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("swipe-artwork").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, next); assertEquals(1, previous) }
    }

    @Test fun emptyPlayerDoesNotHandleTrackSwipes() {
        var skips = 0
        showSwipePlayer(PlaybackState(), { skips++ }, { skips++ })
        compose.onNodeWithTag("swipe-player").performTouchInput { swipeLeft(); swipeRight() }
        compose.runOnIdle { assertEquals(0, skips) }
    }

    @Test fun loginOffersUpdatesAndDiagnosticsWithoutSigningIn() {
        var opened = false
        compose.setContent {
            MusicTheme {
                LoginContent("https://music.example.com", false, false, onSignIn = {}, onCancel = {},
                    onRegister = {}, onAppSettings = { opened = true })
            }
        }
        compose.onNodeWithText("Updates and diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    @Config(qualifiers = "w800dp-h1100dp")
    fun libraryTopBarRendersOnTablet() {
        showLibraryPreview()
        compose.onNodeWithContentDescription("Browse library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        savePreview("library-tablet")
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun libraryControlsFitOnNarrowScreensWithLargeText() {
        val title = "A very long playlist title that should not crowd the controls"
        showLibraryPreview(1.5f, title)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Play this page").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val browse = compose.onNodeWithContentDescription("Browse library").assertIsDisplayed().getUnclippedBoundsInRoot()
        val play = compose.onNodeWithContentDescription("Play this page").getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription("Settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        val pause = compose.onNodeWithContentDescription("Pause").getUnclippedBoundsInRoot()
        assertTrue(browse.left >= 0.dp && browse.right <= play.left)
        assertTrue(play.right <= refresh.left && refresh.right <= settings.left)
        assertTrue(settings.right <= 320.dp)
        assertTrue(play.left >= 0.dp && play.right <= 320.dp)
        assertTrue(pause.left >= 0.dp && pause.right <= 320.dp)
        savePreview("library-large-text")
    }

    @Test fun playerArtworkAndTransportRenderOnPhone() {
        var paused = false
        val playback = previewPlayback()
        compose.setContent {
            MusicTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        ToolButton(Icons.Rounded.Close, "Close player") {}
                    }
                    PlayerArtwork(playback, SongMetadata(title = "Blue hour", artist = "Northbound", album = "Night Sessions"), Modifier.weight(1f))
                    PlayerTransport(playback, {}, {}, { paused = true }, {}, {}, {})
                }
                }
            }
        }
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithContentDescription("Album artwork unavailable").assertIsDisplayed()
        savePreview("player-phone")
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { assertTrue(paused) }
    }

    @Test fun songRatingsAppearBesideTheirMenusWithoutChangingRowActions() {
        val track = Track("job", "song.mp3", title = "Rated song", rating = 4)
        val library = LibraryState(tracks = TrackPage(files = listOf(track), total = 1))
        var selected = -1
        var menu = false
        var rated: Track? = null
        compose.setContent {
            MusicTheme {
                LibraryContent(library, PlaybackState(connected = true), { selected = it }, {},
                    onRating = { rated = it }) { song, _ ->
                    ToolButton(Icons.Rounded.MoreVert, "Options for ${song.displayTitle}") { menu = true }
                }
            }
        }
        val rating = compose.onNodeWithContentDescription("Rating: 4 out of 5", useUnmergedTree = true)
            .assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("4/5", useUnmergedTree = true).assertIsDisplayed()
        val options = compose.onNodeWithContentDescription("Options for Rated song").assertIsDisplayed()
        val ratingBounds = rating.getUnclippedBoundsInRoot()
        val menuBounds = options.getUnclippedBoundsInRoot()
        assertTrue(ratingBounds.right <= menuBounds.left)
        assertEquals((menuBounds.top.value + menuBounds.bottom.value) / 2f, (ratingBounds.top.value + ratingBounds.bottom.value) / 2f, 1f)
        rating.performClick()
        compose.runOnIdle { assertEquals(track, rated); assertFalse(menu); assertEquals(-1, selected) }
        options.performClick()
        compose.runOnIdle { assertTrue(menu); assertEquals(-1, selected) }
        compose.onNodeWithText("Rated song").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun songRatingRemainsVisibleAndClickableWhenUnratedOrCleared() {
        val track = mutableStateOf(Track("job", "song.mp3", title = "Song"))
        var ratingClicks = 0
        compose.setContent {
            MusicTheme {
                TrackRow(track.value, onRatingClick = { ratingClicks++ }, onClick = {}) {
                    ToolButton(Icons.Rounded.MoreVert, "Song options") {}
                }
            }
        }
        val ratings = hasContentDescription("Rating:", substring = true)
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().performClick()
        compose.onNodeWithText("0/5", useUnmergedTree = true).assertIsDisplayed()
        for (rating in 1..5) {
            compose.runOnIdle { track.value = track.value.copy(rating = rating) }
            compose.onAllNodes(ratings, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithContentDescription("Rating: $rating out of 5", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("$rating/5", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.runOnIdle { track.value = track.value.copy(rating = 0) }
        compose.onAllNodes(ratings, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription("Rating: 0 out of 5").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, ratingClicks) }
        compose.onNodeWithText("Song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Song options").assertIsDisplayed()
    }

    @Test fun ratingDialogSetsAndClearsRatingWithoutChangingOtherMetadata() {
        val original = SongMetadata(title = "Song", artist = "Artist", album = "Album", genre = "Pop",
            year = "2026", uslt = "Lyrics")
        val value = mutableStateOf(original)
        val open = mutableStateOf(true)
        var saved: SongMetadata? = null
        compose.setContent {
            MusicTheme {
                if (open.value) MetadataDialog(value.value, { value.value = it }, ratingOnly = true,
                    dismiss = { open.value = false }) { saved = it; open.value = false }
            }
        }
        compose.onNodeWithText("Rate song").assertIsDisplayed()
        compose.onNodeWithText("Title").assertDoesNotExist()
        compose.onNodeWithText("Rating: 0 / 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 stars").performClick().assertIsOn()
        compose.onNodeWithText("Rating: 4 / 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 stars").performClick().assertIsOff()
        compose.onNodeWithText("Rating: 0 / 5").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(original, saved)
            open.value = true
        }
        compose.onNodeWithContentDescription("5 stars").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(original.copy(rating = 5), saved) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun ratingDialogCancelDoesNotSaveChanges() {
        val value = mutableStateOf(SongMetadata(rating = 3))
        val open = mutableStateOf(true)
        var saved = false
        compose.setContent {
            MusicTheme {
                if (open.value) MetadataDialog(value.value, { value.value = it }, ratingOnly = true,
                    dismiss = { open.value = false }) { saved = true }
            }
        }
        compose.onNodeWithText("Rating: 3 / 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 stars").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertFalse(saved) }
    }

    @Test fun ratingDialogCannotSaveWhileLoadingFailedBusyOrReadOnly() {
        val value = mutableStateOf<SongMetadata?>(null)
        val error = mutableStateOf<String?>(null)
        val busy = mutableStateOf(false)
        val canEdit = mutableStateOf(true)
        compose.setContent {
            MusicTheme {
                MetadataDialog(value.value, { value.value = it }, error = error.value, busy = busy.value,
                    ratingOnly = true, canEdit = canEdit.value, dismiss = {}) {}
            }
        }
        compose.onNodeWithText("Loading...").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { error.value = "Could not load song information." }
        compose.onNodeWithText("Could not load song information.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { value.value = SongMetadata(rating = 2); busy.value = true }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("3 stars").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; canEdit.value = false }
        compose.onNodeWithText("Rating changes require permission to edit an MP3 file.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("3 stars").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.runOnIdle { canEdit.value = true }
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun longTrackNamesLeaveTheirRatingAndMenuAccessibleWithLargeText() {
        val title = "A long song title with enough words to wrap across several lines on a phone"
        var selected = false
        var menu = false
        var rated = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MusicTheme {
                    Box(Modifier.width(320.dp)) {
                        TrackRow(Track("job", "song.mp3", title, "An artist with a long name", rating = 5),
                            onRatingClick = { rated = true }, onClick = { selected = true }) {
                            ToolButton(Icons.Rounded.MoreVert, "Song options") { menu = true }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithContentDescription("Song options").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(menu) }
        val menuBounds = compose.onNodeWithContentDescription("Song options").getUnclippedBoundsInRoot()
        val ratingBounds = compose.onNodeWithContentDescription("Rating: 5 out of 5", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(ratingBounds.right <= menuBounds.left)
        assertTrue(ratingBounds.left >= compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot().right)
        assertTrue(menuBounds.right <= 320.dp)
        compose.onNodeWithContentDescription("Rating: 5 out of 5").performClick()
        compose.runOnIdle { assertTrue(rated) }
    }

    @Test fun playlistChoiceUsesStableIdentifiers() {
        var selected: String? = null
        compose.setContent {
            MusicTheme { DestinationDialog("Move to playlist", listOf("first-id" to "Morning", "second-id" to "Evening"), {}) { selected = it } }
        }
        compose.onNodeWithText("Evening").performClick()
        compose.runOnIdle { assertEquals("second-id", selected) }
    }

    @Test fun manifestDeclaresMediaServiceAndNotificationPermissionsWithoutBroadStorageAccess() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.containsAll(setOf(Manifest.permission.INTERNET, Manifest.permission.WAKE_LOCK,
            Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK, Manifest.permission.POST_NOTIFICATIONS)))
        assertFalse(permissions.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertFalse(permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertFalse(permissions.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
    }

    private fun previewTracks() = listOf(
        "Blue hour" to "Northbound", "Quiet signal" to "Lena Sol", "Tidal" to "Aster & Vale",
        "After the rain" to "The Meridian", "Night drive" to "Northbound", "Open water" to "Lena Sol",
        "Still here" to "Aster & Vale", "Slow motion" to "The Meridian"
    ).mapIndexed { index, (title, artist) -> Track("preview", "track-$index.mp3", title, artist, playlistTitle = "Night Sessions") }

    private fun previewPlayback(): PlaybackState {
        val tracks = previewTracks()
        return PlaybackState(connected = true, track = tracks.first(), queue = tracks, playing = true, position = 86_000, duration = 234_000)
    }

    private fun showSwipePlayer(state: PlaybackState = previewPlayback(), previous: () -> Unit,
        next: () -> Unit, onSeek: (Long) -> Unit = {}) {
        compose.setContent {
            MusicTheme {
                Column(Modifier.fillMaxSize().testTag("swipe-player").playerTrackSwipes(
                    enabled = state.track != null, nextEnabled = state.queue.size > 1,
                    previous = previous, next = next
                )) {
                    PlayerArtwork(state, null, Modifier.weight(1f).testTag("swipe-artwork"))
                    PlayerTransport(state, onSeek, previous, {}, next, {}, {})
                }
            }
        }
    }

    private fun showLibraryPreview(fontScale: Float = 1f, playlistTitle: String? = null) {
        val playback = previewPlayback()
        val library = LibraryState(library = Library(songCount = 248,
            entries = playlistTitle?.let { listOf(LibraryEntry("playlist", "playlist", it)) }.orEmpty()),
            selectedId = playlistTitle?.let { "playlist" }, tracks = TrackPage(files = playback.queue, total = 248, totalPages = 5))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MusicTheme {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                        LibraryTopBar(library, playback, true, {}, {}, {}, {}, {})
                    }, bottomBar = {
                        Column {
                            MiniPlayer(playback, null, {}, {}, {}, {})
                            MusicNavigation(0) {}
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            LibraryContent(library, playback, {}, {}) { track, _ ->
                                ToolButton(Icons.Rounded.MoreVert, "Options for ${track.displayTitle}") {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun savePreview(name: String) {
        val image = compose.runOnIdle { compose.activity.window.decorView.drawToBitmap() }
        val directory = File("build/outputs/ui-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var bluePixels = 0
        var textPixels = 0
        for (vertical in 0 until image.height step 4) {
            for (horizontal in 0 until image.width step 4) {
                val pixel = image.getPixel(horizontal, vertical)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                if (blue > 80 && blue > red + 30 && green > 40) bluePixels++
                if (red > 190 && green > 190 && blue > 190) textPixels++
            }
        }
        assertTrue("The ribbon artwork and cyan controls must be visible", bluePixels > 100)
        assertTrue("The UI must contain readable light text", textPixels > 30)
    }
}