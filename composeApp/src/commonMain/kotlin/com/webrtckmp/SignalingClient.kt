package com.webrtckmp

import com.webrtckmp.models.SignalingMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the WebSocket connection to the signaling server.
 *
 * Responsibilities:
 *  - Connect / disconnect
 *  - Serialize outgoing SignalingMessages to JSON and send them
 *  - Deserialize incoming JSON frames and emit them on [incomingMessages]
 *
 * The ViewModel listens to [incomingMessages] and drives WebRTC accordingly.
 */
class SignalingClient(private val serverUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    // SharedFlow so multiple collectors can receive messages (though we only need one)
    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient {
        install(WebSockets)
        install(Logging) { level = LogLevel.INFO }
    }

    private var session: WebSocketSession? = null

    fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        scope.launch {
            try {
                client.webSocket(serverUrl) {
                    session = this
                    onConnected()

                    // Keep the connection alive — Android's network stack drops
                    // idle WebSocket connections after ~30–60 s.
                    val pingJob = launch {
                        while (true) {
                            delay(20_000L)
                            send(Frame.Ping(ByteArray(0)))
                        }
                    }

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            runCatching { json.decodeFromString<SignalingMessage>(text) }
                                .onSuccess { _incomingMessages.emit(it) }
                                .onFailure { println("SignalingClient: parse error: $it\nRaw: $text") }
                        }
                    }
                }
            } catch (e: Exception) {
                println("SignalingClient: connection error: $e")
            } finally {
                session = null
                onDisconnected()
            }
        }
    }

    fun send(message: SignalingMessage) {
        scope.launch {
            val text = json.encodeToString(message)
            session?.send(Frame.Text(text))
        }
    }

    fun disconnect() {
        // Cancelling the scope cancels the connect coroutine — Ktor's
        // client.webSocket {} closes the underlying connection automatically
        // when its block exits via cancellation. Pending send() jobs also die.
        scope.cancel()
    }
}
