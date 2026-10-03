package com.ssytdlp.app

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.PendingLogin

class MusicApplication : Application() {
    val uiActivity = UiActivityGate()
    val audioLevels = AudioLevelMeter().apply { enabled = false }
    private val audioLevelObservers = mutableSetOf<Any>()
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "edge_lighting") updateMeter()
    }
    lateinit var sessions: SessionStore
        private set
    lateinit var serverConfig: ServerConfig
        private set
    lateinit var api: ServerApi
        private set

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                uiActivity.activityResumed(activity)
                updateMeter()
            }
            override fun onActivityPaused(activity: Activity) {
                uiActivity.activityPaused(activity)
                updateMeter()
            }
            override fun onActivityDestroyed(activity: Activity) {
                uiActivity.activityPaused(activity)
                updateMeter()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        DebugLog.initialize(this)
        DebugLog.event(DebugEvent.APP_STARTED)
        sessions = SessionStore(this)
        serverConfig = ServerConfig(this)
        val accountOrigin = sessions.account.value?.origin
        when {
            // Upgrading from a build with a fixed server: adopt the signed-in account's origin.
            serverConfig.origin.value == null && accountOrigin != null -> serverConfig.set(accountOrigin)
            accountOrigin != null && accountOrigin != serverConfig.origin.value -> sessions.clear()
        }
        if (sessions.pending?.origin?.let { it != serverConfig.origin.value } == true) sessions.pending = null
        api = ServerApi(sessions, uiActivity)
    }

    internal fun observeAudioLevels(): () -> Unit {
        val observer = Any()
        audioLevelObservers.add(observer)
        updateMeter()
        return {
            audioLevelObservers.remove(observer)
            updateMeter()
        }
    }

    private fun updateMeter() {
        audioLevels.enabled = uiActivity.resumed.value &&
            (audioLevelObservers.isNotEmpty() ||
                getSharedPreferences("settings", MODE_PRIVATE).getBoolean("edge_lighting", true))
    }
}

object AppServer {
    fun beginLogin(origin: String): PendingLogin = AuthProtocol.begin(origin)

    fun acceptCallback(uri: String, pending: PendingLogin?, origin: String) = AuthProtocol.acceptCallback(uri, pending.also {
        require(it == null || it.origin == origin) { "Start a new sign-in on the configured server." }
    })
}
