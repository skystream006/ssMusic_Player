package com.ssytdlp.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Track

@Composable
internal fun rememberTrackArtwork(track: Track, api: ServerApi, account: Account?): ImageBitmap? =
    key(api, account?.origin, account?.user?.id, account?.session, track.artworkUrl, track.mediaType) {
        val path = track.artworkUrl?.takeIf { track.mediaType == "audio" || track.mediaType == "video" }
        val cache = api.trackArtworkCache
        val bitmap by produceState(cache.peek(account, path)) {
            value = cache.load(account, path)
        }
        bitmap
    }

internal fun decodeTrackArtwork(bytes: ByteArray): ImageBitmap? =
    decodeArtworkBitmap(bytes, thumbnail = true)?.asImageBitmap()

@Composable
internal fun TrackArtwork(track: Track, active: Boolean, bitmap: ImageBitmap?) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, "Album artwork for ${track.displayTitle}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (active) Icon(Icons.Rounded.GraphicEq, null,
                Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surfaceContainer).size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(if (active) Icons.Rounded.GraphicEq else if (track.mediaType == "video") Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
