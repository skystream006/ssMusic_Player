package com.ssytdlp.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.PendingLogin
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val mutableAccount = MutableStateFlow(read("account")?.let {
        runCatching { ApiJson.decodeFromString<Account>(it) }.getOrNull()
    }?.takeIf { runCatching { Instant.parse(it.session.expiresAt).isAfter(Instant.now()) }.getOrDefault(false) })
    val account = mutableAccount.asStateFlow()
    val playback = PlaybackStore(context).apply { setAccount(mutableAccount.value) }

    var pending: PendingLogin?
        @Synchronized get() = read("pending")?.let { runCatching { ApiJson.decodeFromString<PendingLogin>(it) }.getOrNull() }
        @Synchronized set(value) { write("pending", value?.let { ApiJson.encodeToString(it) }) }

    @Synchronized fun save(account: Account) {
        write("account", ApiJson.encodeToString(account))
        pending = null
        playback.setAccount(account)
        mutableAccount.value = account
    }

    @Synchronized fun clear(expectedToken: String? = null) {
        if (expectedToken != null && mutableAccount.value?.session?.token != expectedToken) return
        write("account", null)
        pending = null
        playback.setAccount(null)
        mutableAccount.value = null
    }

    private fun key(): SecretKey = (keyStore.getKey("ssmusic.session", null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ssmusic.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()

    private fun read(name: String): String? {
        val encrypted = preferences.getString(name, null) ?: return null
        return runCatching {
            val parts = encrypted.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(name).commit()
            null
        }
    }

    private fun write(name: String, value: String?) {
        val encoded = value?.let {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        check(preferences.edit().putString(name, encoded).commit()) { "Unable to save the private session on this device." }
    }
}
