package com.ssytdlp.app

import android.content.Context
import androidx.media3.common.Player
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class SavedPlayback(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF
)

class PlaybackStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    @Synchronized fun setAccount(account: Account?) {
        val owner = account?.playbackOwner()
        if (owner == null || preferences.getString("owner", null) != owner) {
            preferences.edit().clear().putString("owner", owner).apply()
        }
    }

    @Synchronized fun selectedLibrary(account: Account): String? =
        if (owns(account)) preferences.getString("selected_library", null) else null

    @Synchronized fun saveSelectedLibrary(account: Account, selectedId: String?) {
        if (owns(account)) preferences.edit().putString("selected_library", selectedId).apply()
    }

    @Synchronized fun playback(account: Account): SavedPlayback? {
        if (!owns(account)) return null
        val saved = preferences.getString("snapshot", null) ?: return null
        return runCatching { ApiJson.decodeFromString<SavedPlayback>(saved) }.getOrElse {
            preferences.edit().remove("snapshot").apply()
            null
        }
    }

    @Synchronized fun savePlayback(account: Account, snapshot: SavedPlayback, synchronous: Boolean = false) {
        if (!owns(account)) return
        val encoded = ApiJson.encodeToString(snapshot)
        if (!synchronous && preferences.getString("snapshot", null) == encoded) return
        val edit = preferences.edit().putString("snapshot", encoded)
        if (synchronous) edit.commit() else edit.apply()
    }

    private fun owns(account: Account) = preferences.getString("owner", null) == account.playbackOwner()
    private fun Account.playbackOwner() = ApiJson.encodeToString(listOf(origin, user.id))
}
