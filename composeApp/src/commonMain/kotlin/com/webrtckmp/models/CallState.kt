package com.webrtckmp.models

/**
 * The call lifecycle as a sealed class.
 * The UI observes this single value and renders accordingly.
 */
sealed class CallState {
    /** App just opened, no room joined yet. */
    data object Idle : CallState()

    /** We sent a Join and are waiting for the server's RoomJoined reply. */
    data object Connecting : CallState()

    /** We are in the room but no peer is there yet; we are the "host". */
    data object WaitingForPeer : CallState()

    /** A peer joined and we are exchanging SDP / ICE (the negotiation phase). */
    data class Negotiating(val peerId: String) : CallState()

    /** ICE completed; audio/video is flowing. */
    data class InCall(val peerId: String) : CallState()

    /** The call ended (peer left or we hung up). */
    data object Ended : CallState()

    /** Something went wrong. */
    data class Failed(val reason: String) : CallState()
}
