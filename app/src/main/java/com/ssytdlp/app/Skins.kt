package com.ssytdlp.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

enum class AppSkin(val label: String, val description: String, @DrawableRes val drawable: Int) {
    CHERRY_BLOSSOM("Cherry Blossom Sunset", "Windblown blossoms and petals over a warm sunset", R.drawable.skin_cherry_blossom),
    STARRY_CITY("Starry City Sunset", "A star-filled night above a city skyline at sunset", R.drawable.skin_starry_city);

    companion object {
        fun fromPreference(value: String?): AppSkin = entries.find { it.name == value } ?: CHERRY_BLOSSOM
    }
}

internal val LocalAppSkin = staticCompositionLocalOf<AppSkin?> { null }

@Composable
internal fun appBackgroundColor(): Color =
    if (LocalAppSkin.current == null) MaterialTheme.colorScheme.background else Color.Transparent

@Composable
internal fun SkinBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val skin = LocalAppSkin.current
    val background = MaterialTheme.colorScheme.background
    Box(modifier.background(background)) {
        if (skin != null) {
            Image(painterResource(skin.drawable), contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            // Keep text readable in both light and dark color themes without changing their palettes.
            Box(Modifier.matchParentSize().background(background.copy(
                alpha = if (background.luminance() > 0.5f) 0.88f else 0.76f)))
        }
        content()
    }
}
