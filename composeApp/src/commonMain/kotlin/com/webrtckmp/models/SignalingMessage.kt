package com.webrtckmp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every WebSocket frame between client and signaling server is one of these.
 * Using a sealed class keeps the type system honest — you can't send an
 * unknown message type by mistake.
 *
 * The @SerialName annotation controls the "type" string sent over the wire.
 */
@Serializable
sealed class SignalingMessage {

    // ── Client → Server ────────────────────────────────────────────────────

    @Serializable
    @SerialName("join")
    data class Join(
        val roomId: String,
        val userId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("offer")
    data class Offer(
        val sdp: String,
        val to: String,
        val from: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(
        val sdp: String,
        val to: String,
        val from: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("ice_candidate")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val to: String,
        val from: String,
    ) : SignalingMessage()

    // ── Server → Client ────────────────────────────────────────────────────

    /** Server tells us we successfully joined and whether a peer is already waiting. */
    @Serializable
    @SerialName("room_joined")
    data class RoomJoined(
        val roomId: String,
        val yourId: String,
        val peerId: String?,        // null if we are first in the room
    ) : SignalingMessage()

    /** Server tells existing room member that a new peer has arrived. */
    @Serializable
    @SerialName("peer_joined")
    data class PeerJoined(
        val peerId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("peer_left")
    data class PeerLeft(
        val peerId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
    ) : SignalingMessage()
}
