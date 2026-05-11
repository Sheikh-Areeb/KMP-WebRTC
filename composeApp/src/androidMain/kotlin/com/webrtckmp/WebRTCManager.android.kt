package com.webrtckmp

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// ── PlatformContext ────────────────────────────────────────────────────────

actual class PlatformContext actual constructor()

// ── WebRTCManager ─────────────────────────────────────────────────────────

actual class WebRTCManager actual constructor(
    private val context: PlatformContext,
    private val callbacks: WebRTCCallbacks,
) {
    companion object {
        // A single EglBase for the whole session; SurfaceViewRenderers share it.
        val eglBase: EglBase = EglBase.create()
    }

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    // Stored so we can attach the sink even if the renderer arrives before the track
    private var pendingLocalRenderer: SurfaceViewRenderer? = null
    private var pendingRemoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    // Google's public STUN servers — free, no account needed
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    // ── Public API ────────────────────────────────────────────────────────

    actual fun initializePeerConnection() {
        // PeerConnectionFactory.initialize() is called once in WebRTCApp.onCreate()
        // — calling it again here would throw "Already initialized" on re-join.
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
        if (peerConnection == null) {
            callbacks.onError("createPeerConnection returned null — check device/codec support")
        }
    }

    actual fun startLocalCapture() {
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceHelper = helper

        val videoSource = factory.createVideoSource(false)
        val vTrack = factory.createVideoTrack("local_video", videoSource)
        localVideoTrack = vTrack

        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("local_audio", audioSource)

        val enumerator = Camera2Enumerator(WebRTCApp.appContext)
        val cameraId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()

        if (cameraId != null) {
            val cap = enumerator.createCapturer(cameraId, null)
            videoCapturer = cap
            cap.initialize(helper, WebRTCApp.appContext, videoSource.capturerObserver)
            cap.startCapture(1280, 720, 30)
        }

        peerConnection?.addTrack(vTrack, listOf("local_stream"))
        peerConnection?.addTrack(localAudioTrack, listOf("local_stream"))

        // If a renderer was attached before tracks existed, connect it now
        pendingLocalRenderer?.let { vTrack.addSink(it) }
    }

    actual fun createOffer() {
        peerConnection?.createOffer(sdpObserver("offer"), avConstraints())
    }

    actual fun createAnswer() {
        peerConnection?.createAnswer(sdpObserver("answer"), avConstraints())
    }

    private fun avConstraints() = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
    }

    actual fun setRemoteDescription(sdp: String, type: String) {
        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        peerConnection?.setRemoteDescription(noopObserver(), SessionDescription(sdpType, sdp))
    }

    actual fun addRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    actual fun attachLocalRenderer(renderer: Any) {
        // init() / setMirror() are called by the AndroidView factory — see MainActivity.
        // Calling them again here would throw "Already initialized" on recompose.
        val svr = renderer as? SurfaceViewRenderer ?: return
        pendingLocalRenderer = svr
        localVideoTrack?.addSink(svr)   // no-op if track not ready yet; startLocalCapture handles it
    }

    actual fun attachRemoteRenderer(renderer: Any) {
        val svr = renderer as? SurfaceViewRenderer ?: return
        pendingRemoteRenderer = svr
        remoteVideoTrack?.addSink(svr)  // no-op if track not ready yet; onTrack handles it
    }

    actual fun dispose() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceHelper?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        if (::factory.isInitialized) factory.dispose()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun sdpObserver(type: String) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            peerConnection?.setLocalDescription(noopObserver(), sdp)
            callbacks.onLocalSdpCreated(sdp.description, type)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) { callbacks.onError("SDP create $type: $e") }
        override fun onSetFailure(e: String) { callbacks.onError("SDP set $type: $e") }
    }

    private fun noopObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            callbacks.onLocalIceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            callbacks.onConnectionStateChange(state.name.lowercase())
        }

        // Unified-Plan: remote tracks arrive one by one via onTrack
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                // Attach pending renderer if the UI beat the network
                pendingRemoteRenderer?.let { track.addSink(it) }
            }
        }

        // Required overrides — not used in this demo
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }
}
