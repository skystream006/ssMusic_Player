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
    STARRY_CITY("Starry City Sunset", "A star-filled night above a city skyline at sunset", R.drawable.skin_starry_city),
    OCEAN_WAVE("Ocean Wave", "Whale, dolphin, and anchovies in a sunset wave", R.drawable.skin_ocean_wave),
    OCEAN_MOONLIGHT("Ocean Moonlight", "A bright moon reflected on the ocean horizon", R.drawable.skin_ocean_moonlight),
    GALAXY("Galaxy", "Three distant galaxies surrounded by stars", R.drawable.skin_galaxy),
    TROPICAL("Tropical", "A coconut palm overlooking a beach sunset", R.drawable.skin_tropical),
    MECHANICS("Mechanics", "Interlocking gears and intricate metal mechanisms", R.drawable.skin_mechanics),
    AURORA_BOREALIS("Aurora Borealis", "Luminous northern lights above snowy pines", R.drawable.skin_aurora_borealis),
    DESERT_DUNES("Desert Dunes", "Golden sand ridges beneath a hazy desert sun", R.drawable.skin_desert_dunes),
    BAMBOO_GROVE("Bamboo Grove", "Misty green bamboo surrounding a quiet forest path", R.drawable.skin_bamboo_grove),
    AUTUMN_RIVER("Autumn River", "Amber foliage along a winding reflective river", R.drawable.skin_autumn_river),
    ALPINE_DAWN("Alpine Dawn", "Snow-capped peaks reflected in a still mountain lake", R.drawable.skin_alpine_dawn),
    RAINY_WINDOW("Rainy Window", "Rain-speckled glass with softly glowing city lights", R.drawable.skin_rainy_window),
    VOLCANIC_EMBER("Volcanic Ember", "Glowing lava flowing through dark volcanic rock", R.drawable.skin_volcanic_ember),
    CRYSTAL_CAVERN("Crystal Cavern", "Luminous crystal facets in a violet underground cave", R.drawable.skin_crystal_cavern),
    PAPER_LANTERNS("Paper Lanterns", "Warm hanging lanterns above twilight rooftops", R.drawable.skin_paper_lanterns),
    SYNTHWAVE_GRID("Synthwave Grid", "A striped neon sun over a retro perspective grid", R.drawable.skin_synthwave_grid);

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
