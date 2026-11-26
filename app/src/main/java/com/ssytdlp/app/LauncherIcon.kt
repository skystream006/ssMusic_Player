package com.ssytdlp.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ssytdlp.app.core.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val Preferences.isDarkAppearance: Boolean
    get() = mode == "dark" || (mode == null && effectiveTheme in listOf("midnight", "black"))

internal object LauncherIcon {
    private val themes = mapOf(
        "midnight" to "Midnight", "royal-purple" to "RoyalPurple", "gold" to "Gold",
        "green" to "Green", "pink" to "Pink", "black" to "Black"
    )
    private const val DEFAULT = "LauncherDefault"
    private val aliases = listOf(DEFAULT) + themes.values.flatMap { listOf("Launcher${it}Light", "Launcher${it}Dark") }
    private val mutableArtwork = MutableStateFlow<Int?>(null)
    val artworkChanges = mutableArtwork.asStateFlow()

    private fun alias(preferences: Preferences, waveAppearance: Boolean) = if (waveAppearance) DEFAULT else
        "Launcher${themes[preferences.effectiveTheme] ?: themes.getValue("green")}${if (preferences.isDarkAppearance) "Dark" else "Light"}"

    fun artwork(context: Context, preferences: Preferences, waveAppearance: Boolean): Int =
        context.packageManager.getActivityInfo(ComponentName(context.packageName,
            "${context.packageName}.${alias(preferences, waveAppearance)}"), PackageManager.MATCH_DISABLED_COMPONENTS).icon

    fun currentArtwork(context: Context): Int = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0
    )?.activityInfo?.icon ?: R.mipmap.ic_launcher

    fun update(context: Context, preferences: Preferences, waveAppearance: Boolean) {
        val selected = alias(preferences, waveAppearance)
        if (updateComponents(context, selected)) mutableArtwork.value = artwork(context, preferences, waveAppearance)
    }

    fun initializeCar(context: Context) {
        val launcher = requireNotNull(context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0
        )) { "No enabled launcher entry" }
        updateComponents(context, launcher.activityInfo.name.substringAfterLast('.'))
    }

    private fun updateComponents(context: Context, selected: String): Boolean {
        val manager = context.packageManager
        val entries = aliases.map { it to (it == DEFAULT) } +
            aliases.map { it.replace("Launcher", "CarIconService\$") to (it == DEFAULT) }
        val changes = entries.mapNotNull { (entry, defaultEnabled) ->
            val component = ComponentName(context.packageName, "${context.packageName}.$entry")
            val enabled = when (manager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> false
            }
            val desired = entry == selected || entry == selected.replace("Launcher", "CarIconService\$")
            if (enabled == desired) null else component to desired
        }
        if (changes.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(changes.map { (component, enabled) ->
                PackageManager.ComponentEnabledSetting(component, state(enabled), PackageManager.DONT_KILL_APP)
            })
        } else {
            // Keep a launchable entry throughout the non-atomic update on older Android versions.
            changes.sortedByDescending { it.second }.forEach { (component, enabled) ->
                manager.setComponentEnabledSetting(component, state(enabled), PackageManager.DONT_KILL_APP)
            }
        }
        return true
    }

    private fun state(enabled: Boolean) = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
