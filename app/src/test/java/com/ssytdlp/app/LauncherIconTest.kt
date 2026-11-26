package com.ssytdlp.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], application = Application::class)
class LauncherIconTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun eachColorAndModeSelectsExactlyOneLauncherWithMatchingArtwork() {
        val variants = listOf(
            Triple("midnight", "Midnight", R.mipmap.ic_launcher_midnight_light to R.mipmap.ic_launcher_midnight_dark),
            Triple("royal-purple", "RoyalPurple", R.mipmap.ic_launcher_royal_purple_light to R.mipmap.ic_launcher_royal_purple_dark),
            Triple("gold", "Gold", R.mipmap.ic_launcher_gold_light to R.mipmap.ic_launcher_gold_dark),
            Triple("green", "Green", R.mipmap.ic_launcher_green_light to R.mipmap.ic_launcher_green_dark),
            Triple("pink", "Pink", R.mipmap.ic_launcher_pink_light to R.mipmap.ic_launcher_pink_dark),
            Triple("black", "Black", R.mipmap.ic_launcher_black_light to R.mipmap.ic_launcher_black_dark)
        )
        variants.forEach { (theme, alias, artwork) ->
            listOf("light", "dark").forEach { mode ->
                val preferences = Preferences(theme, mode)
                LauncherIcon.update(context, preferences, waveAppearance = false)
                val expected = "Launcher$alias${if (mode == "dark") "Dark" else "Light"}"
                assertLauncher(expected, if (mode == "dark") artwork.second else artwork.first)
                assertEquals(LauncherIcon.currentArtwork(context), LauncherIcon.artwork(context, preferences, false))
                LauncherIcon.update(context, preferences, waveAppearance = false)
                assertLauncher(expected, if (mode == "dark") artwork.second else artwork.first)
            }
        }
        val main = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertTrue(main.enabled)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java)))
        val callback = Intent(Intent.ACTION_VIEW, Uri.parse("com.ssytdlp.app://callback"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        assertEquals(MainActivity::class.java.name,
            context.packageManager.resolveActivity(callback, 0)!!.activityInfo.name)
    }

    @Test fun legacyAndImplicitModesMatchTheUiAndBlueWaveRestoresOriginalArtwork() {
        LauncherIcon.update(context, Preferences("midnight"), waveAppearance = false)
        assertLauncher("LauncherMidnightDark", R.mipmap.ic_launcher_midnight_dark)
        LauncherIcon.update(context, Preferences("black"), waveAppearance = false)
        assertLauncher("LauncherBlackDark", R.mipmap.ic_launcher_black_dark)
        LauncherIcon.update(context, Preferences("light"), waveAppearance = false)
        assertLauncher("LauncherGreenLight", R.mipmap.ic_launcher_green_light)
        LauncherIcon.update(context, Preferences("light", "dark"), waveAppearance = false)
        assertLauncher("LauncherGreenDark", R.mipmap.ic_launcher_green_dark)
        LauncherIcon.update(context, Preferences("pink", "light"), waveAppearance = true)
        assertLauncher("LauncherDefault", R.mipmap.ic_launcher)
        assertEquals(R.mipmap.ic_launcher, LauncherIcon.artwork(context, Preferences("pink", "light"), true))
    }

    @Test fun coldCarStartupRepairsTheCarEntryFromTheRetainedPhoneLauncher() {
        LauncherIcon.update(context, Preferences("gold", "dark"), waveAppearance = false)
        val manager = context.packageManager
        manager.setComponentEnabledSetting(ComponentName(context, CarIconService.GoldDark::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        manager.setComponentEnabledSetting(ComponentName(context, CarIconService.Default::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        LauncherIcon.initializeCar(context)
        assertLauncher("LauncherGoldDark", R.mipmap.ic_launcher_gold_dark)
    }

    private fun assertLauncher(alias: String, artwork: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val launchers = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(1, launchers.size)
        val info = launchers.single().activityInfo
        assertEquals("${context.packageName}.$alias", info.name)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
        assertEquals(artwork, info.icon)
        val cars = context.packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName), 0)
        assertEquals(1, cars.size)
        val car = cars.single().serviceInfo
        assertEquals("${context.packageName}.${alias.replace("Launcher", "CarIconService\$")}", car.name)
        val carIcon = context.getDrawable(car.icon)!!
        assertTrue(carIcon is AdaptiveIconDrawable)
        val background = (carIcon as AdaptiveIconDrawable).background
        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        background.setBounds(0, 0, 108, 108)
        background.draw(Canvas(bitmap))
        listOf(0 to 54, 107 to 54, 54 to 0, 54 to 107).forEach { (x, y) ->
            assertEquals(255, Color.alpha(bitmap.getPixel(x, y)))
        }
        assertEquals(if (alias.endsWith("Light")) Color.rgb(246, 248, 246) else Color.rgb(8, 10, 16),
            bitmap.getPixel(0, 54))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(
            ComponentName(context, PlaybackService::class.java)))
    }
}
