@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.webrtckmp

import cocoapods.StreamWebRTC.RTCCameraVideoCapturer
import cocoapods.StreamWebRTC.RTCConfiguration
import cocoapods.StreamWebRTC.RTCDataChannel
import cocoapods.StreamWebRTC.RTCDefaultVideoDecoderFactory
import cocoapods.StreamWebRTC.RTCDefaultVideoEncoderFactory
import cocoapods.StreamWebRTC.RTCIceCandidate
import cocoapods.StreamWebRTC.RTCIceConnectionState
import cocoapods.StreamWebRTC.RTCIceGatheringState
import cocoapods.StreamWebRTC.RTCIceServer
import cocoapods.StreamWebRTC.RTCMediaConstraints
import cocoapods.StreamWebRTC.RTCMediaStream
import cocoapods.StreamWebRTC.RTCPeerConnection
import cocoapods.StreamWebRTC.RTCPeerConnectionDelegateProtocol
import cocoapods.StreamWebRTC.RTCPeerConnectionFactory
import cocoapods.StreamWebRTC.RTCPeerConnectionState
import cocoapods.StreamWebRTC.RTCRtpTransceiver
import cocoapods.StreamWebRTC.RTCSdpSemantics
import cocoapods.StreamWebRTC.RTCSdpType
import cocoapods.StreamWebRTC.RTCSessionDescription
import cocoapods.StreamWebRTC.RTCSignalingState
import cocoapods.StreamWebRTC.RTCVideoRendererProtocol
import cocoapods.StreamWebRTC.RTCVideoTrack
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceFormat
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.darwin.NSObject

// ── PlatformContext ────────────────────────────────────────────────────────
// iOS needs no context object.  The actual class is a zero-size placeholder.

actual class PlatformContext actual constructor()

// ── WebRTCManager ─────────────────────────────────────────────────────────

