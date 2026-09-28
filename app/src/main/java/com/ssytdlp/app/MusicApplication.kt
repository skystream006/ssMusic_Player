package com.ssytdlp.app

import android.app.Application
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.PendingLogin

class MusicApplication : Application() {
    val audioLevels = AudioLevelMeter()
    lateinit var sessions: SessionStore
        private set
    lateinit var serverConfig: ServerConfig
        private set
    lateinit var api: ServerApi
        private set

    override fun onCreate() {
        super.onCreate()
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
        api = ServerApi(sessions)
    }
}

object AppServer {
    fun beginLogin(origin: String): PendingLogin = AuthProtocol.begin(origin)

    fun acceptCallback(uri: String, pending: PendingLogin?, origin: String) = AuthProtocol.acceptCallback(uri, pending.also {
        require(it == null || it.origin == origin) { "Start a new sign-in on the configured server." }
    })
}
