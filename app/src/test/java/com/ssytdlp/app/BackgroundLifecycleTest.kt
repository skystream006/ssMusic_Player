package com.ssytdlp.app

import android.app.Activity
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundLifecycleTest {
    @Test fun `automatic update check remains deferred during background startup`() {
        val app = ApplicationProvider.getApplicationContext<MusicApplication>()
        val store = ViewModelStore()
        val updater = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AppUpdater::class.java]
        try {
            updater.check(automatic = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(updater.state.value.busy)
            assertFalse(ReflectionHelpers.getField<Lazy<OkHttpClient>>(updater, "client\$delegate").isInitialized())
            assertNull(updater.notification.value)
        } finally { store.clear() }
    }

    @Test fun `application startup and merely started activity do not enable UI work`() {
        val app = ApplicationProvider.getApplicationContext<MusicApplication>()
        val settings = app.getSharedPreferences("settings", 0)
        settings.edit().putBoolean("edge_lighting", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.uiActivity.resumed.value)
        assertFalse(app.audioLevels.enabled)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start()
        assertFalse(app.uiActivity.resumed.value)
        assertFalse(app.audioLevels.enabled)
        activity.resume()
        assertTrue(app.uiActivity.resumed.value)
        assertTrue(app.audioLevels.enabled)
        activity.pause()
        assertFalse(app.uiActivity.resumed.value)
        assertFalse(app.audioLevels.enabled)
        activity.stop().destroy()
    }

    @Test fun `lighting preference changes enable analysis only with a resumed activity`() {
        val app = ApplicationProvider.getApplicationContext<MusicApplication>()
        val settings = app.getSharedPreferences("settings", 0)
        settings.edit().putBoolean("edge_lighting", false).commit()
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertTrue(app.uiActivity.resumed.value)
        assertFalse(app.audioLevels.enabled)
        settings.edit().putBoolean("edge_lighting", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.audioLevels.enabled)
        settings.edit().putBoolean("edge_lighting", false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.audioLevels.enabled)
        activity.pause().stop().destroy()
        settings.edit().putBoolean("edge_lighting", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.audioLevels.enabled)
    }
}
