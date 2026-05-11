package com.webrtckmp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.webrtckmp.models.CallState
import com.webrtckmp.screens.CallScreen
import com.webrtckmp.screens.EndedScreen
import com.webrtckmp.screens.FailedScreen
import com.webrtckmp.screens.LoadingScreen
import com.webrtckmp.screens.LobbyScreen

@Composable
fun App(viewModel: CallViewModel, videoContent: @Composable (CallState) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val callState by viewModel.callState.collectAsState()

            when (callState) {
                is CallState.Idle -> LobbyScreen(userId = viewModel.userId) { roomId ->
                    viewModel.joinRoom(roomId)
                }
                is CallState.Connecting    -> LoadingScreen("Connecting to server…")
                is CallState.WaitingForPeer -> LoadingScreen("Waiting for someone to join…\nRoom is ready!")
                is CallState.Negotiating   -> LoadingScreen("Connecting call…")
                is CallState.InCall -> CallScreen(
                    peerId = (callState as CallState.InCall).peerId,
                    videoContent = { videoContent(callState) },
                    onHangUp = { viewModel.hangUp() },
                )
                is CallState.Ended  -> EndedScreen { viewModel.reset() }
                is CallState.Failed -> FailedScreen(
                    reason = (callState as CallState.Failed).reason,
                    onRetry = { viewModel.reset() },
                )
            }
        }
    }
}
