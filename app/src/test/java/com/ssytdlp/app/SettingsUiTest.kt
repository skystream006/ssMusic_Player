package com.ssytdlp.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.UserResponse
import java.security.Provider
import java.security.Security
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        compose.onNodeWithText("Circulating Waveform").assertIsSelected()
        compose.onNodeWithContentDescription("Edge lighting").assertIsOn().performClick()
        compose.runOnIdle { assertFalse(enabled.value) }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOff().performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOn()
    }

    @Test fun edgeLightingSwitchHonorsDisabledPreferenceInitially() {
        compose.setContent { MaterialTheme { EdgeLightingSetting(false) {} } }
        compose.onNodeWithContentDescription("Edge lighting").assertIsOff()
        EdgeLightingStyle.entries.forEach { compose.onNodeWithText(it.label).assertDoesNotExist() }
    }

    @Test fun edgeLightingChoicesExpandSelectAndRetainSelectionWhenToggled() {
        val enabled = mutableStateOf(false)
        val style = mutableStateOf(EdgeLightingStyle.OSCILLATION)
        compose.setContent {
            MaterialTheme {
                EdgeLightingSetting(enabled.value, style.value, { style.value = it }) { enabled.value = it }
            }
        }
        compose.onNodeWithContentDescription("Edge lighting").performClick()
        compose.onNodeWithText("Oscillation").assertIsSelected()
        EdgeLightingStyle.entries.forEach {
            compose.onNodeWithText(it.label).assertIsDisplayed().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(it, style.value) }
        }
        compose.onNodeWithContentDescription("Edge lighting").performClick()
        EdgeLightingStyle.entries.forEach { compose.onNodeWithText(it.label).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Edge lighting").performClick()
        compose.onNodeWithText("Circulating Waveform").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w800dp-h1100dp")
    fun edgeLightingChoicesAreHorizontalAndAlphabetized() {
        compose.setContent { MaterialTheme { EdgeLightingSetting(true) {} } }
        val choices = listOf("Audio waveform", "Circulating", "Circulating Waveform", "Oscillation", "Vibration").map {
            compose.onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        choices.zipWithNext().forEach { (first, second) ->
            assertEquals(first.top, second.top)
            assertTrue(first.right < second.left)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun edgeLightingChoicesWrapAndRemainSelectableWithLargeText() {
        val style = mutableStateOf(EdgeLightingStyle.OSCILLATION)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme { EdgeLightingSetting(true, style.value, { style.value = it }) {} }
            }
        }
        listOf("Audio waveform", "Circulating", "Circulating Waveform", "Oscillation", "Vibration").forEach {
            val option = compose.onNodeWithText(it).assertIsDisplayed()
            val bounds = option.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= 0.dp && bounds.right <= 320.dp)
            option.performClick().assertIsSelected()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h780dp")
    fun updateButtonsStayHorizontalWithLargeTextAndDownloadInProgress() {
        val models = ViewModelStore()
        lateinit var updater: AppUpdater
        compose.runOnUiThread {
            updater = AppUpdater(ApplicationProvider.getApplicationContext())
            models.put("updates", updater)
        }
        val state = ReflectionHelpers.getField<MutableStateFlow<UpdateState>>(updater, "mutableState")
        state.value = UpdateState(availableVersion = "1.0.999", busy = true, downloading = true, total = 1024)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    MaterialTheme { Box(Modifier.width(320.dp)) { UpdateSettings(updater) } }
                }
            }
            val check = compose.onNodeWithText("Check for updates").assertIsNotEnabled()
            val download = compose.onNodeWithText("Download and install").assertIsNotEnabled()
            val cancel = compose.onNodeWithText("Cancel").assertIsEnabled()
            val buttons = listOf(check, download, cancel).map { it.assertIsDisplayed().getUnclippedBoundsInRoot() }
            buttons.zipWithNext().forEach { (first, second) ->
                assertTrue(first.right < second.left)
                assertEquals(((first.top + first.bottom) / 2).value, ((second.top + second.bottom) / 2).value, 1f)
            }
            buttons.forEach { assertTrue(it.left >= 0.dp && it.right <= 320.dp) }
            compose.runOnIdle { state.value = state.value.copy(busy = false, downloading = false) }
            cancel.assertDoesNotExist()
            check.assertIsEnabled()
            download.assertIsEnabled().performClick()
            compose.onNodeWithText("Download and install 1.0.999?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            compose.runOnUiThread { models.clear() }
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h1600dp")
    fun settingsPlaceJobsAfterAppearanceAndCollapseBackupAndServerByDefault() {
        withSettingsModel { model ->
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            setPermissions(notifications = true, unrestrictedBattery = true)
            var jobsOpened = false
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MusicTheme { SettingsScreen(model, { _, _ -> }) { jobsOpened = true } }
                }
            }
            val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
            compose.onNodeWithText("Library backup").assert(collapsed)
            compose.onNodeWithText("Server").assert(collapsed)
            compose.onNodeWithText("Back up").assertDoesNotExist()
            compose.onNodeWithText("Sign out").assertDoesNotExist()
            val appearance = compose.onNodeWithText("Appearance").getUnclippedBoundsInRoot()
            val edge = compose.onNodeWithContentDescription("Edge lighting").getUnclippedBoundsInRoot()
            val jobs = compose.onNodeWithText("Jobs").getUnclippedBoundsInRoot()
            val backup = compose.onNodeWithText("Library backup").getUnclippedBoundsInRoot()
            assertTrue(appearance.bottom < edge.top && edge.bottom < jobs.top && jobs.bottom < backup.top)
            compose.onNodeWithText("Jobs").performClick()
            compose.runOnIdle { assertTrue(jobsOpened) }
            compose.onNodeWithText("Library backup").performClick()
            compose.onNodeWithText("Back up").assertIsDisplayed()
            compose.onNodeWithText("iTunes").performClick().assertIsSelected()
            compose.onNodeWithText("Library backup").performClick()
            compose.onNodeWithText("Back up").assertDoesNotExist()
            compose.onNodeWithText("Library backup").performClick()
            compose.onNodeWithText("iTunes").assertIsSelected()
            compose.onNodeWithText("Server").performScrollTo().performClick()
            compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("iTunes").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun sharedRoleHidesJobsAndLibraryBackupSettings() {
        withSettingsModel(role = "shared") { model ->
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            setPermissions(notifications = true, unrestrictedBattery = true)
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MusicTheme { SettingsScreen(model, { _, _ -> }) {} }
                }
            }
            compose.onNodeWithText("Jobs").assertDoesNotExist()
            compose.onNodeWithText("Library backup").assertDoesNotExist()
            compose.onNodeWithText("Device").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h1600dp")
    fun settingsRespondToRoleChangesWithoutReopeningTheScreen() {
        withSettingsModel(role = "admin") { model ->
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            setPermissions(notifications = true, unrestrictedBattery = true)
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MusicTheme { SettingsScreen(model, { _, _ -> }) {} }
                }
            }
            compose.onNodeWithText("Jobs").assertIsDisplayed()
            compose.onNodeWithText("Library backup").assertIsDisplayed()
            val account = ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount")
            compose.runOnIdle { account.value = account.value!!.let { it.copy(user = it.user.copy(role = "shared")) } }
            compose.onNodeWithText("Jobs").assertDoesNotExist()
            compose.onNodeWithText("Library backup").assertDoesNotExist()
            compose.onNodeWithText("Device").assertIsDisplayed()
            compose.runOnIdle { account.value = account.value!!.let { it.copy(user = it.user.copy(role = "user")) } }
            compose.onNodeWithText("Jobs").assertIsDisplayed()
            compose.onNodeWithText("Library backup").assertIsDisplayed()
        }
    }

    @Test fun edgeLightingPreferenceDefaultsOnAndPersistsAcrossViewModels() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().remove("edge_lighting").remove("edge_lighting_style").commit()
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
            assertEquals(EdgeLightingStyle.CIRCULATING_WAVEFORM, initial.edgeLightingStyle)
            compose.setContent {
                MaterialTheme {
                    EdgeLightingSetting(initial.edgeLightingEnabled, initial.edgeLightingStyle,
                        initial::chooseEdgeLightingStyle, initial::chooseEdgeLighting)
                }
            }
            compose.onNodeWithContentDescription("Edge lighting").assertIsOn()
            compose.onNodeWithText("Circulating Waveform").assertIsSelected()
            compose.onNodeWithText("Vibration").performClick()
            assertEquals(EdgeLightingStyle.VIBRATION, initial.edgeLightingStyle)
            compose.onNodeWithText("Circulating Waveform").performClick().assertIsSelected()
            assertEquals(EdgeLightingStyle.CIRCULATING_WAVEFORM, initial.edgeLightingStyle)
            assertEquals("CIRCULATING_WAVEFORM", preferences.getString("edge_lighting_style", null))
            compose.onNodeWithContentDescription("Edge lighting").assertIsOn().performClick()
            compose.onNodeWithContentDescription("Edge lighting").assertIsOff()
            assertFalse(initial.edgeLightingEnabled)
            assertFalse(preferences.getBoolean("edge_lighting", true))
            compose.runOnUiThread {
                val recreated = MusicViewModel(application)
                models.put("recreated", recreated)
                assertFalse(recreated.edgeLightingEnabled)
                assertEquals(EdgeLightingStyle.CIRCULATING_WAVEFORM, recreated.edgeLightingStyle)
                recreated.chooseEdgeLighting(true)
                assertTrue(preferences.getBoolean("edge_lighting", false))
                recreated.chooseEdgeLightingStyle(EdgeLightingStyle.AUDIO_WAVEFORM)
                val enabledAgain = MusicViewModel(application)
                models.put("enabledAgain", enabledAgain)
                assertTrue(enabledAgain.edgeLightingEnabled)
                assertEquals(EdgeLightingStyle.AUDIO_WAVEFORM, enabledAgain.edgeLightingStyle)
                preferences.edit().putString("edge_lighting_style", "unknown-style").commit()
                val unknownStyle = MusicViewModel(application)
                models.put("unknownStyle", unknownStyle)
                assertEquals(EdgeLightingStyle.CIRCULATING_WAVEFORM, unknownStyle.edgeLightingStyle)
            }
        } finally {
            compose.runOnUiThread { models.clear() }
            Security.removeProvider(provider.name)
            preferences.edit().remove("edge_lighting").remove("edge_lighting_style").commit()
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

    private fun withSettingsModel(role: String = "user", test: (MusicViewModel) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = object : Provider("SettingsLayoutTestKeyStore", 1.0, "Empty test session keystore") {}
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val models = ViewModelStore()
        try {
            val application = MusicApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
            application.onCreate()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val body = if (chain.request().url.encodedPath == "/api/auth/me")
                    ApiJson.encodeToString(UserResponse(application.sessions.account.value!!.user)) else "{}"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            ReflectionHelpers.setField(application, "api", ServerApi({ application.sessions.account.value }, {}, client))
            lateinit var model: MusicViewModel
            compose.runOnUiThread {
                model = MusicViewModel(application)
                models.put("settings", model)
                model.viewModelScope.cancel()
                ReflectionHelpers.getField<MutableStateFlow<Account?>>(model.sessions, "mutableAccount").value =
                    Account("https://music.example.com", User(name = "Preview", role = role), Session("test-session", "2099-01-01T00:00:00Z"))
            }
            test(model)
        } finally {
            compose.runOnUiThread { models.clear() }
            Security.removeProvider(provider.name)
        }
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
