@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.webrtckmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import cocoapods.GoogleWebRTC.RTCEAGLVideoView

// Called from ContentView.swift to mount the shared Compose tree.
fun MainViewController(viewModel: CallViewModel) = ComposeUIViewController {
    App(viewModel = viewModel) { _ ->
        IosVideoLayer(viewModel = viewModel)
    }
}

/**
 * Hosts two RTCEAGLVideoView instances inside Compose using UIKitView.
 *
 * RTCEAGLVideoView is a Metal-accelerated UIView that renders WebRTC video
 * frames directly from the GPU pipeline — no CPU copy needed.
 *
 * UIKitView is Compose Multiplatform's bridge for embedding any UIView
 * inside a Compose layout.  The factory lambda runs once on the main thread
 * to create the view; update lambda fires on recomposition.
 */
@Composable
private fun IosVideoLayer(viewModel: CallViewModel) {
    val localRenderer  = remember { mutableStateOf<RTCEAGLVideoView?>(null) }
    val remoteRenderer = remember { mutableStateOf<RTCEAGLVideoView?>(null) }

    // Once both surfaces exist, register them with WebRTCManager.
    // This mirrors the exact same pattern used in Android's MainActivity.
    LaunchedEffect(localRenderer.value, remoteRenderer.value) {
        val local  = localRenderer.value  ?: return@LaunchedEffect
        val remote = remoteRenderer.value ?: return@LaunchedEffect
        viewModel.attachRenderers(local, remote)
    }

    // ── Remote video — fills the entire screen ────────────────────────────
    androidx.compose.ui.interop.UIKitView(
        factory = {
            // CGRectZero is fine; Compose drives the frame via the modifier
            RTCEAGLVideoView().also {
                remoteRenderer.value = it
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    // ── Local video — picture-in-picture overlay ──────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.interop.UIKitView(
            factory = {
                RTCEAGLVideoView().also {
                    localRenderer.value = it
                }
            },
            modifier = Modifier
                .size(width = 120.dp, height = 160.dp)
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
