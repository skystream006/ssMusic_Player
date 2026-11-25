package com.ssytdlp.app

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private var voiceBrowser: MediaBrowser? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.message("Notifications are off. Music playback is still available.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent { MusicApp(model, ::requestNotifications) }
        if (savedInstanceState == null) consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(model::callback)
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> playVoiceSearch(intent)
        }
        intent.data = null
    }

    private fun playVoiceSearch(intent: Intent) {
        voiceBrowser?.disconnect()
        voiceBrowser = null
        val owner = model.sessions.account.value?.let(::MetadataOwner)
            ?: return model.message("When parked, sign in on your phone before playing music.")
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val hints = Bundle().apply {
            for (key in listOf(MediaStore.EXTRA_MEDIA_TITLE, MediaStore.EXTRA_MEDIA_ARTIST,
                MediaStore.EXTRA_MEDIA_ALBUM, MediaStore.EXTRA_MEDIA_PLAYLIST)) {
                intent.getStringExtra(key)?.let { putString(key, it) }
            }
        }
        // Older assistants launch an activity instead of calling the media session directly.
        // Forward only search text through the same atomic play-from-search path as a dashboard.
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(this, ComponentName(this, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (voiceBrowser !== browser) return
                if (model.sessions.account.value?.let(::MetadataOwner) == owner) {
                    MediaController(this@MainActivity, browser.sessionToken).transportControls.playFromSearch(query, hints)
                } else model.message("The playback account changed. Try the voice request again.")
                browser.disconnect()
                voiceBrowser = null
            }
            override fun onConnectionFailed() {
                if (voiceBrowser !== browser) return
                browser.disconnect()
                voiceBrowser = null
                model.message("Could not connect to playback. Try the voice request again.")
            }
            override fun onConnectionSuspended() = onConnectionFailed()
        }, null)
        voiceBrowser = browser
        browser.connect()
    }

    override fun onDestroy() {
        voiceBrowser?.disconnect()
        voiceBrowser = null
        super.onDestroy()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (settings.getBoolean("notification_prompted", false)) return
        settings.edit().putBoolean("notification_prompted", true).apply()
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}