package com.ssytdlp.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import org.aomedia.avif.android.AvifDecoder

internal fun decodeArtworkBitmap(bytes: ByteArray, thumbnail: Boolean = false): Bitmap? {
    val maxBytes = if (thumbnail) 64 * 1024 else 2 * 1024 * 1024
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
    if (dimensions.outWidth > 0 && dimensions.outHeight > 0) {
        val sample = artworkSampleSize(dimensions.outWidth, dimensions.outHeight, thumbnail) ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })?.let { return it }
    }
    // AVIF decoding is not guaranteed by the platform on older supported Android versions.
    return try {
        val encoded = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        if (!AvifDecoder.isAvifImage(encoded)) return null
        val info = AvifDecoder.Info()
        if (!AvifDecoder.getInfo(encoded, bytes.size, info)) return null
        val sample = artworkSampleSize(info.width, info.height, thumbnail) ?: return null
        // libavif decodes the source before scaling; bound that allocation as well.
        if (info.width.toLong() * info.height > 16_777_216) return null
        val bitmap = Bitmap.createBitmap((info.width / sample).coerceAtLeast(1),
            (info.height / sample).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var decoded = false
        try {
            decoded = AvifDecoder.decode(encoded, bytes.size, bitmap, 1)
            if (decoded) bitmap else null
        } finally {
            if (!decoded) bitmap.recycle()
        }
    } catch (_: UnsatisfiedLinkError) {
        null
    }
}

private fun artworkSampleSize(width: Int, height: Int, thumbnail: Boolean): Int? {
    if (width <= 0 || height <= 0) return null
    if (thumbnail) return if (width <= 192 && height <= 192) 1 else null
    var sample = 1
    while (width / sample > 1024 || height / sample > 1024) sample *= 2
    return sample
}
