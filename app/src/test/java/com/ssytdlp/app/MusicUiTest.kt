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

    @Test fun libraryWaveLayoutRendersOnPhone() {
        showLibraryPreview()
        compose.onNodeWithText("All Music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play this page").assertIsDisplayed()
        compose.onNodeWithText("Quiet signal").assertIsDisplayed()
        savePreview("library-phone")
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
                    MiniPlayer(playback, null, {}, {}, {})
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
    fun libraryWaveLayoutRendersOnTablet() {
        showLibraryPreview()
        compose.onNodeWithContentDescription("Browse library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        savePreview("library-tablet")
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun libraryControlsFitOnNarrowScreensWithLargeText() {
        showLibraryPreview(1.5f)
        compose.onNodeWithContentDescription("Play this page").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        val play = compose.onNodeWithContentDescription("Play this page").getUnclippedBoundsInRoot()
        val pause = compose.onNodeWithContentDescription("Pause").getUnclippedBoundsInRoot()
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
        compose.setContent {
            MusicTheme {
                LibraryContent(library, PlaybackState(connected = true), {}, { selected = it }, {}, {}) { song, _ ->
                    ToolButton(Icons.Rounded.MoreVert, "Options for ${song.displayTitle}") { menu = true }
                }
            }
        }
        val rating = compose.onNodeWithContentDescription("Rating: 4 out of 5", useUnmergedTree = true)
            .assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("4/5", useUnmergedTree = true).assertIsDisplayed()
        val options = compose.onNodeWithContentDescription("Options for Rated song").assertIsDisplayed()
        val ratingBounds = rating.getUnclippedBoundsInRoot()
        val menuBounds = options.getUnclippedBoundsInRoot()
        assertTrue(ratingBounds.right <= menuBounds.left)
        assertEquals(menuBounds.center.y.value, ratingBounds.center.y.value, 1f)
        options.performClick()
        compose.runOnIdle { assertTrue(menu); assertEquals(-1, selected) }
        compose.onNodeWithText("Rated song").performClick()
        compose.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun songRatingReflectsUpdatesAndDisappearsWhenCleared() {
        val track = mutableStateOf(Track("job", "song.mp3", title = "Song"))
        compose.setContent {
            MusicTheme {
                TrackRow(track.value, onClick = {}) {
                    ToolButton(Icons.Rounded.MoreVert, "Song options") {}
                }
            }
        }
        val ratings = hasContentDescription("Rating:", substring = true)
        compose.onAllNodes(ratings, useUnmergedTree = true).assertCountEquals(0)
        for (rating in 1..5) {
            compose.runOnIdle { track.value = track.value.copy(rating = rating) }
            compose.onAllNodes(ratings, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithContentDescription("Rating: $rating out of 5", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("$rating/5", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.runOnIdle { track.value = track.value.copy(rating = 0) }
        compose.onAllNodes(ratings, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Song options").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun longTrackNamesLeaveTheirRatingAndMenuAccessibleWithLargeText() {
        val title = "A long song title with enough words to wrap across several lines on a phone"
        var selected = false
        var menu = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MusicTheme {
                    Box(Modifier.width(320.dp)) {
                        TrackRow(Track("job", "song.mp3", title, "An artist with a long name", rating = 5), onClick = { selected = true }) {
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

    private fun showLibraryPreview(fontScale: Float = 1f) {
        val playback = previewPlayback()
        val library = LibraryState(library = Library(songCount = 248), tracks = TrackPage(files = playback.queue, total = 248, totalPages = 5))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MusicTheme {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { MusicTopBar("Alex", true, {}, {}) }, bottomBar = {
                        Column {
                            MiniPlayer(playback, null, {}, {}, {})
                            MusicNavigation(0) {}
                        }
                    }) { padding ->
                        Box(Modifier.padding(padding)) {
                            LibraryContent(library, playback, {}, {}, {}, {}) { track, _ ->
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