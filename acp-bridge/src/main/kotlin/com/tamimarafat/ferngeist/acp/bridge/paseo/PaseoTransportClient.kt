package com.tamimarafat.ferngeist.acp.bridge.paseo

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

internal sealed interface PaseoConnectionState {
    data object Disconnected : PaseoConnectionState

    data object Connecting : PaseoConnectionState

    data object Connected : PaseoConnectionState

    data class Failed(val error: String?) : PaseoConnectionState
}

/**
 * Low-level Paseo WebSocket transport for a single daemon connection over the
 * local network (direct only — no relay/E2EE).
 *
 * Responsibilities:
 * - Open `ws(s)://host/ws` with the `paseo.bearer.<password>` subprotocol.
 * - Perform the flat `hello` handshake and await `server_info`.
 * - Frame the two-layer envelope: flat `ping`/`pong`; everything else wrapped as
 *   `{type:"session", message:{...}}`.
 * - Correlate request/response by `requestId`, and broadcast unsolicited inner
 *   session messages (agent_stream, permission requests, status) via [inbound].
 */
internal class PaseoTransportClient(
    private val scope: CoroutineScope,
    private val clientId: String,
    private val appVersion: String,
) {
    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) { install(WebSockets) }
    }

    private val _state = MutableStateFlow<PaseoConnectionState>(PaseoConnectionState.Disconnected)
    val state: StateFlow<PaseoConnectionState> = _state.asStateFlow()

    private val _serverInfo = MutableStateFlow<JsonObject?>(null)
    val serverInfo: StateFlow<JsonObject?> = _serverInfo.asStateFlow()

    /** Unsolicited (or response) inner session messages: the `message` object of every `{type:"session"}` frame. */
    private val _inbound = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    val inbound: SharedFlow<JsonObject> = _inbound.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null
    private var pingJob: Job? = null
    private val sendMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    // ---- Relay E2EE state (null/false for direct connections) ----
    private val crypto = PaseoCrypto()

    @Volatile
    private var encrypting = false

    @Volatile
    private var sharedKey: ByteArray? = null

    private var clientKeyPair: PaseoCrypto.KeyPair? = null
    private var e2eeReady: CompletableDeferred<Unit>? = null
    private var e2eeRetryJob: Job? = null

    val isConnected: Boolean
        get() = _state.value is PaseoConnectionState.Connected

    /**
     * Opens the socket (direct or relay), performs any E2EE + the hello handshake, and
     * waits for `server_info`.
     * @return true on a successful handshake.
     */
    suspend fun connect(
        connection: PaseoConnection,
        helloTimeoutMs: Long = 15_000L,
    ): Boolean {
        disconnect()
        _state.value = PaseoConnectionState.Connecting
        return try {
            val newSession = openSocket(connection)
            session = newSession
            startReceiveLoop(newSession)

            if (connection is PaseoConnection.Relay) {
                if (!performE2eeHandshake(helloTimeoutMs)) {
                    _state.value = PaseoConnectionState.Failed("E2EE handshake failed")
                    disconnect()
                    return false
                }
            }

            if (!completeServerHandshake(helloTimeoutMs)) {
                disconnect()
                return false
            }
            _state.value = PaseoConnectionState.Connected
            startPingLoop()
            true
        } catch (t: Throwable) {
            _state.value = PaseoConnectionState.Failed(t.message)
            disconnect()
            false
        }
    }

    private suspend fun openSocket(connection: PaseoConnection): DefaultClientWebSocketSession =
        when (connection) {
            is PaseoConnection.Direct -> {
                val wsScheme = if (connection.scheme == "wss" || connection.scheme == "https") "wss" else "ws"
                httpClient.webSocketSession {
                    url("$wsScheme://${connection.host}/ws")
                    if (connection.password.isNotBlank()) {
                        headers.append(HttpHeaders.SecWebSocketProtocol, "paseo.bearer.${connection.password}")
                    }
                }
            }

            is PaseoConnection.Relay -> {
                // Derive the E2EE shared key from the daemon's public key + a fresh ephemeral keypair.
                val keyPair = crypto.generateKeyPair()
                clientKeyPair = keyPair
                sharedKey = crypto.sharedKey(PaseoCrypto.decodeKey(connection.daemonPublicKeyB64), keyPair.secretKey)
                httpClient.webSocketSession { url(relayUrl(connection)) }
            }
        }

    private fun relayUrl(relay: PaseoConnection.Relay): String {
        val scheme = if (relay.useTls) "wss" else "ws"
        val serverId = java.net.URLEncoder.encode(relay.serverId, "UTF-8")
        return "$scheme://${relay.relayEndpoint}/ws?serverId=$serverId&role=client&v=2"
    }

    /** Sends `e2ee_hello` (plaintext, retried every 1s) and waits for `e2ee_ready`. */
    private suspend fun performE2eeHandshake(timeoutMs: Long): Boolean {
        val keyPair = clientKeyPair ?: return false
        val ready = CompletableDeferred<Unit>()
        e2eeReady = ready
        val hello =
            buildJsonObject {
                put("type", "e2ee_hello")
                put("key", PaseoCrypto.encodeKey(keyPair.publicKey))
            }.toString()
        e2eeRetryJob =
            scope.launch {
                while (isActive && !ready.isCompleted) {
                    runCatching { sendPlain(hello) }
                    delay(E2EE_RETRY_MS)
                }
            }
        val ok = withTimeoutOrFailure(timeoutMs) { ready.await() } != null
        e2eeRetryJob?.cancel()
        e2eeRetryJob = null
        return ok && encrypting
    }

    private suspend fun completeServerHandshake(timeoutMs: Long): Boolean {
        val serverInfoDeferred = CompletableDeferred<JsonObject>()
        pendingServerInfo = serverInfoDeferred
        sendHello()
        val info =
            withTimeoutOrFailure(timeoutMs) { serverInfoDeferred.await() }
                ?: run {
                    _state.value = PaseoConnectionState.Failed("Handshake timed out")
                    return false
                }
        _serverInfo.value = info
        return true
    }

    private var pendingServerInfo: CompletableDeferred<JsonObject>? = null

    private suspend fun <T> withTimeoutOrFailure(
        timeoutMs: Long,
        block: suspend () -> T,
    ): T? =
        try {
            kotlinx.coroutines.withTimeout(timeoutMs) { block() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }

    private suspend fun sendHello() {
        val hello =
            buildJsonObject {
                put("type", "hello")
                put("clientId", clientId)
                put("clientType", "mobile")
                put("protocolVersion", PROTOCOL_VERSION)
                put("appVersion", appVersion)
            }
        sendRaw(hello.toString())
    }

    private fun startReceiveLoop(session: DefaultClientWebSocketSession) {
        receiveJob =
            scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            handleFrame(frame.readText())
                        }
                    }
                    onClosed(null)
                } catch (t: Throwable) {
                    onClosed(t.message)
                }
            }
    }

    private fun startPingLoop() {
        pingJob =
            scope.launch {
                while (isActive && isConnected) {
                    delay(PING_INTERVAL_MS)
                    runCatching {
                        sendRaw("""{"type":"ping"}""")
                    }
                }
            }
    }

    private suspend fun handleFrame(text: String) {
        if (encrypting) {
            val key = sharedKey ?: return
            val plain =
                runCatching { crypto.open(PaseoCrypto.decodeFrame(text), key) }.getOrNull()
            if (plain == null) {
                // Decrypt failure / plaintext on an encrypted channel is fatal — drop the socket.
                onClosed("E2EE decryption failure")
                disconnect()
                return
            }
            handlePlain(String(plain, Charsets.UTF_8))
        } else {
            handlePlain(text)
        }
    }

    private suspend fun handlePlain(text: String) {
        val obj = runCatching { PaseoJson.parseToJsonElement(text) }.getOrNull().asObjectOrNull() ?: return
        when (obj.typeTag()) {
            "e2ee_ready" -> {
                if (sharedKey != null) encrypting = true
                e2eeReady?.complete(Unit)
            }
            "ping" -> sendRaw("""{"type":"pong"}""")
            "pong" -> Unit
            "recording_state" -> Unit
            "session" -> obj.obj("message")?.let { dispatchSessionMessage(it) }
            else -> Unit
        }
    }

    private suspend fun dispatchSessionMessage(message: JsonObject) {
        // server_info satisfies the handshake.
        if (message.typeTag() == "status" &&
            message.obj("payload")?.str("status") == "server_info"
        ) {
            message.obj("payload")?.let { pendingServerInfo?.complete(it) }
        }

        // Correlate responses / errors by requestId.
        val requestId = message.obj("payload")?.str("requestId") ?: message.str("requestId")
        if (requestId != null) {
            val waiter = pending.remove(requestId)
            if (waiter != null) {
                if (message.typeTag() == "rpc_error") {
                    val errText = message.obj("payload")?.firstString("error", "message") ?: "Paseo RPC error"
                    waiter.completeExceptionally(PaseoRpcException(errText))
                } else {
                    waiter.complete(message)
                }
            }
        }

        // Always broadcast for unsolicited handling (agent_stream, permission requests, updates).
        _inbound.emit(message)
    }

    /** Sends a session-wrapped request and awaits the correlated response. */
    suspend fun request(
        requestType: String,
        requestId: String,
        fields: JsonObject,
        timeoutMs: Long = 30_000L,
    ): JsonObject {
        val deferred = CompletableDeferred<JsonObject>()
        pending[requestId] = deferred
        val inner =
            buildJsonObject {
                put("type", requestType)
                fields.forEach { (k, v) -> put(k, v) }
                put("requestId", requestId)
            }
        sendSession(inner)
        return try {
            withTimeoutOrFailure(timeoutMs) { deferred.await() }
                ?: throw PaseoRpcException("Request '$requestType' timed out")
        } finally {
            pending.remove(requestId)
        }
    }

    /** Sends a session-wrapped message without awaiting a response. */
    suspend fun sendSession(inner: JsonObject) {
        val envelope =
            buildJsonObject {
                put("type", "session")
                put("message", inner)
            }
        sendRaw(envelope.toString())
    }

    /** Sends application text, encrypting through the E2EE channel once it is open. */
    private suspend fun sendRaw(text: String) {
        val key = sharedKey
        if (encrypting && key != null) {
            sendPlain(PaseoCrypto.encodeFrame(crypto.seal(text.toByteArray(Charsets.UTF_8), key)))
        } else {
            sendPlain(text)
        }
    }

    /** Sends a raw text frame with no encryption (handshake frames and direct connections). */
    private suspend fun sendPlain(text: String) {
        val active = session ?: throw PaseoRpcException("Not connected")
        sendMutex.withLock {
            active.send(Frame.Text(text))
        }
    }

    private fun onClosed(error: String?) {
        if (_state.value is PaseoConnectionState.Connected || _state.value is PaseoConnectionState.Connecting) {
            _state.value =
                if (error != null) PaseoConnectionState.Failed(error) else PaseoConnectionState.Disconnected
        }
        failAllPending(error)
    }

    private fun failAllPending(error: String?) {
        val ex = PaseoRpcException(error ?: "Connection closed")
        pending.values.forEach { it.completeExceptionally(ex) }
        pending.clear()
        pendingServerInfo?.completeExceptionally(ex)
        pendingServerInfo = null
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        receiveJob?.cancel()
        receiveJob = null
        e2eeRetryJob?.cancel()
        e2eeRetryJob = null
        encrypting = false
        sharedKey = null
        clientKeyPair = null
        e2eeReady = null
        val current = session
        session = null
        scope.launch { runCatching { current?.close() } }
        failAllPending(null)
        if (_state.value !is PaseoConnectionState.Failed) {
            _state.value = PaseoConnectionState.Disconnected
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val PING_INTERVAL_MS = 25_000L
        private const val E2EE_RETRY_MS = 1_000L
    }
}

internal class PaseoRpcException(message: String) : RuntimeException(message)
