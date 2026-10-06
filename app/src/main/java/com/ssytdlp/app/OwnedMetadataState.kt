package com.ssytdlp.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ssytdlp.app.core.SongMetadata

/** Checks ownership on every read, even before the account-flow collector can run. */
internal class OwnedMetadataState(private val currentOwner: () -> MetadataOwner?) : MutableState<SongMetadata?> {
    private data class Value(val owner: MetadataOwner?, val metadata: SongMetadata?)
    private var stored by mutableStateOf<Value?>(null)

    override var value: SongMetadata?
        get() = stored?.takeIf { it.owner == currentOwner() }?.metadata
        set(value) { publish(currentOwner(), value) }

    fun publish(owner: MetadataOwner?, metadata: SongMetadata?) {
        stored = Value(owner, metadata)
    }

    override fun component1(): SongMetadata? = value
    override fun component2(): (SongMetadata?) -> Unit = { value = it }
}
