package com.voxapps.hub.domain.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process signal from [PairingHceService] (a system-managed service Android instantiates from the
 * manifest — it has no reference to any open Activity/Screen) to whichever screen is currently
 * observing, so the *foreground* side of a pairing tap can do the things only an Activity can do:
 * request Bluetooth discoverability and show pairing progress. Same-process only; nothing here
 * crosses an IPC boundary.
 */
object PairingEvents {
    private val _events = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun emit(event: PairingEvent) {
        _events.tryEmit(event)
    }
}

sealed interface PairingEvent {
    /** [PairingHceService] just received and stored a new peer — the card side of a tap. */
    data class ReceivedAsServer(val peer: PairedPeer) : PairingEvent
}
