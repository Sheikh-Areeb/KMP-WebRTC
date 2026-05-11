package com.webrtckmp

import com.webrtckmp.models.CallState
import com.webrtckmp.models.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Shared ViewModel — lives in commonMain so both Android and iOS share the
 * exact same call-management logic.
 *
 * The ViewModel wires together two subsystems:
 *   1. SignalingClient  — sends/receives JSON messages over WebSocket
 *   2. WebRTCManager    — manages the native PeerConnection
 *
 * Call flow (happy path):
 *   joinRoom()
 *     → SignalingClient sends Join
 *     → Server replies RoomJoined
 *       • If peerId == null  → we are first; wait for PeerJoined
 *       • If peerId != null  → peer is already there; we send Offer
 *     → The peer who received Offer sends Answer
 *     → Both sides exchange IceCandidates
 *     → WebRTC connectivity state reaches "connected"
 *
 * @param context   Platform context (Activity on Android, stub on iOS)
 * @param serverUrl ws:// URL of the signaling server
 */
@OptIn(ExperimentalUuidApi::class)
class CallViewModel(
    private val context: PlatformContext,
    private val serverUrl: String = "ws://10.0.2.2:3000",  // 10.0.2.2 is localhost for Android emulator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Stable per-session user ID
    val userId: String = Uuid.random().toString().take(8)

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var remotePeerId: String? = null
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var signalingCollectJob: Job? = null

    // ── Public API ─────────────────────────────────────────────────────────

    fun joinRoom(roomId: String) {
        // Idempotency: tear down any previous session so re-joining (after a
        // failed attempt or back-from-Ended without going through reset()) does
        // not leak the old WebRTCManager / SignalingClient / collector job.
        cleanup()
        _callState.value = CallState.Connecting
        println("[ViewModel] joinRoom: roomId=$roomId, userId=$userId, serverUrl=$serverUrl")

        val rtcCallbacks = WebRTCCallbacks(
            onLocalIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
                val peerId = remotePeerId ?: return@WebRTCCallbacks
                signalingClient?.send(
                    SignalingMessage.IceCandidate(
                        candidate = candidate,
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex,
                        to = peerId,
                        from = userId,
                    )
                )
            },
            onConnectionStateChange = { state ->
                println("CallViewModel: PeerConnection state → $state")
                when (state) {
                    "connected" -> {
                        val peer = remotePeerId ?: "unknown"
                        _callState.value = CallState.InCall(peer)
                    }
                    "failed", "disconnected", "closed" -> {
                        _callState.value = CallState.Ended
                    }
                }
            },
            onLocalSdpCreated = { sdp, type ->
                val peerId = remotePeerId ?: return@WebRTCCallbacks
                val msg = if (type == "offer") {
                    SignalingMessage.Offer(sdp = sdp, to = peerId, from = userId)
                } else {
                    SignalingMessage.Answer(sdp = sdp, to = peerId, from = userId)
                }
                signalingClient?.send(msg)
            },
            onError = { msg ->
                _callState.value = CallState.Failed(msg)
            },
        )

        val rtc = WebRTCManager(context, rtcCallbacks)
        webRTCManager = rtc
        println("[ViewModel] before initializePeerConnection")
        rtc.initializePeerConnection()
        println("[ViewModel] before startLocalCapture")
        rtc.startLocalCapture()
        println("[ViewModel] WebRTC ready, creating signaling client")

        val client = SignalingClient(serverUrl)
        signalingClient = client

        // Start listening to incoming signaling messages before we connect.
        // We track the Job so cleanup() can cancel it — otherwise a re-join
        // would leak a dead collector on the old SignalingClient's flow.
        signalingCollectJob = scope.launch {
            client.incomingMessages.collect { message ->
                handleSignalingMessage(message)
            }
        }

        client.connect(
            onConnected = {
                client.send(SignalingMessage.Join(roomId = roomId, userId = userId))
            },
            onDisconnected = {
                // Only show the error when the WebSocket dropped *while we
                // were setting up* — if the user already hung up (Ended) or
                // reset to lobby (Idle), or the call is already running
                // (InCall, no longer needs signaling), say nothing.
                when (_callState.value) {
                    is CallState.Connecting,
                    is CallState.WaitingForPeer,
                    is CallState.Negotiating -> {
                        _callState.value = CallState.Failed("Disconnected from signaling server")
                    }
                    else -> Unit
                }
            }
        )
    }

    fun hangUp() {
        // dispose() triggers the PeerConnection "closed" callback which also
        // sets state → Ended; that's harmless (MutableStateFlow dedupes equal
        // values) but we set it explicitly here so the UI updates even if the
        // callback is delayed or skipped.
        cleanup()
        _callState.value = CallState.Ended
    }

    // Called from EndedScreen / FailedScreen to return to the lobby.
    // Safe to call even if hangUp() already ran (all ops are null-safe).
    fun reset() {
        cleanup()
        _callState.value = CallState.Idle
    }

    private fun cleanup() {
        signalingCollectJob?.cancel()
        signalingCollectJob = null
        signalingClient?.disconnect()
        webRTCManager?.dispose()
        signalingClient = null
        webRTCManager = null
        remotePeerId = null
    }

    fun attachRenderers(localRenderer: Any, remoteRenderer: Any) {
        webRTCManager?.attachLocalRenderer(localRenderer)
        webRTCManager?.attachRemoteRenderer(remoteRenderer)
    }

    // ── Signaling message handler ──────────────────────────────────────────

    private fun handleSignalingMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.RoomJoined -> {
                if (message.peerId != null) {
                    // A peer is already in the room — we are the late joiner, so we call them
                    remotePeerId = message.peerId
                    _callState.value = CallState.Negotiating(message.peerId)
                    webRTCManager?.createOffer()
                } else {
                    // We are first — sit tight until PeerJoined arrives
                    _callState.value = CallState.WaitingForPeer
                }
            }

            is SignalingMessage.PeerJoined -> {
                // We were waiting; the new peer will send us an Offer shortly
                remotePeerId = message.peerId
                _callState.value = CallState.Negotiating(message.peerId)
            }

            is SignalingMessage.Offer -> {
                webRTCManager?.setRemoteDescription(message.sdp, "offer")
                webRTCManager?.createAnswer()
            }

            is SignalingMessage.Answer -> {
                webRTCManager?.setRemoteDescription(message.sdp, "answer")
            }

            is SignalingMessage.IceCandidate -> {
                webRTCManager?.addRemoteIceCandidate(
                    message.candidate,
                    message.sdpMid,
                    message.sdpMLineIndex,
                )
            }

            is SignalingMessage.PeerLeft -> {
                _callState.value = CallState.Ended
            }

            is SignalingMessage.Error -> {
                _callState.value = CallState.Failed(message.message)
            }

            // Join is client→server only; if it ever shows up here something
            // is wrong with the server. Listing it keeps `when` exhaustive,
            // so adding a new SignalingMessage type triggers a compile error.
            is SignalingMessage.Join -> Unit
        }
    }
}
