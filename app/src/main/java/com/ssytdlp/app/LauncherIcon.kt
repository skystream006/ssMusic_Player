package com.ssytdlp.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ssytdlp.app.core.Preferences

internal val Preferences.isDarkAppearance: Boolean
    get() = mode == "dark" || (mode == null && effectiveTheme in listOf("midnight", "black"))

internal object LauncherIcon {
    private val themes = mapOf(
        "midnight" to "Midnight", "royal-purple" to "RoyalPurple", "gold" to "Gold",
        "green" to "Green", "pink" to "Pink", "black" to "Black"
    )
    private const val DEFAULT = "LauncherDefault"
    private val aliases = listOf(DEFAULT) + themes.values.flatMap { listOf("Launcher${it}Light", "Launcher${it}Dark") }

    fun update(context: Context, preferences: Preferences, waveAppearance: Boolean) {
        val selected = if (waveAppearance) DEFAULT else
            "Launcher${themes[preferences.effectiveTheme] ?: themes.getValue("green")}${if (preferences.isDarkAppearance) "Dark" else "Light"}"
        val manager = context.packageManager
        val changes = aliases.mapNotNull { alias ->
            val component = ComponentName(context.packageName, "${context.packageName}.$alias")
            val enabled = when (manager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == DEFAULT
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> false
            }
            val desired = alias == selected
            if (enabled == desired) null else component to desired
        }
        if (changes.isEmpty()) return
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
    }

    private fun state(enabled: Boolean) = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
