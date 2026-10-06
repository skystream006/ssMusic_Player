package com.ssytdlp.app

import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Run on API 26 as well as current Android: the bundled AVIF decoder uses Android JNI.
@RunWith(AndroidJUnit4::class)
class ArtworkDecoderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decodes192pxAvifThumbnailFromBytes() {
        val image = decodeTrackArtwork(bytes(thumbnail))
        assertNotNull(image)
        assertEquals(192, image!!.width)
        assertEquals(192, image.height)
        val bitmap = image.asAndroidBitmap()
        val pixel = bitmap.getPixel(96, 96)
        assertTrue(Color.red(pixel) > 200)
        assertTrue(Color.green(pixel) < 40)
        assertTrue(Color.blue(pixel) < 40)
        bitmap.recycle()
    }

    @Test fun rejectsOversizedAndTruncatedAvifThumbnails() {
        assertNull(decodeTrackArtwork(bytes(tooWide)))
        assertNull(decodeTrackArtwork(bytes(tooTall)))
        assertNull(decodeTrackArtwork(bytes(thumbnail).copyOf(20)))
        assertNull(decodeTrackArtwork(bytes(thumbnail).copyOf(282)))
        assertNull(decodeTrackArtwork(bytes(thumbnail).copyOf(64 * 1024 + 1)))
    }

    @Test fun downsamplesAvifAlbumArtwork() {
        val bitmap = decodeArtworkBitmap(bytes(album))
        assertNotNull(bitmap)
        assertEquals(1024, bitmap!!.width)
        assertEquals(512, bitmap.height)
        bitmap.recycle()
    }

    @Test fun displaysAvifMetadataArtwork() {
        compose.setContent { MusicTheme { AlbumArtwork("data:image/avif;base64,$thumbnail") } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Album artwork").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Album artwork").assertExists()
        compose.onNodeWithContentDescription("Album artwork unavailable").assertDoesNotExist()
    }

    @Test fun decodesAvifMetadataAtMiniPlayerResolution() {
        val bitmap = decodeArtworkBitmap(bytes(album), targetPixels = 128)!!
        assertEquals(128, bitmap.width)
        assertEquals(64, bitmap.height)
        bitmap.recycle()
    }

    private fun bytes(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    companion object {
        // Original solid-red YUV420 images encoded with avifenc; no external image assets.
        private const val thumbnail =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADybWV0YQAAAAAAAAAoaGRscgAAAAAAAAAAcGljdAAAAAAA" +
            "AAAAAAAAAGxpYmF2aWYAAAAADnBpdG0AAAAAAAEAAAAeaWxvYwAAAABEAAABAAEAAAABAAABGgAAADMAAAAoaWluZgAAAAAA" +
            "AQAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAAamlwcnAAAABLaXBjbwAAABRpc3BlAAAAAAAAAMAAAADAAAAAEHBpeGkA" +
            "AAAAAwgICAAAAAxhdjFDgQAMAAAAABNjb2xybmNseAABAA0ABgAAAAAXaXBtYQAAAAAAAAABAAEEAQKDBAAAADttZGF0EgAK" +
            "Chgd7+/YICGgwIAyIxGQAYYYYQDBtj9mJjPc0bKOOMaW/OaeTDjJMMGNMJC5uBsQ"
        private const val tooWide =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADybWV0YQAAAAAAAAAoaGRscgAAAAAAAAAAcGljdAAAAAAA" +
            "AAAAAAAAAGxpYmF2aWYAAAAADnBpdG0AAAAAAAEAAAAeaWxvYwAAAABEAAABAAEAAAABAAABGgAAAEEAAAAoaWluZgAAAAAA" +
            "AQAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAAamlwcnAAAABLaXBjbwAAABRpc3BlAAAAAAAAAMEAAADAAAAAEHBpeGkA" +
            "AAAAAwgICAAAAAxhdjFDgQAMAAAAABNjb2xybmNseAABAA0ABgAAAAAXaXBtYQAAAAAAAAABAAEEAQKDBAAAAEltZGF0EgAK" +
            "Chgd8C/YICGgwIAyMRGQAYYYYQDBtj9mJjPc0bKOOMaW/OaeTDjJM8TFB976stmPWHcLE/7sGO9FpcwzJdA="
        private const val tooTall =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADybWV0YQAAAAAAAAAoaGRscgAAAAAAAAAAcGljdAAAAAAA" +
            "AAAAAAAAAGxpYmF2aWYAAAAADnBpdG0AAAAAAAEAAAAeaWxvYwAAAABEAAABAAEAAAABAAABGgAAAEEAAAAoaWluZgAAAAAA" +
            "AQAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAAamlwcnAAAABLaXBjbwAAABRpc3BlAAAAAAAAAMAAAADBAAAAEHBpeGkA" +
            "AAAAAwgICAAAAAxhdjFDgQAMAAAAABNjb2xybmNseAABAA0ABgAAAAAXaXBtYQAAAAAAAAABAAEEAQKDBAAAAEltZGF0EgAK" +
            "Chgd7/AYICGgwIAyMRGQAYYYYQDBtj9mJjPc0bKOOMaW/OaeTDjJMMGNMJC5uBsjVigb+KAL1HnGPUSUBLM="
        private const val album =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADybWV0YQAAAAAAAAAoaGRscgAAAAAAAAAAcGljdAAAAAAA" +
            "AAAAAAAAAGxpYmF2aWYAAAAADnBpdG0AAAAAAAEAAAAeaWxvYwAAAABEAAABAAEAAAABAAABGgAAAFYAAAAoaWluZgAAAAAA" +
            "AQAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAAamlwcnAAAABLaXBjbwAAABRpc3BlAAAAAAAACAAAAAQAAAAAEHBpeGkA" +
            "AAAAAwgICAAAAAxhdjFDgQgMAAAAABNjb2xybmNseAABAA0ABgAAAAAXaXBtYQAAAAAAAAABAAEEAQKDBAAAAF5tZGF0EgAK" +
            "Choqf//+wQENBgQyRhGQAYYYYQDBtj9mJjPc0bKOOMaW/OaeTDjJMIrHh/2q7MCmzmUAx4TCZL7xauWcRnEwRftj8wOP" +
            "MT9W/qVETQrKZQpHM5g="
    }
}
