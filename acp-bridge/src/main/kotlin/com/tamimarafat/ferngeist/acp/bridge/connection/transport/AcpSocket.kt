package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.WebSocketTransport
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpDiagnosticsStore
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpTransportListener
import com.tamimarafat.ferngeist.acp.bridge.connection.onRemoteResumed
import com.tamimarafat.ferngeist.acp.bridge.connection.onRemoteTurnEnded
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

// Keeps the WebSocket alive and detects dead peers: the CIO client pings on
// this interval when no frames are exchanged.
private const val WEB_SOCKET_PING_INTERVAL_MILLIS = 15_000L

// Smaller frames (streamed deltas, acks) cost more to deflate than they save.
private const val DEFLATE_MIN_FRAME_BYTES = 1024

// Bounds the WebSocket upgrade handshake. Without this, a peer that accepts
// the TCP socket but never completes the upgrade (or a dead agent behind a
// gateway) parks connect() forever — the initialize()/listSessions() timeouts
// never get reached because they run after the handshake.
private const val WEB_SOCKET_CONNECT_TIMEOUT_MS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L

// One CIO client for every socket in the process. Each client builds its own engine and TLS
// context, so a client per connection paid for both on every chat, listing and verification
// socket, and no handshake could resume another's TLS session. Never closed, like the REST client.
private val sharedWebSocketClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = WEB_SOCKET_PING_INTERVAL_MILLIS
            // Offered, not required: a gateway that does not negotiate permessage-deflate leaves the
            // socket as it was. A session/load replay is hundreds of KB of repetitive JSON.
            extensions {
                install(WebSocketDeflateExtension) {
                    compressIfBiggerThan(DEFLATE_MIN_FRAME_BYTES)
                }
            }
        }
    }
}

/** Opens the ACP WebSocket, bounded by [WEB_SOCKET_CONNECT_TIMEOUT_MS]. */
private suspend fun openWebSocket(wsUrl: String): DefaultClientWebSocketSession {
    // The handshake is bounded by WEB_SOCKET_CONNECT_TIMEOUT_MS. The
    // TimeoutCancellationException is converted into a plain IOException here so
    // connectInternal's catch treats it as a real connection failure (it rethrows
    // bare CancellationExceptions, which would otherwise cancel the caller before
    // it can clear the connecting spinner).
    return try {
        withTimeout(WEB_SOCKET_CONNECT_TIMEOUT_MS) {
            sharedWebSocketClient.webSocketSession {
                url(wsUrl)
            }
        }
    } catch (_: TimeoutCancellationException) {
        // Deliberately NOT chaining the TimeoutCancellationException as the cause:
        // isCancellationLikeError() scans the cause chain for CancellationException
        // and would otherwise downgrade this to a silent disconnect instead of a
        // surfaced failure with reconnect.
        throw IOException(
            "Connection timed out after ${WEB_SOCKET_CONNECT_TIMEOUT_MS / MILLIS_PER_SECOND}s. " +
                "The server accepted the connection but did not complete the handshake.",
        )
    }
}

/** Owns the socket, SDK client and generation checks for protocol callbacks. */
internal class AcpSocket(
    private val scope: CoroutineScope,
    private val listener: AcpTransportListener,
    private val diagnosticsStore: AcpDiagnosticsStore,
    private val onTermination: suspend (() -> Unit, Throwable?) -> Unit,
    private val onConnected: () -> Unit,
) {
    var client: Client? = null
        private set
    private var activeWebSocket: DefaultClientWebSocketSession? = null
    private var activeTransportGeneration = 0L
    private var ignoreTransportCallbacks = false

    /** Opens the socket and starts its ACP protocol before announcing the connection. */
    @OptIn(UnstableApi::class)
    suspend fun establishSession(
        wsUrl: String,
        resetState: () -> Unit,
        diagnosticsUrl: String = wsUrl,
    ): Boolean {
        diagnosticsStore.startConnect(diagnosticsUrl)
        val webSocketSession = openWebSocket(wsUrl)
        val protocol = startProtocol(webSocketSession, resetState)
        client = Client(protocol)
        activeWebSocket = webSocketSession
        onConnected()
        return true
    }

    private fun startProtocol(
        webSocketSession: DefaultClientWebSocketSession,
        resetState: () -> Unit,
    ): Protocol {
        // NOTE: Manual transport/Protocol setup instead of the SDK's
        // HttpClient.acpProtocolOnClientWebSocket() because Protocol.transport
        // is private — we need onError/onClose for reconnection.
        // Switch to the extension if a future SDK exposes Protocol.transport publicly.
        val transport =
            WebSocketTransport(
                parentScope = webSocketSession,
                wss = webSocketSession,
            )
        val protocol =
            Protocol(
                parentScope = webSocketSession,
                transport = transport,
                options = ProtocolOptions(protocolDebugName = "FerngeistACP"),
            )
        val generation = activeTransportGeneration + 1L
        activeTransportGeneration = generation
        ignoreTransportCallbacks = false
        transport.onError { error ->
            scope.launch {
                handleTermination(
                    generation = generation,
                    resetState = resetState,
                    error = error,
                )
            }
        }
        transport.onClose {
            scope.launch {
                handleTermination(
                    generation = generation,
                    resetState = resetState,
                )
            }
        }
        protocol.onRemoteTurnEnded(listener::onRemoteTurnEnded)
        protocol.onRemoteResumed(listener::onRemoteResumed)
        protocol.start()
        return protocol
    }

    private suspend fun handleTermination(
        generation: Long,
        resetState: () -> Unit,
        error: Throwable? = null,
    ) {
        if (generation != activeTransportGeneration || ignoreTransportCallbacks) return
        ignoreTransportCallbacks = true
        onTermination(resetState, error)
        ignoreTransportCallbacks = false
    }

    fun probe() {
        activeWebSocket?.outgoing?.trySend(Frame.Ping(ByteArray(0)))
    }

    fun discardClient() {
        runCatching { client?.protocol?.close() }
        client = null
    }

    fun release() {
        discardClient()
        activeWebSocket = null
    }

    fun close() {
        ignoreTransportCallbacks = true
        activeTransportGeneration++
        release()
        ignoreTransportCallbacks = false
    }
}
