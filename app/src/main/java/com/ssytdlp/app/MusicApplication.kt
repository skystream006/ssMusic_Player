package com.ssytdlp.app

import android.app.Application
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.PendingLogin

class MusicApplication : Application() {
    lateinit var sessions: SessionStore
        private set
    lateinit var api: ServerApi
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLog.initialize(this)
        DebugLog.event(DebugEvent.APP_STARTED)
        sessions = SessionStore(this)
        if (sessions.account.value?.origin?.let { it != AppServer.origin } == true) sessions.clear()
        if (sessions.pending?.origin?.let { it != AppServer.origin } == true) sessions.pending = null
        api = ServerApi(sessions)
    }
}

object AppServer {
    val origin: String = AuthProtocol.normalizeOrigin(BuildConfig.PASSKEY_ORIGIN)

    fun beginLogin(): PendingLogin = AuthProtocol.begin(origin)

    fun acceptCallback(uri: String, pending: PendingLogin?) = AuthProtocol.acceptCallback(uri, pending.also {
        require(it == null || it.origin == origin) { "Start a new sign-in on the primary server." }
    })
}
