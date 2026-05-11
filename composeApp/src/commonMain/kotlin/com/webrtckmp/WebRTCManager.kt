package com.webrtckmp

/**
 * Platform context carrier.
 *
 * On Android this is aliased to android.content.Context so the WebRTC factory
 * can initialise its native libraries.  On iOS no context is needed, so the
 * actual class is an empty placeholder.
 */
expect class PlatformContext()

/**
 * Callbacks the platform WebRTC implementation fires back into shared code.
 */
data class WebRTCCallbacks(
    /** A new local ICE candidate is ready to be forwarded to the remote peer. */
    val onLocalIceCandidate: (candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit,
    /** The PeerConnection's overall connectivity state changed. */
    val onConnectionStateChange: (state: String) -> Unit,
    /** Offer or answer SDP was created by the local side. */
    val onLocalSdpCreated: (sdp: String, type: String) -> Unit,
    /** An error happened inside the native WebRTC layer. */
    val onError: (message: String) -> Unit,
)

/**
 * Platform-specific WebRTC peer connection manager.
 *
 * expect/actual means:
 *   • The interface is declared here in commonMain (one source of truth).
 *   • Each platform provides the real implementation in its own source set.
 *
 * Notice the constructor takes PlatformContext — on Android it carries the
 * Activity/Application context; on iOS the actual class ignores it.
 */
expect class WebRTCManager(
    context: PlatformContext,
    callbacks: WebRTCCallbacks,
) {
    /** Initialise the PeerConnectionFactory and create a PeerConnection. */
    fun initializePeerConnection()

    /** Create an SDP offer (we are the caller). Result fires onLocalSdpCreated. */
    fun createOffer()

    /** Create an SDP answer after we received the remote offer. Result fires onLocalSdpCreated. */
    fun createAnswer()

    /** Apply the SDP we received from the remote peer. */
    fun setRemoteDescription(sdp: String, type: String)

    /** Forward an ICE candidate received from the remote peer via signaling. */
    fun addRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)

    /** Start capturing local camera + mic. */
    fun startLocalCapture()

    /** Attach a platform video renderer to the local video track. */
    fun attachLocalRenderer(renderer: Any)

    /** Attach a platform video renderer to the remote video track. */
    fun attachRemoteRenderer(renderer: Any)

    /** Tear everything down cleanly. */
    fun dispose()
}
