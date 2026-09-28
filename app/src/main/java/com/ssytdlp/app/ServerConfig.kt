package com.ssytdlp.app

import android.content.Context
import com.ssytdlp.app.core.AuthProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the server address the user typed in on this device. Unlike the account
 * session, this is not a secret, so it is kept in plain (non-encrypted) preferences.
 */
class ServerConfig(context: Context) {
    private val preferences = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
    private val mutableOrigin = MutableStateFlow(preferences.getString(KEY_ORIGIN, null))
    val origin = mutableOrigin.asStateFlow()

    /** Validates and saves [input], returning the normalized origin. A bare hostname is promoted to HTTPS. */
    fun set(input: String): String {
        val trimmed = input.trim()
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val normalized = AuthProtocol.normalizeOrigin(withScheme)
        check(preferences.edit().putString(KEY_ORIGIN, normalized).commit()) { "Unable to save the server address on this device." }
        mutableOrigin.value = normalized
        return normalized
    }

    fun clear() {
        check(preferences.edit().remove(KEY_ORIGIN).commit()) { "Unable to reset the server address on this device." }
        mutableOrigin.value = null
    }

    private companion object { const val KEY_ORIGIN = "origin" }
}
