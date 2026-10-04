package com.ssytdlp.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.drawToBitmap
import androidx.media3.common.Player
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.Preferences
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist
import com.ssytdlp.app.core.SongMetadata
import java.io.File
import kotlin.math.roundToInt
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
            MusicTheme(Preferences(theme = "green", mode = "light"), waveAppearance = false) {
                colors = MaterialTheme.colorScheme
            }
        }
        compose.runOnIdle { assertEquals(Color(0xFFF6F8F6), colors.background) }
    }

    @Test fun legacyPorcelainUsesGreenPaletteWithoutChangingMode() {
        val preferences = mutableStateOf(Preferences(theme = "light"))
        lateinit var legacy: ColorScheme
        lateinit var green: ColorScheme
        compose.setContent {
            MusicTheme(preferences.value, waveAppearance = false) { legacy = MaterialTheme.colorScheme }
            MusicTheme(preferences.value.copy(theme = "green"), waveAppearance = false) { green = MaterialTheme.colorScheme }
        }
        listOf(null, "light", "dark").forEach { mode ->
            compose.runOnIdle { preferences.value = preferences.value.copy(mode = mode) }
            compose.runOnIdle {
                assertEquals(green.primary, legacy.primary)
                assertEquals(green.secondary, legacy.secondary)
                assertEquals(green.background, legacy.background)
                assertEquals(green.onBackground, legacy.onBackground)
                assertEquals(green.surface, legacy.surface)
                assertEquals(green.onSurface, legacy.onSurface)
                assertEquals(Color(0xFF227452), legacy.primary)
                assertEquals(if (mode == "dark") Color(0xFF141817) else Color(0xFFF6F8F6), legacy.background)
            }
        }
    }

    @Test fun serverBlackPaletteMatchesCharcoalAndGoldInBothModes() {
        val preferences = mutableStateOf(Preferences(theme = "black"))
        lateinit var colors: ColorScheme
        compose.setContent {
            MusicTheme(preferences.value, waveAppearance = false) { colors = MaterialTheme.colorScheme }
        }
        fun assertPalette(dark: Boolean) {
            compose.runOnIdle {
                val accent = if (dark) Color(0xFFF5D442) else Color(0xFF806000)
                val ink = if (dark) Color(0xFFF0F0EE) else Color(0xFF202124)
                assertEquals(accent, colors.primary)
                assertEquals(accent, colors.secondary)
                assertEquals(accent, colors.tertiary)
                assertEquals(accent, colors.surfaceTint)
                assertEquals(if (dark) Color(0xFF141414) else Color.White, colors.onPrimary)
                assertEquals(if (dark) Color(0xFF0E0E0E) else Color(0xFFF5F5F5), colors.background)
                assertEquals(if (dark) Color(0xFF181818) else Color.White, colors.surface)
                assertEquals(if (dark) Color(0xFF141414) else Color(0xFFFAFAFA), colors.surfaceContainerLow)
                assertEquals(if (dark) Color(0xFF262626) else Color(0xFFEAEAEA), colors.surfaceContainerHigh)
                assertEquals(ink, colors.onBackground)
                assertEquals(ink, colors.onSurface)
                assertEquals(if (dark) Color(0xFFADADAD) else Color(0xFF686868), colors.onSurfaceVariant)
                assertEquals(if (dark) Color(0xFF373737) else Color(0xFFD8D8D8), colors.outlineVariant)
                assertEquals(if (dark) Color(0xFFFF9AAB) else Color(0xFFB03250), colors.error)
            }
        }
        assertPalette(dark = true)
        compose.runOnIdle { preferences.value = preferences.value.copy(mode = "light") }
        assertPalette(dark = false)
        compose.runOnIdle { preferences.value = preferences.value.copy(mode = "dark") }
        assertPalette(dark = true)
    }

    @Test fun blackServerPreferencesDoNotOverrideBlueWaveAppearance() {
        lateinit var colors: ColorScheme
        compose.setContent {
            MusicTheme(Preferences(theme = "black", mode = "light")) { colors = MaterialTheme.colorScheme }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF030508), colors.background)
            assertEquals(Color(0xFF68DEFF), colors.primary)
        }
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
        compose.onNodeWithContentDescription("Play this page").assertDoesNotExist()
        compose.onNodeWithText("Quiet signal").assertIsDisplayed()
        compose.onNodeWithText("LIBRARY").assertDoesNotExist()
        compose.onNodeWithText("ssMusic").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Browse library").assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val browse = compose.onNodeWithContentDescription("Browse library").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription("Search").assertIsDisplayed().getUnclippedBoundsInRoot()
        val tracks = compose.onNodeWithText("TRACKS").getUnclippedBoundsInRoot()
        assertTrue(browse.right <= search.left)
        assertTrue(search.bottom <= tracks.top)
        assertTrue("Tracks should start directly below the compact top bar", tracks.top <= 112.dp)
        savePreview("library-phone")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        compose.onNodeWithText("Slow motion").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        assertEquals(browse, compose.onNodeWithContentDescription("Browse library").assertIsDisplayed().getUnclippedBoundsInRoot())
    }

    @Test fun libraryTopBarControlsKeepTheirActionsAndSearchCanBeCleared() {
        val state = mutableStateOf(LibraryState(tracks = TrackPage(files = previewTracks(), total = 8)))
        var browsed = false
        var refreshed = false
        var settingsOpened = false
        compose.setContent {
            MusicTheme {
                LibraryTopBar(state.value, true,
                    onBrowse = { browsed = true }, onSearch = { state.value = state.value.copy(search = it) },
                    onRefresh = { refreshed = true }, onSettings = { settingsOpened = true })
            }
        }
        compose.onNodeWithContentDescription("Browse library").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Play this page").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Quiet")
        compose.runOnIdle { assertEquals("Quiet", state.value.search) }
        compose.onNodeWithContentDescription("Clear search").assertIsDisplayed().performClick()
        compose.onNodeWithText("Search your music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Hidden filter")
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("", state.value.search)
            assertTrue(browsed)
            assertTrue(refreshed)
            assertTrue(settingsOpened)
        }
    }

    @Test fun libraryTopBarKeepsSearchAvailableWhileLoadingAnEmptyPage() {
        val state = mutableStateOf(LibraryState())
        var settingsOpened = false
        compose.setContent {
            MusicTheme {
                LibraryTopBar(state.value, !state.value.loading, {}, {}, {},
                    onSettings = { settingsOpened = true })
            }
        }
        compose.onNodeWithContentDescription("Play this page").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Settings").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Browse library").assertIsEnabled()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.runOnIdle { assertTrue(settingsOpened) }
    }

    @Test fun libraryTopBarShowsSelectedPlaylistFolderAndFallbackTitles() {
        val state = mutableStateOf(LibraryState(library = Library(
            entries = listOf(LibraryEntry("playlist", "playlist", "Playlist entry"),
                LibraryEntry("folder", "folder", "My folder")),
            playlists = listOf(LibraryPlaylist("playlist", "Night Sessions")))))
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                LibraryTopBar(state.value, true, {}, {}, {}, {})
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

    @Test
    @Config(qualifiers = "w780dp-h360dp")
    fun playlistSelectorWrapsItsTextInsteadOfSpanningTheTitleArea() {
        val state = mutableStateOf(LibraryState())
        compose.setContent {
            MusicTheme { LibraryTopBar(state.value, true, {}, {}, {}, {}) }
        }
        val selector = compose.onNodeWithContentDescription("Browse library").getUnclippedBoundsInRoot()
        assertTrue("Short playlist selectors must wrap their contents", selector.right - selector.left < 180.dp)
        val title = compose.onNodeWithText("All Music", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val arrow = compose.onNodeWithContentDescription("Browse library", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(arrow.left - title.right <= 8.dp)
        compose.runOnIdle {
            state.value = LibraryState(selectedId = "long", library = Library(entries = listOf(
                LibraryEntry("long", "playlist", "An extremely long playlist title ".repeat(10)))))
        }
        val search = compose.onNodeWithContentDescription("Search").assertIsDisplayed().getUnclippedBoundsInRoot()
        val longSelector = compose.onNodeWithContentDescription("Browse library").getUnclippedBoundsInRoot()
        assertTrue(longSelector.right <= search.left)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun noVocalsAreGroupedBelowOriginalsWithoutChangingPlaybackOrActionIndices() {
        val instrumental = Track("job", "[NoVocals]/Instrumental.mp3", title = "Instrumental")
        val original = Track("job", "Original.mp3", title = "Original")
        val secondInstrumental = Track("other", "[novocals]/Second.mp3", title = "Second instrumental")
        val state = LibraryState(tracks = TrackPage(files = listOf(instrumental, original, secondInstrumental)))
        var played = -1
        var action = -1
        compose.setContent {
            MusicTheme {
                LibraryContent(state, PlaybackState(connected = true), { played = it }, {}) { track, index ->
                    ToolButton(Icons.Rounded.MoreVert, "Options ${track.displayTitle}") { action = index }
                }
            }
        }
        compose.onNodeWithText("Original").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, played) }
        compose.onNodeWithText("Instrumental").assertDoesNotExist()
        val group = compose.onNodeWithText("[NoVocals] (2)").assertIsDisplayed()
        assertTrue(group.getUnclippedBoundsInRoot().top >=
            compose.onNodeWithText("Original").getUnclippedBoundsInRoot().bottom)
        compose.onNodeWithContentDescription("Expand NoVocals").performClick()
        compose.onNodeWithText("Instrumental").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, played) }
        compose.onNodeWithContentDescription("Options Second instrumental").performClick()
        compose.runOnIdle { assertEquals(2, action) }
        compose.onNodeWithContentDescription("Collapse NoVocals").performClick()
        compose.onNodeWithText("Instrumental").assertDoesNotExist()
        compose.onNodeWithText("Original").assertIsDisplayed()
    }

    @Test fun noVocalsOnlyPageCanBeExpanded() {
        val track = Track("job", "[NoVocals]/Song.mp3", title = "Accompaniment")
        compose.setContent {
            MusicTheme {
                LibraryContent(LibraryState(tracks = TrackPage(files = listOf(track))),
                    PlaybackState(connected = true), {}, {}) { _, _ -> }
            }
        }
        compose.onNodeWithText("Your library is empty").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand NoVocals").performClick()
        compose.onNodeWithText("Accompaniment").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp")
    fun librarySearchRespectsSideSystemBarInsetsInLandscape() {
        val state = mutableStateOf(LibraryState(search = "Quiet"))
        compose.setContent {
            MusicTheme {
                LibraryTopBar(state.value, true, {},
                    onSearch = { state.value = state.value.copy(search = it) }, {}, {},
                    windowInsets = WindowInsets(left = 32.dp, top = 24.dp, right = 48.dp, bottom = 0.dp))
            }
        }
        val clear = compose.onNodeWithContentDescription("Clear search").assertIsDisplayed()
        val clearBounds = clear.getUnclippedBoundsInRoot()
        assertTrue(clearBounds.right <= 780.dp - 48.dp - 16.dp)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        clear.performClick()
        compose.runOnIdle { assertEquals("", state.value.search) }
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

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun miniPlayerTopRowSwipesSkipOnceAndIgnoreShortCancelledAndVerticalGestures() {
        var previous = 0
        var next = 0
        var otherActions = 0
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("mini-player-page")) {
                    MiniPlayer(previewPlayback(), null, { otherActions++ }, { otherActions++ },
                        { next++ }, { otherActions++ }, previous = { previous++ },
                        repeat = { otherActions++ }, shuffle = { otherActions++ }) {
                        ToolButton(Icons.Rounded.MoreVert, "More song actions") { otherActions++ }
                    }
                }
            }
        }
        val page = compose.onNodeWithTag("mini-player-page")
        val y = miniPlayerTopRowY()
        val shortDistance = with(compose.density) { 30.dp.toPx() }
        page.performTouchInput {
            swipe(Offset(width * 0.5f, y), Offset(width * 0.5f + shortDistance, y))
            down(Offset(width * 0.9f, y))
            moveTo(Offset(width * 0.1f, y), delayMillis = 200)
        }
        compose.runOnIdle { assertEquals(0, next); assertEquals(0, previous) }
        page.performTouchInput {
            cancel()
            swipe(Offset(width * 0.5f, y), Offset(width * 0.5f, height * 0.7f))
            swipe(Offset(width * 0.5f, y), Offset(width * 0.5f, 0f))
        }
        val lowerRowY = compose.onNodeWithContentDescription("Shuffle").fetchSemanticsNode().boundsInRoot.center.y
        page.performTouchInput {
            swipe(Offset(width * 0.1f, lowerRowY), Offset(width * 0.9f, lowerRowY))
            swipe(Offset(width * 0.9f, lowerRowY), Offset(width * 0.1f, lowerRowY))
        }
        compose.runOnIdle { assertEquals(0, next); assertEquals(0, previous) }
        swipeMiniPlayerTopRow(left = true)
        compose.runOnIdle { assertEquals(1, next); assertEquals(0, previous) }
        swipeMiniPlayerTopRow(left = false)
        compose.runOnIdle {
            assertEquals(1, next)
            assertEquals(1, previous)
            assertEquals(0, otherActions)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun miniPlayerSwipesUseCurrentCallbacksAndCancelWhenTrackOrQueueChanges() {
        val state = mutableStateOf(previewPlayback())
        var staleCallbacks = 0
        var previous = 0
        var next = 0
        var restarted = 0
        val onPrevious = mutableStateOf<() -> Unit>({ staleCallbacks++ })
        val onNext = mutableStateOf<() -> Unit>({ staleCallbacks++ })
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("mini-player-page")) {
                    MiniPlayer(state.value, null, {}, {}, onNext.value, {},
                        previous = { restarted++ }, previousTrack = onPrevious.value)
                }
            }
        }
        val page = compose.onNodeWithTag("mini-player-page")
        val y = miniPlayerTopRowY()
        page.performTouchInput {
            down(Offset(width * 0.9f, y))
            moveTo(Offset(width * 0.1f, y), delayMillis = 200)
        }
        compose.runOnIdle {
            onPrevious.value = { previous++ }
            onNext.value = { next++ }
            Snapshot.sendApplyNotifications()
        }
        page.performTouchInput { up() }
        swipeMiniPlayerTopRow(left = false)
        compose.runOnIdle {
            assertEquals(0, staleCallbacks)
            assertEquals(1, next)
            assertEquals(1, previous)
        }
        page.performTouchInput {
            down(Offset(width * 0.1f, y))
            moveTo(Offset(width * 0.9f, y), delayMillis = 200)
        }
        compose.runOnIdle {
            state.value = state.value.copy(track = state.value.queue[1])
            Snapshot.sendApplyNotifications()
        }
        page.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, previous) }
        swipeMiniPlayerTopRow(left = false)
        compose.runOnIdle { assertEquals(2, previous) }
        page.performTouchInput {
            down(Offset(width * 0.9f, y))
            moveTo(Offset(width * 0.1f, y), delayMillis = 200)
        }
        compose.runOnIdle {
            state.value = state.value.copy(queue = listOf(state.value.track!!))
            Snapshot.sendApplyNotifications()
        }
        page.performTouchInput { up() }
        compose.onNodeWithContentDescription("Next track").assertIsNotEnabled()
        swipeMiniPlayerTopRow(left = true)
        swipeMiniPlayerTopRow(left = false)
        compose.onNodeWithContentDescription("Previous track").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, staleCallbacks)
            assertEquals(1, next)
            assertEquals(3, previous)
            assertEquals(1, restarted)
        }
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
                            { skipped = true }, seeks::add, previousTrack = { skipped = true })
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
        compose.onNodeWithText("Blue hour").performTouchInput { click() }
        compose.onNodeWithContentDescription("Pause").performTouchInput { click() }
        compose.onNodeWithContentDescription("Next track").performTouchInput { click() }
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
        compose.onNodeWithContentDescription("Shuffle").assertDoesNotExist()
        compose.onNodeWithContentDescription("Repeat: off").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun miniPlayerControlsStayBesideSongInformationOnSmallPhonesAndKeepIndependentActions() {
        val titleText = "A long song title that still has space beside its artwork"
        val state = mutableStateOf(previewPlayback().let {
            it.copy(track = it.track!!.copy(title = titleText), repeat = Player.REPEAT_MODE_OFF)
        })
        var previous = 0
        var swipePrevious = 0
        var expanded = 0
        var toggled = 0
        var actions = 0
        val showActions = mutableStateOf(false)
        compose.setContent {
            MusicTheme {
                MiniPlayer(state.value, null, { expanded++ }, { toggled++ }, {}, {},
                    previous = { previous++ },
                    repeat = { state.value = state.value.copy(repeat = (state.value.repeat + 1) % 3) },
                    shuffle = { state.value = state.value.copy(shuffle = !state.value.shuffle) },
                    previousTrack = { swipePrevious++ },
                    actions = {
                        if (showActions.value) ToolButton(Icons.Rounded.MoreVert, "More song actions",
                            modifier = Modifier.size(48.dp)) { actions++ }
                    })
            }
        }
        val title = compose.onNodeWithText(titleText).assertIsDisplayed().getUnclippedBoundsInRoot()
        val transport = listOf("Previous track", "Pause", "Next track", "Repeat: off")
        val originalBounds = transport.map { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
        compose.runOnIdle { showActions.value = true }
        assertEquals(title, compose.onNodeWithText(titleText).getUnclippedBoundsInRoot())
        assertTrue("Mini-player must leave space for scrolling song text", title.right - title.left >= 48.dp)
        var previousRight = title.right
        val pause = compose.onNodeWithContentDescription("Pause").getUnclippedBoundsInRoot()
        transport.forEachIndexed { index, label ->
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals("Song actions must not reduce the top transport row", originalBounds[index], bounds)
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            assertTrue(bounds.left >= 0.dp && bounds.right <= 320.dp)
            assertTrue("Controls must not overlap the song information or each other", bounds.left >= previousRight)
            assertTrue("Controls must stay beside the song information", bounds.top < title.bottom && bounds.bottom > title.top)
            assertEquals(pause.top, bounds.top)
            previousRight = bounds.right
        }
        val shuffle = compose.onNodeWithContentDescription("Shuffle").assertIsDisplayed().getUnclippedBoundsInRoot()
        val slider = compose.onNodeWithContentDescription("Playback position").getUnclippedBoundsInRoot()
        assertTrue(shuffle.right - shuffle.left >= 48.dp && shuffle.bottom - shuffle.top >= 48.dp)
        assertTrue(shuffle.left >= 0.dp && shuffle.right <= slider.left && slider.right <= 320.dp)
        assertTrue("Shuffle must fit beside the slider without crowding song information", shuffle.top >= pause.bottom)
        val menu = compose.onNodeWithContentDescription("More song actions").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(menu.right - menu.left >= 48.dp && menu.bottom - menu.top >= 48.dp)
        assertTrue("Song actions must stay after a usable slider", slider.right - slider.left >= 48.dp && slider.right <= menu.left)
        assertTrue(menu.right <= 320.dp && menu.top >= pause.bottom)
        assertTrue("Song actions must stay beside the slider", menu.top < slider.bottom && menu.bottom > slider.top)
        compose.onNodeWithContentDescription("More song actions").assertHasClickAction().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, actions)
            assertEquals(0, previous)
            assertEquals(0, toggled)
            assertEquals(0, expanded)
        }
        compose.onNodeWithContentDescription("Shuffle").assertIsOff().performTouchInput { click() }.assertIsOn()
        compose.onNodeWithContentDescription("Previous track").performTouchInput { click() }
        compose.onNodeWithContentDescription("Repeat: off").assertIsOff().performClick()
        compose.onNodeWithContentDescription("Repeat: one").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Repeat: all").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Repeat: off").assertIsOff()
        compose.onNodeWithContentDescription("Shuffle").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(0, swipePrevious)
            assertEquals(0, toggled)
            assertEquals(0, expanded)
        }
    }

    @Test fun miniPlayerAndFullPlayerShareShuffleAndRepeatOneWithoutChangingPausedSong() {
        val track = Track("job", "song.mp3")
        val initial = PlaybackState(track = track, queue = listOf(track), position = 42_000, duration = 120_000)
        val state = mutableStateOf(initial)
        var transportActions = 0
        val shuffle = { state.value = state.value.copy(shuffle = !state.value.shuffle) }
        val repeat = { state.value = state.value.copy(repeat = (state.value.repeat + 1) % 3) }
        compose.setContent {
            MusicTheme {
                Column {
                    MiniPlayer(state.value, null, { transportActions++ }, { transportActions++ },
                        { transportActions++ }, { transportActions++ }, { transportActions++ }, repeat, shuffle)
                    PlayerTransport(state.value, { transportActions++ }, { transportActions++ },
                        { transportActions++ }, { transportActions++ }, shuffle, repeat)
                }
            }
        }
        compose.onAllNodesWithContentDescription("Shuffle").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Repeat: off").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Repeat: off")[0].assertIsOff().performClick()
        compose.onAllNodesWithContentDescription("Repeat: one").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Repeat: one").fetchSemanticsNodes().forEach {
            assertEquals("Repeat current song", it.config[SemanticsProperties.StateDescription])
        }
        compose.onAllNodesWithContentDescription("Shuffle")[1].assertIsOff().performClick()
        compose.onAllNodesWithContentDescription("Shuffle")[0].assertIsOn()
        compose.onAllNodesWithContentDescription("Shuffle")[1].assertIsOn()
        compose.runOnIdle {
            assertEquals(initial.copy(shuffle = true, repeat = Player.REPEAT_MODE_ONE), state.value)
            assertEquals(0, transportActions)
        }
        compose.onAllNodesWithContentDescription("Repeat: one")[1].assertIsOn().performClick()
        compose.onAllNodesWithContentDescription("Repeat: all")[0].assertIsOn().performClick()
        compose.onAllNodesWithContentDescription("Repeat: off").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Shuffle")[0].performClick()
        compose.onAllNodesWithContentDescription("Shuffle")[1].assertIsOff()
        compose.runOnIdle { assertEquals(initial, state.value); assertEquals(0, transportActions) }
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun miniPlayerScrollsLongTitleAndArtistWithLargeTextWithoutMovingControls() {
        val track = Track("job", "long.mp3", title = "A long song title that needs to scroll on a cover screen",
            artist = "An artist name that also needs enough room to be read")
        val state = mutableStateOf(PlaybackState(track = track, queue = listOf(track)))
        var expanded = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MusicTheme { MiniPlayer(state.value, null, { expanded++ }, {}, {}, {}) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val playBounds = compose.onNodeWithContentDescription("Play").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Next track").assertIsNotEnabled()
        val informationBounds = compose.onNodeWithText(track.displayTitle).fetchSemanticsNode().boundsInRoot
        val labels = listOf(track.displayTitle, track.displayArtist)
        labels.forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().isLineEllipsized(0))
            assertTrue("Marquee must measure the complete text", layouts.single().size.width > informationBounds.width)
        }
        fun captureText(text: String): Bitmap {
            val viewport = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            val textBounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return compose.runOnIdle {
                val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                Bitmap.createBitmap(view.drawToBitmap(), viewport.left.roundToInt(), textBounds.top.roundToInt(),
                    viewport.width.roundToInt(), textBounds.height.roundToInt())
            }
        }
        val before = labels.map(::captureText)
        compose.mainClock.advanceTimeBy(3_000)
        labels.forEachIndexed { index, text ->
            assertFalse("Overflowing song information must scroll", before[index].sameAs(captureText(text)))
        }
        assertEquals(playBounds, compose.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot())
        compose.onNodeWithText(track.displayTitle).performClick()
        compose.runOnIdle {
            assertEquals(1, expanded)
            state.value = state.value.copy(track = track.copy(name = "short.mp3", title = "Hi", artist = "Me"))
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(track.displayTitle).assertDoesNotExist()
        val shortTitle = captureText("Hi")
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("Text that fits must remain still", shortTitle.sameAs(captureText("Hi")))
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
        val pause = compose.onNodeWithContentDescription("Pause").assertIsDisplayed().getUnclippedBoundsInRoot()
        val artwork = compose.onNodeWithContentDescription("Album artwork unavailable").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(artwork.right <= pause.left)
        assertTrue(artwork.top < pause.bottom && artwork.bottom > pause.top)
        val repeat = compose.onNodeWithContentDescription("Repeat: off").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(pause.top, repeat.top)
        assertTrue(repeat.right <= 800.dp)
        savePreview("library-tablet")
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun libraryControlsFitOnNarrowScreensWithLargeText() {
        val title = "A very long playlist title that should not crowd the controls"
        showLibraryPreview(1.5f, title)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Play this page").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        val browse = compose.onNodeWithContentDescription("Browse library").assertIsDisplayed().getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription("Search").assertIsDisplayed().getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription("Settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        val pause = compose.onNodeWithContentDescription("Pause").getUnclippedBoundsInRoot()
        assertTrue(browse.left >= 0.dp && browse.right <= search.left)
        assertTrue(search.right <= refresh.left && refresh.right <= settings.left)
        assertTrue(settings.right <= 320.dp)
        assertTrue(search.left >= 0.dp && search.right <= 320.dp)
        assertTrue(pause.left >= 0.dp && pause.right <= 320.dp)
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
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
        compose.onNodeWithText("Song rating").assertIsDisplayed()
        compose.onNodeWithText("Rating: 2 / 5").assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithContentDescription("3 stars").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsEnabled()
        compose.runOnIdle { canEdit.value = true }
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    // Robolectric #8460: text fields in dialogs loop at widths above the default 320dp.
    @Config(qualifiers = "w320dp-h800dp")
    @Test fun metadataViewerShowsFieldsArtworkRatingAndLockWithoutMutationControls() {
        compose.mainClock.autoAdvance = false
        val image = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val value = mutableStateOf(SongMetadata(title = "Song", artist = "Artist", album = "Album",
            genre = "Pop", year = "2026", rating = 3, transcriptionLocked = true,
            artwork = "data:image/png;base64," + android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP)))
        val open = mutableStateOf(true)
        var changes = 0
        var saves = 0
        compose.setContent {
            MusicTheme {
                if (open.value) MetadataDialog(value.value, { changes++ }, canEdit = false,
                    dismiss = { open.value = false }) { saves++ }
            }
        }
        compose.onNodeWithText("View song metadata").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("Album artwork").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Album artwork").assertIsDisplayed()
        listOf("Title" to "Song", "Artist" to "Artist", "Album" to "Album", "Genre" to "Pop", "Year" to "2026")
            .forEach { (label, text) ->
                compose.onNodeWithText(label).performScrollTo().assertTextContains(text)
            }
        compose.onNodeWithText("Rating: 3 / 5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Transcription locked").assertIsDisplayed().assertHasNoClickAction()
        compose.runOnIdle {
            value.value = value.value.copy(transcriptionLocked = false, artwork = null)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Transcription unlocked").assertIsDisplayed().assertHasNoClickAction()
        compose.waitUntil(5_000) {
            compose.runOnIdle { Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("Album artwork unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Album artwork unavailable").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(isToggleable()).assertDoesNotExist()
        listOf("Save", "Cancel", "Choose artwork", "Remove artwork").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        listOf("Lock transcription", "Unlock transcription").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, saves) }
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun metadataEditorRetainsFieldRatingLockAndSaveActions() {
        compose.mainClock.autoAdvance = false
        val original = SongMetadata(title = "Song", artist = "Artist", album = "Album", genre = "Pop", year = "2026")
        val value = mutableStateOf(original)
        var saved: SongMetadata? = null
        compose.setContent {
            MusicTheme { MetadataDialog(value.value, { value.value = it }, dismiss = {}) { saved = it } }
        }
        compose.onNodeWithText("Song information").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(5)
        compose.onNodeWithText("Title").performTextReplacement("Updated")
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("4 stars").performScrollTo().performClick()
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(original.copy(title = "Updated", rating = 4, transcriptionLocked = true), saved)
        }
        compose.onNodeWithText("Cancel").assertIsEnabled()
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

    private fun miniPlayerTopRowY(): Float =
        compose.onNodeWithContentDescription("Previous track").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithTag("mini-player-page").fetchSemanticsNode().boundsInRoot.top

    private fun swipeMiniPlayerTopRow(left: Boolean) {
        val y = miniPlayerTopRowY()
        compose.onNodeWithTag("mini-player-page").performTouchInput {
            val start = if (left) 0.9f else 0.1f
            swipe(Offset(width * start, y), Offset(width * (1f - start), y))
        }
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
                        LibraryTopBar(library, true, {}, {}, {}, {})
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