actual class WebRTCManager actual constructor(
    private val context: PlatformContext,
    private val callbacks: WebRTCCallbacks,
) {
    private var factory: RTCPeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null

    private var localVideoTrack: RTCVideoTrack? = null
    private var remoteVideoTrack: RTCVideoTrack? = null
    private var capturer: RTCCameraVideoCapturer? = null

    // Renderers may arrive before or after the corresponding track — we store
    // them so whichever side arrives first can connect them.
    private var pendingLocalRenderer: RTCVideoRendererProtocol? = null
    private var pendingRemoteRenderer: RTCVideoRendererProtocol? = null

    // Google's free public STUN servers
    private val iceServers = listOf(
        RTCIceServer(uRLStrings = listOf("stun:stun.l.google.com:19302")),
        RTCIceServer(uRLStrings = listOf("stun:stun1.l.google.com:19302")),
    )

    // ── Public API ────────────────────────────────────────────────────────

    actual fun initializePeerConnection() {
        val encoderFactory = RTCDefaultVideoEncoderFactory()
        val decoderFactory = RTCDefaultVideoDecoderFactory()

        factory = RTCPeerConnectionFactory(
            encoderFactory = encoderFactory,
            decoderFactory = decoderFactory,
        )

        val config = RTCConfiguration().apply {
            iceServers = this@WebRTCManager.iceServers
            sdpSemantics = RTCSdpSemantics.RTCSdpSemanticsUnifiedPlan
        }

        peerConnection = factory!!.peerConnectionWithConfiguration(
            configuration = config,
            constraints = RTCMediaConstraints(
                mandatoryConstraints = mapOf(
                    "OfferToReceiveAudio" to "true",
                    "OfferToReceiveVideo" to "true",
                ),
                optionalConstraints = null,
            ),
            delegate = PeerConnectionDelegate(),
        )
    }

    actual fun startLocalCapture() {
        val f = factory ?: return

        // ── Video ──────────────────────────────────────────────────────────
        val videoSource = f.videoSource()
        val vTrack = f.videoTrackWithSource(videoSource, trackId = "local_video_0")
        localVideoTrack = vTrack

        val cap = RTCCameraVideoCapturer(delegate = videoSource)
        capturer = cap

        @Suppress("UNCHECKED_CAST")
        val allDevices = RTCCameraVideoCapturer.captureDevices() as List<AVCaptureDevice>
        val device: AVCaptureDevice = allDevices.firstOrNull() ?: return

        @Suppress("UNCHECKED_CAST")
        val formats = RTCCameraVideoCapturer.supportedFormatsForDevice(device) as List<AVCaptureDeviceFormat>
        val format = formats.lastOrNull() ?: return   // last = highest resolution

        cap.startCaptureWithDevice(device, format = format, fps = 30L)

        // ── Audio ──────────────────────────────────────────────────────────
        val audioSource = f.audioSourceWithConstraints(null)
        val audioTrack = f.audioTrackWithSource(audioSource, trackId = "local_audio_0")

        // Add both tracks; the stream ID groups them in the SDP
        peerConnection?.addTrack(vTrack, streamIds = listOf("local_stream"))
        peerConnection?.addTrack(audioTrack, streamIds = listOf("local_stream"))

        // If a renderer was already attached before tracks were ready, connect it now
        pendingLocalRenderer?.let { vTrack.addRenderer(it) }
    }

    actual fun createOffer() {
        peerConnection?.offerForConstraints(avConstraints()) { sdp, error ->
            handleLocalSdp(sdp, error, type = "offer")
        }
    }

    actual fun createAnswer() {
        peerConnection?.answerForConstraints(avConstraints()) { sdp, error ->
            handleLocalSdp(sdp, error, type = "answer")
        }
    }

    private fun avConstraints() = RTCMediaConstraints(
        mandatoryConstraints = mapOf(
            "OfferToReceiveAudio" to "true",
            "OfferToReceiveVideo" to "true",
        ),
        optionalConstraints = null,
    )

    private fun handleLocalSdp(sdp: RTCSessionDescription?, error: NSError?, type: String) {
        if (error != null || sdp == null) {
            callbacks.onError("create$type failed: ${error?.localizedDescription}")
            return
        }
        peerConnection?.setLocalDescription(sdp) { setError ->
            if (setError != null) {
                callbacks.onError("setLocalDescription ($type): ${setError.localizedDescription}")
                return@setLocalDescription
            }
            callbacks.onLocalSdpCreated(sdp.sdp, type)
        }
    }

    actual fun setRemoteDescription(sdp: String, type: String) {
        val sdpType = if (type == "offer") RTCSdpType.RTCSdpTypeOffer else RTCSdpType.RTCSdpTypeAnswer
        val sessionDescription = RTCSessionDescription(type = sdpType, sdp = sdp)
        peerConnection?.setRemoteDescription(sessionDescription) { error ->
            if (error != null) callbacks.onError("setRemoteDescription: ${error.localizedDescription}")
        }
    }

    actual fun addRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = RTCIceCandidate(
            sdp = candidate,
            sdpMLineIndex = sdpMLineIndex,
            sdpMid = sdpMid,
        )
        peerConnection?.addIceCandidate(iceCandidate)
    }

    actual fun attachLocalRenderer(renderer: Any) {
        val view = renderer as? RTCVideoRendererProtocol ?: return
        pendingLocalRenderer = view
        localVideoTrack?.addRenderer(view)
    }

    actual fun attachRemoteRenderer(renderer: Any) {
        val view = renderer as? RTCVideoRendererProtocol ?: return
        pendingRemoteRenderer = view
        remoteVideoTrack?.addRenderer(view)
    }

    actual fun dispose() {
        capturer?.stopCapture()
        localVideoTrack?.isEnabled = false
        peerConnection?.close()
        peerConnection = null
        factory = null
    }

    // ── PeerConnection delegate ───────────────────────────────────────────
    //
    // RTCPeerConnectionDelegate is an ObjC protocol.  Multiple methods share
    // the Kotlin name "peerConnection" but differ by the second parameter label
    // (how ObjC selectors work).  @Suppress("CONFLICTING_OVERLOADS") opts out
    // of the Kotlin function-uniqueness check at the class level; individual
    // methods that collide at the JVM-erased-type level also need
    // @ObjCSignatureOverride so the ObjC runtime can dispatch correctly.

    @Suppress("CONFLICTING_OVERLOADS")
    private inner class PeerConnectionDelegate : NSObject(), RTCPeerConnectionDelegateProtocol {

        // Called when a local ICE candidate is ready → forward via signaling
        override fun peerConnection(peerConnection: RTCPeerConnection, didGenerateIceCandidate: RTCIceCandidate) {
            callbacks.onLocalIceCandidate(
                didGenerateIceCandidate.sdp,
                didGenerateIceCandidate.sdpMid ?: "",
                didGenerateIceCandidate.sdpMLineIndex.toInt(),
            )
        }

        // Overall PeerConnection connectivity (new in WebRTC Unified Plan)
        override fun peerConnection(peerConnection: RTCPeerConnection, didChangeConnectionState: RTCPeerConnectionState) {
            val stateStr = when (didChangeConnectionState) {
                RTCPeerConnectionState.RTCPeerConnectionStateConnected    -> "connected"
                RTCPeerConnectionState.RTCPeerConnectionStateFailed       -> "failed"
                RTCPeerConnectionState.RTCPeerConnectionStateDisconnected -> "disconnected"
                RTCPeerConnectionState.RTCPeerConnectionStateClosed       -> "closed"
                else -> return
            }
            callbacks.onConnectionStateChange(stateStr)
        }

        // Remote track arrived (Unified Plan) — attach the pending renderer if ready
        override fun peerConnection(peerConnection: RTCPeerConnection, didStartReceivingOnTransceiver: RTCRtpTransceiver) {
            val track = didStartReceivingOnTransceiver.receiver().track() as? RTCVideoTrack ?: return
            remoteVideoTrack = track
            pendingRemoteRenderer?.let { track.addRenderer(it) }
        }

        // ── Required stubs (not used in this demo) ────────────────────────

        override fun peerConnection(peerConnection: RTCPeerConnection, didChangeSignalingState: RTCSignalingState) {}

        // didAddStream and didRemoveStream share the same erased JVM type (RTCMediaStream second param)
        // @ObjCSignatureOverride lets the ObjC runtime disambiguate them by full selector.
        @ObjCSignatureOverride
        override fun peerConnection(peerConnection: RTCPeerConnection, didAddStream: RTCMediaStream) {}

        @ObjCSignatureOverride
        override fun peerConnection(peerConnection: RTCPeerConnection, didRemoveStream: RTCMediaStream) {}

        override fun peerConnectionShouldNegotiate(peerConnection: RTCPeerConnection) {}
        override fun peerConnection(peerConnection: RTCPeerConnection, didChangeIceConnectionState: RTCIceConnectionState) {}
        override fun peerConnection(peerConnection: RTCPeerConnection, didChangeIceGatheringState: RTCIceGatheringState) {}
        override fun peerConnection(peerConnection: RTCPeerConnection, didRemoveIceCandidates: List<*>) {}
        override fun peerConnection(peerConnection: RTCPeerConnection, didOpenDataChannel: RTCDataChannel) {}
    }
}
