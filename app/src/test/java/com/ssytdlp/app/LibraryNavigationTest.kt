package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.User
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account("https://music.example", User("listener"),
        Session("test", "2099-01-01T00:00:00Z"))
    private val tracks = List(70) {
        Track("job", "song-$it.mp3", title = "Song $it", artworkUrl = "/api/jobs/job/artwork/song-$it.mp3?v=1")
    } + Track("job", "[NoVocals]/song.mp3", title = "Instrumental")
    private val requests = AtomicInteger()
    private val library = mutableStateOf(LibraryState(selectedId = "playlist", search = "song", page = 3,
        tracks = TrackPage(files = tracks, page = 3, totalPages = 5)))
    private lateinit var browsing: LibraryBrowsingState
    private lateinit var expanded: MutableState<Boolean>
    private lateinit var pageLifecycle: LifecycleOwner
    private var playing by mutableIntStateOf(0)
    private var pageStarts = 0
    private var pageDisposals = 0
    private var libraryActions = 0
    private var seek by mutableFloatStateOf(0f)
    private val api by lazy {
        val bytes = ByteArrayOutputStream().use { stream ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
        ServerApi({ account }, {}, OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Artwork")
                .body(bytes.toResponseBody()).build()
        }.build())
    }

    @Test fun playerRoundTripKeepsScrollGroupsFolderAndThumbnails() {
        compose.setContent { Navigation() }
        compose.onNodeWithTag("library-tracks").performScrollToIndex(70)
        compose.onNodeWithContentDescription("Expand NoVocals").performClick()
        compose.onNodeWithTag("library-tracks").performScrollToIndex(45)
        compose.runOnIdle {
            browsing.parent = "nested-folder"
            runBlocking { browsing.tracks.scrollToItem(45, 17) }
        }
        waitForArtwork(45)
        val index = browsing.tracks.firstVisibleItemIndex
        val offset = browsing.tracks.firstVisibleItemScrollOffset
        compose.onNodeWithText("Open player").performClick()
        compose.onNodeWithText("Next track").performClick()
        val requestsBeforeReturn = requests.get()
        compose.onNodeWithText("Back").performClick()
        waitForArtwork(45)
        compose.runOnIdle {
            assertEquals(index, browsing.tracks.firstVisibleItemIndex)
            assertEquals(offset, browsing.tracks.firstVisibleItemScrollOffset)
            assertTrue(browsing.showNoVocals)
            assertEquals("nested-folder", browsing.parent)
            assertEquals(1, playing)
            assertEquals(1, pageStarts)
            assertEquals(0, pageDisposals)
            assertEquals(requestsBeforeReturn, requests.get())
            assertEquals("playlist", library.value.selectedId)
            assertEquals("song", library.value.search)
            assertEquals(3, library.value.page)
        }
    }

    @Test fun coveredLibraryCannotReceiveTouchesKeysOrAccessibilityActions() {
        compose.setContent { Navigation() }
        compose.onNodeWithTag("library-input").performClick().assertIsFocused()
        compose.onNodeWithText("Open player").performClick()
        compose.onNodeWithTag("library-input").assertDoesNotExist()
        compose.onNodeWithText("Library action").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Lifecycle.State.CREATED, pageLifecycle.lifecycle.currentState) }
        compose.onNodeWithTag("player").performTouchInput { click(center) }
        compose.onNodeWithTag("player").performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        compose.runOnIdle {
            assertEquals(0, libraryActions)
            expanded.value = false
        }
        compose.onNodeWithTag("library-input").assertIsNotFocused()
        compose.onNodeWithText("Library action").performClick()
        compose.runOnIdle {
            assertEquals(1, libraryActions)
            assertEquals(Lifecycle.State.RESUMED, pageLifecycle.lifecycle.currentState)
        }
    }

    @Test fun recreationWhilePlayerIsOpenRestoresUnderlyingBrowsingState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Navigation() }
        compose.onNodeWithTag("library-tracks").performScrollToIndex(35)
        compose.runOnIdle {
            browsing.parent = "nested-folder"
            browsing.showNoVocals = true
            runBlocking { browsing.tracks.scrollToItem(35, 11) }
        }
        compose.onNodeWithText("Open player").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(35, browsing.tracks.firstVisibleItemIndex)
            assertEquals(11, browsing.tracks.firstVisibleItemScrollOffset)
            assertEquals("nested-folder", browsing.parent)
            assertTrue(browsing.showNoVocals)
        }
    }

    @Test fun overlayStillAcceptsSlowStartingPlayerDrags() {
        compose.setContent { Navigation() }
        compose.onNodeWithText("Open player").performClick()
        compose.onNodeWithTag("player-seek").performTouchInput {
            down(centerLeft)
            moveBy(androidx.compose.ui.geometry.Offset(1f, 0f))
            moveTo(centerRight, delayMillis = 600)
            up()
        }
        compose.runOnIdle { assertTrue(seek > 0.8f) }
    }

    @Test fun overlayAcceptsPlayerSeekTapsWithoutExposingLibraryActions() {
        compose.setContent { Navigation() }
        compose.onNodeWithText("Open player").performClick()
        compose.onNodeWithTag("player-seek").performTouchInput { click(centerRight) }
        compose.runOnIdle {
            assertTrue(seek > 0.8f)
            assertEquals(0, libraryActions)
        }
        compose.onNodeWithText("Library action").assertDoesNotExist()
    }

    @Test fun changingBrowsingContextResetsTrackPositionButNotBrowserFolder() {
        compose.setContent { Navigation() }
        compose.onNodeWithTag("library-tracks").performScrollToIndex(30)
        compose.runOnIdle {
            browsing.parent = "folder"
            browsing.showNoVocals = true
            library.value = library.value.copy(search = "different")
        }
        compose.runOnIdle {
            assertEquals(0, browsing.tracks.firstVisibleItemIndex)
            assertFalse(browsing.showNoVocals)
            assertEquals("folder", browsing.parent)
        }
    }

    @Composable
    private fun Navigation() {
        expanded = rememberSaveable { mutableStateOf(false) }
        browsing = rememberLibraryBrowsingState(library.value)
        MusicTheme {
            PlayerScreenTransition(0, expanded.value, true) { screen, pageModifier, dock ->
                if (screen == 0) {
                    pageLifecycle = LocalLifecycleOwner.current
                    DisposableEffect(Unit) {
                        pageStarts++
                        onDispose { pageDisposals++ }
                    }
                    Scaffold(topBar = {
                        Column {
                            TextField("", {}, Modifier.testTag("library-input"))
                            Button(onClick = { libraryActions++ }) { Text("Library action") }
                        }
                    }, bottomBar = {
                        dock {
                            Button(onClick = { expanded.value = true }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                                Text("Open player")
                            }
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            LibraryContent(library.value, PlaybackState(track = tracks[playing], connected = true),
                                {}, {}, artwork = { rememberTrackArtwork(it, api, account) }, browsing = browsing) { _, _ -> }
                        }
                    }
                } else {
                    Column(pageModifier.fillMaxSize().testTag("player")) {
                        Text("Now playing ${tracks[playing].displayTitle}")
                        Button(onClick = { expanded.value = false }) { Text("Back") }
                        Button(onClick = { playing++ }) { Text("Next track") }
                        Slider(seek, { seek = it }, Modifier.testTag("player-seek"))
                    }
                }
            }
        }
    }

    private fun waitForArtwork(index: Int) = compose.waitUntil(5_000) {
        compose.onAllNodesWithContentDescription("Album artwork for Song $index", useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }
}
