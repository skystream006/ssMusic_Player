package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.User
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h360dp-land")
class LandscapeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val provider = object : Provider("LandscapeTestKeyStore", 1.0, "Empty test session keystore") {}
    private lateinit var model: MusicViewModel
    private val track = Track("source", "first.mp3", title = "First song")
    private val queued = Track("source", "second.mp3", title = "Second song")
    private val playback = PlaybackState(track = track, queue = listOf(track, queued), connected = true, duration = 60_000)

    @Before fun setup() {
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val application = MusicApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
        application.onCreate()
        compose.runOnUiThread {
            model = MusicViewModel(application)
            models.put("landscape", model)
            model.viewModelScope.cancel()
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun landscapeAppUsesMiniPlayerAndBackWithoutBottomNavigation() {
        assertAppNavigationWithoutBottomBar()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun portraitAppUsesMiniPlayerAndBackWithoutBottomNavigation() {
        assertAppNavigationWithoutBottomBar()
    }

    @Test fun libraryUsesThirtyFiveSixtyFiveSplitAndKeepsPlaylistSelectionInline() {
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            library.value = LibraryState(
                library = Library(entries = listOf(
                    LibraryEntry("folder", "folder", "Favorites"),
                    LibraryEntry("playlist", "playlist", "Evening", parentId = "folder")),
                    playlists = listOf(LibraryPlaylist("playlist", "Evening playlist", 1))),
                tracks = TrackPage(files = listOf(track)))
        }
        var played = -1
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    LibraryScreen(model, playback, { played = it }) { _, _ -> }
                }
            }
        }
        assertSplit("library-playlists-pane", "library-songs-pane", 0.35f)
        compose.onNodeWithText("First song").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, played) }
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Evening playlist").performClick()
        compose.runOnIdle { assertEquals("playlist", model.library.selectedId) }
        compose.onNodeWithTag("library-playlists-pane").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.onNodeWithText("All Music").performClick()
        compose.runOnIdle { assertEquals(null, model.library.selectedId) }
    }

    @Test fun shortLibraryPanesScrollIndependently() {
        val library = ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
        compose.runOnIdle {
            library.value = LibraryState(
                library = Library(entries = List(20) { LibraryEntry("playlist-$it", "playlist", "Playlist $it") }),
                tracks = TrackPage(files = List(20) { Track("source", "$it.mp3", title = "Song $it") }))
        }
        compose.setContent {
            MusicTheme {
                Box(Modifier.height(150.dp)) { LibraryScreen(model, playback, {}) { _, _ -> } }
            }
        }
        compose.onNodeWithTag("library-playlists-pane").performScrollToNode(hasText("Playlist 19"))
        compose.onNodeWithText("Playlist 19").assertIsDisplayed()
        compose.onNodeWithText("Song 0").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-songs-pane")))
            .performScrollToNode(hasText("Song 19"))
        compose.onNodeWithText("Song 19").assertIsDisplayed()
        compose.onNodeWithText("Playlist 19").assertIsDisplayed()
    }

    @Test fun nowPlayingKeepsQueueBesideBothTabsAndControlsVisibleInShortViewport() {
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        compose.runOnIdle { metadata.value = SongMetadata(uslt = "Landscape lyrics") }
        compose.setContent {
            MusicTheme {
                Box(Modifier.height(200.dp).testTag("screen")) {
                    NowPlayingScreen(model, playback) { _, _ -> }
                }
            }
        }
        assertSplit("now-playing-pane", "now-playing-queue-pane", 0.65f)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(2)
        compose.onNodeWithText("Player").assertIsSelected()
        compose.onNodeWithTag("landscape-player-artwork").assertIsDisplayed().assertHeightIsAtLeast(1.dp)
        compose.onNodeWithText("Second song").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithText("1:00").assertIsDisplayed()
        compose.onNodeWithText("USLT Lyrics").performClick().assertIsSelected()
        compose.onNodeWithText("Landscape lyrics").assertIsDisplayed()
        compose.onNodeWithText("Second song").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Remove from queue").assertCountEquals(2)
        compose.onNodeWithText("Player").performClick().assertIsSelected()
        compose.onNodeWithText("Second song").assertIsDisplayed()
    }

    @Test fun emptyLandscapePlayerStillShowsTheQueuePane() {
        compose.setContent { MusicTheme { NowPlayingScreen(model, PlaybackState()) { _, _ -> } } }
        compose.onNodeWithText("Choose a song from Library to start playing.").assertIsDisplayed()
        compose.onNodeWithText("Your queue is empty.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun shortLandscapeLyricsKeepBothSourcesVisible() {
        val metadata = ReflectionHelpers.getField<MutableState<SongMetadata?>>(model, "metadata\$delegate")
        val preferUslt = mutableStateOf(false)
        compose.runOnIdle {
            metadata.value = SongMetadata(sylt = listOf(LyricLine(0.0, "Synchronized lyrics")),
                uslt = "Plain lyrics")
        }
        compose.setContent {
            MusicTheme {
                Box(Modifier.height(40.dp).testTag("screen")) {
                    Lyrics(model, playback, Modifier.fillMaxSize(), preferUslt.value)
                }
            }
        }
        fun assertLyricsVisible(text: String) {
            val screen = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
            val lyrics = compose.onNodeWithText(text).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(lyrics.top >= screen.top)
            assertTrue(lyrics.bottom <= screen.bottom)
        }
        assertLyricsVisible("Synchronized lyrics")
        compose.runOnIdle {
            preferUslt.value = true
            Snapshot.sendApplyNotifications()
        }
        assertLyricsVisible("Plain lyrics")
    }

    @Test fun narrowCompactTransportKeepsTheSeekSliderVisible() {
        compose.setContent {
            MusicTheme {
                Box(Modifier.width(320.dp)) {
                    PlayerTransport(playback, {}, {}, {}, {}, {}, {}, compact = true)
                }
            }
        }
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed().assertWidthIsAtLeast(1.dp)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithText("1:00").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun portraitLibraryStillUsesTheModalBrowser() {
        compose.setContent {
            MusicTheme { LibraryScreen(model, playback, {}) { _, _ -> } }
        }
        compose.onNodeWithTag("library-playlists-pane").assertDoesNotExist()
        compose.onNodeWithText("TRACKS").assertIsDisplayed()
        compose.onNodeWithText("All Music").assertDoesNotExist()
    }

    @Test fun rotatingFromQueueRestoresAValidPlayerTabAndPreservesLyricsSelection() {
        val orientation = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { this.orientation = orientation.value }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MusicTheme { NowPlayingScreen(model, playback) { _, _ -> } }
            }
        }
        compose.onNodeWithText("Queue").performClick().assertIsSelected()
        compose.runOnIdle {
            orientation.value = Configuration.ORIENTATION_LANDSCAPE
            Snapshot.sendApplyNotifications()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Player").assertIsSelected()
        compose.onNodeWithTag("now-playing-queue-pane").assertIsDisplayed()
        compose.onNodeWithText("Lyrics").performClick().assertIsSelected()
        compose.runOnIdle {
            orientation.value = Configuration.ORIENTATION_PORTRAIT
            Snapshot.sendApplyNotifications()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Lyrics").assertIsSelected()
        compose.onNodeWithText("Queue").assertHasClickAction()
        compose.onNodeWithTag("now-playing-queue-pane").assertDoesNotExist()
    }

    private fun assertAppNavigationWithoutBottomBar() {
        val state = ReflectionHelpers.getField<MutableStateFlow<PlaybackState>>(model.playback, "mutableState")
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[AppUpdater::class.java].viewModelScope.cancel()
            model.getApplication<MusicApplication>().serverConfig.set("https://music.example.com")
            ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount").value =
                Account("https://music.example.com", User(name = "Listener"),
                    Session("test-session", "2099-01-01T00:00:00Z"))
        }
        compose.setContent { MusicApp(model) {} }
        fun assertNoBottomNavigation() {
            compose.onNodeWithContentDescription("Library").assertDoesNotExist()
            compose.onNodeWithContentDescription("Now Playing").assertDoesNotExist()
            compose.onNodeWithText("Now Playing").assertDoesNotExist()
        }
        fun assertLibrary() {
            compose.onNodeWithText("TRACKS").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onNodeWithTag("now-playing-top-bar").assertDoesNotExist()
            assertNoBottomNavigation()
        }
        fun assertNowPlayingTopBar() {
            val topBar = compose.onNodeWithTag("now-playing-top-bar").assertIsDisplayed().getUnclippedBoundsInRoot()
            val pane = compose.onNodeWithTag("now-playing-pane").assertIsDisplayed().getUnclippedBoundsInRoot()
            compose.onNodeWithText("NOW PLAYING").assertIsDisplayed()
                .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
            compose.onNode(hasText("First song") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assertIsDisplayed().assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
            compose.onAllNodesWithContentDescription("More song actions").assertCountEquals(1)
            compose.onNodeWithContentDescription("More song actions").assertIsDisplayed()
                .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
            listOf("Back", "Refresh", "Settings").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHasClickAction()
                    .assert(hasAnyAncestor(hasTestTag("now-playing-top-bar")))
            }
            compose.onNodeWithText("ssMusic").assertDoesNotExist()
            compose.onNodeWithText("Listener's Music").assertDoesNotExist()
            assertEquals(topBar.bottom, pane.top)
            val playerTab = compose.onNode(hasText("Player") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).getUnclippedBoundsInRoot()
            assertEquals(pane.top, playerTab.top)
        }
        assertLibrary()
        compose.onNodeWithContentDescription("Playback position").assertDoesNotExist()
        compose.runOnIdle { state.value = playback }
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        assertLibrary()
        compose.onNodeWithText("First song").performClick()
        compose.onNodeWithTag("now-playing-pane").assertIsDisplayed()
        compose.onNodeWithText("Player").assertIsSelected()
        assertNowPlayingTopBar()
        assertNoBottomNavigation()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("now-playing-pane").assertDoesNotExist()
        compose.onNodeWithTag("now-playing-top-bar").assertDoesNotExist()
        compose.onNodeWithText("ssMusic").assertIsDisplayed()
        compose.onNodeWithText("Listener's Music").assertIsDisplayed()
        assertNoBottomNavigation()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("now-playing-pane").assertIsDisplayed()
        assertNowPlayingTopBar()
        compose.onNodeWithContentDescription("Back").performClick()
        assertLibrary()
        compose.onNodeWithText("First song").performClick()
        compose.onNodeWithTag("now-playing-pane").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("now-playing-pane").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("now-playing-pane").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertLibrary()
        compose.onNodeWithText("First song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertLibrary()
    }

    private fun assertSplit(leftTag: String, rightTag: String, leftFraction: Float) {
        val screen = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        val left = compose.onNodeWithTag(leftTag).getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag(rightTag).getUnclippedBoundsInRoot()
        assertEquals(screen.width.value * leftFraction, left.width.value, 1f)
        assertEquals(screen.width.value * (1f - leftFraction), right.width.value, 1f)
        assertEquals(left.right.value, right.left.value, 1f)
        assertEquals(left.top, right.top)
        assertEquals(left.height, right.height)
        assertTrue(left.height.value > 0f)
    }
}
