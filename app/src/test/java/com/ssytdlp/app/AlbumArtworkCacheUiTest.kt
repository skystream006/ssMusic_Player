package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Preferences
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
class AlbumArtworkCacheUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun teardown() { scope.cancel() }

    @Test fun playerAndRowFallbacksRenderTheSelectedLogoWhenTheThemeChanges() {
        val wave = mutableStateOf(true)
        compose.setContent {
            MusicTheme(Preferences("gold", "dark"), wave.value) {
                Row {
                    AlbumArtwork(null, Modifier.size(46.dp))
                    TrackArtwork(Track(name = "song"), false, null)
                    Image(painterResource(if (wave.value) R.mipmap.ic_launcher else R.mipmap.ic_launcher_gold_dark),
                        "Expected logo", Modifier.size(46.dp))
                }
            }
        }
        fun samples(): List<Int> {
            val expected = compose.onNodeWithContentDescription("Expected logo").fetchSemanticsNode().boundsInRoot
            return compose.runOnIdle {
                val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val differences = IntArray(2)
                    buildList {
                        for (i in 2..8) for (j in 2..8) {
                            val x = (expected.left + expected.width * i / 10).toInt()
                            val y = (expected.top + expected.height * j / 10).toInt()
                            val pixel = bitmap.getPixel(x, y)
                            differences.indices.forEach { index ->
                                val other = bitmap.getPixel(x - (index + 1) * expected.width.toInt(), y)
                                for (shift in listOf(0, 8, 16)) differences[index] += kotlin.math.abs(
                                    (pixel shr shift and 255) - (other shr shift and 255))
                            }
                            add(pixel)
                        }
                        // Rounded clip layers can round a color channel differently from the unlayered reference.
                        differences.forEach { assertTrue("Mean RGB channel error: ${it / (size * 3.0)}", it < size * 3 * 3) }
                    }
                } finally { bitmap.recycle() }
            }
        }
        val blue = samples()
        compose.runOnIdle { wave.value = false }
        assertNotEquals(blue, samples())
    }

    @Test fun returningToArtworkAfterTabSwitchReusesDecodedImage() {
        val calls = AtomicInteger()
        val cache = MetadataArtworkCache(scope, decode = { _, _ ->
            calls.incrementAndGet()
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        })
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalMetadataArtworkCache provides cache) {
                if (visible.value) AlbumArtwork("data:image/png;base64,test", Modifier.size(48.dp))
            }
        }
        waitForArtwork()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithContentDescription("Album artwork").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        waitForArtwork()
        assertEquals(1, calls.get())
    }

    @Test fun trackChangeNeverShowsPreviousImageWhileNewImageDecodes() {
        val release = CompletableDeferred<Unit>()
        val cache = MetadataArtworkCache(scope, decode = { source, _ ->
            if (source.endsWith("second")) release.await()
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        })
        val source = mutableStateOf("data:image/png;base64,first")
        compose.setContent {
            CompositionLocalProvider(LocalMetadataArtworkCache provides cache) {
                AlbumArtwork(source.value, Modifier.size(48.dp))
            }
        }
        waitForArtwork()
        compose.runOnIdle { source.value = "data:image/png;base64,second" }
        compose.onNodeWithContentDescription("Album artwork unavailable").assertExists()
        compose.onNodeWithContentDescription("Album artwork").assertDoesNotExist()
        release.complete(Unit)
        waitForArtwork()
    }

    @Test fun miniPlayerRequestsPhysicalPixelsRatherThanDp() {
        val target = AtomicInteger()
        val cache = MetadataArtworkCache(scope, decode = { _, pixels ->
            target.set(pixels)
            Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        })
        compose.setContent {
            CompositionLocalProvider(LocalMetadataArtworkCache provides cache, LocalDensity provides Density(3f)) {
                AlbumArtwork("data:image/png;base64,test", Modifier.size(48.dp))
            }
        }
        waitForArtwork()
        assertEquals(256, target.get())
    }

    @Test fun accountChangeRejectsArtworkCapturedByThePreviousComposition() {
        val first = Account("https://music.example", User("first"), Session("test", "2099-01-01T00:00:00Z"))
        val calls = AtomicInteger()
        val cache = MetadataArtworkCache(scope, decode = { _, _ ->
            calls.incrementAndGet()
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        })
        cache.setAccount(first)
        compose.setContent {
            CompositionLocalProvider(LocalMetadataArtworkCache provides cache,
                LocalMetadataArtworkOwner provides MetadataOwner(first)) {
                AlbumArtwork("data:image/png;base64,test", Modifier.size(48.dp))
            }
        }
        waitForArtwork()
        compose.runOnIdle { cache.setAccount(first.copy(user = User("second"))) }
        compose.onNodeWithContentDescription("Album artwork unavailable").assertExists()
        compose.onNodeWithContentDescription("Album artwork").assertDoesNotExist()
        assertEquals(1, calls.get())
    }

    private fun waitForArtwork() = compose.waitUntil(5_000) {
        compose.onAllNodesWithContentDescription("Album artwork").fetchSemanticsNodes().size == 1
    }
}
