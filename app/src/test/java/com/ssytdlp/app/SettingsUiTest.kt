package com.ssytdlp.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import java.security.Provider
import java.security.Security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before fun setup() {
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    }

    @Test fun edgeLightingSwitchReflectsPreferenceAndReportsBothChanges() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { EdgeLightingSetting(enabled.value) { enabled.value = it } }
        }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOn().performClick()
        compose.runOnIdle { assertFalse(enabled.value) }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOff().performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOn()
    }

    @Test fun edgeLightingSwitchHonorsDisabledPreferenceInitially() {
        compose.setContent { MaterialTheme { EdgeLightingSetting(false) {} } }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOff()
    }

    @Test fun edgeLightingPreferenceDefaultsOnAndPersistsAcrossViewModels() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().remove("edge_lighting").commit()
        // No session is saved here; an empty JVM keystore permits application initialization.
        val provider = object : Provider("SettingsTestKeyStore", 1.0, "Empty test session keystore") {}
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val models = ViewModelStore()
        try {
            val application = MusicApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
            application.onCreate()
            lateinit var initial: MusicViewModel
            compose.runOnUiThread {
                initial = MusicViewModel(application)
                models.put("initial", initial)
            }
            assertTrue(initial.edgeLightingEnabled)
            compose.setContent {
                MaterialTheme { EdgeLightingSetting(initial.edgeLightingEnabled, initial::chooseEdgeLighting) }
            }
            compose.onNodeWithContentDescription("Edge lighting").assertIsOn().performClick()
            compose.onNodeWithContentDescription("Edge lighting").assertIsOff()
            assertFalse(initial.edgeLightingEnabled)
            assertFalse(preferences.getBoolean("edge_lighting", true))
            compose.runOnUiThread {
                val recreated = MusicViewModel(application)
                models.put("recreated", recreated)
                assertFalse(recreated.edgeLightingEnabled)
                recreated.chooseEdgeLighting(true)
                assertTrue(preferences.getBoolean("edge_lighting", false))
                val enabledAgain = MusicViewModel(application)
                models.put("enabledAgain", enabledAgain)
                assertTrue(enabledAgain.edgeLightingEnabled)
            }
        } finally {
            compose.runOnUiThread { models.clear() }
            Security.removeProvider(provider.name)
            preferences.edit().remove("edge_lighting").commit()
        }
    }

    @Test fun deviceStartsCollapsedOnlyWhenBothRequirementsAreSatisfied() {
        setPermissions(notifications = true, unrestrictedBattery = true)
        showDeviceSettings()
        compose.onNodeWithText("Device").assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Battery and background activity").assertIsDisplayed()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
    }

    @Test fun deviceStartsExpandedWhenNotificationsAreDisabled() {
        setPermissions(notifications = false, unrestrictedBattery = true)
        showDeviceSettings()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test fun deviceStartsExpandedWhenBatteryIsRestricted() {
        setPermissions(notifications = true, unrestrictedBattery = false)
        showDeviceSettings()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test fun deviceStartsExpandedWhenNeitherRequirementIsSatisfied() {
        setPermissions(notifications = false, unrestrictedBattery = false)
        showDeviceSettings()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test fun deviceRefreshesBothPermissionsWhenResumingFromOsSettings() {
        setPermissions(notifications = false, unrestrictedBattery = false)
        showDeviceSettings()
        compose.onNodeWithText("Notifications").performClick()
        val notificationIntent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notificationIntent.action)
        assertEquals(compose.activity.packageName, notificationIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        setPermissions(notifications = true, unrestrictedBattery = false)
        resume()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Battery and background activity").performClick()
        val batteryIntent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, batteryIntent.action)
        assertEquals("package:${compose.activity.packageName}", batteryIntent.dataString)
        setPermissions(notifications = true, unrestrictedBattery = true)
        resume()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        setPermissions(notifications = false, unrestrictedBattery = true)
        resume()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test fun manualExpansionSurvivesResumeAndStateRestoration() {
        setPermissions(notifications = true, unrestrictedBattery = true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { DeviceSettings() }
            }
        }
        compose.onNodeWithText("Device").performClick()
        resume()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test fun manualCollapseIsNotOverriddenByPermissionChanges() {
        setPermissions(notifications = false, unrestrictedBattery = false)
        showDeviceSettings()
        compose.onNodeWithText("Device").performClick()
        setPermissions(notifications = true, unrestrictedBattery = true)
        resume()
        setPermissions(notifications = false, unrestrictedBattery = true)
        resume()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithText("Device").performClick()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
    }

    private fun setPermissions(notifications: Boolean, unrestrictedBattery: Boolean) {
        shadowOf(compose.activity.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(notifications)
        shadowOf(compose.activity.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(compose.activity.packageName, unrestrictedBattery)
    }

    private fun resume() {
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }

    private fun showDeviceSettings() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { DeviceSettings() }
            }
        }
    }
}
