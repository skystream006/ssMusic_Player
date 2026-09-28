package com.ssytdlp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val model: MusicViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.message("Notifications are off. Music playback is still available.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent { MusicApp(model, ::requestNotifications) }
        if (savedInstanceState == null) consumeCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallback(intent)
    }

    private fun consumeCallback(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(model::callback)
        intent.data = null
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (settings.getBoolean("notification_prompted", false)) return
        settings.edit().putBoolean("notification_prompted", true).apply()
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}