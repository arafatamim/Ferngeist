package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.rpc.parseTransportFrame
import com.agentclientprotocol.transport.Transport
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

private class ConnectivityStub : ConnectivityObserver {
    private val _isConnected = MutableStateFlow(true)
    override val isConnected: Flow<Boolean> = _isConnected
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun newGateway(): SessionGateway {
    val scope = CoroutineScope(UnconfinedTestDispatcher())
    val connection =
        AcpConnection(
            connectivityObserver = ConnectivityStub(),
            resumeAttachToken = { _, _ -> error("no gateway resume in this test") },
            scope = scope,
        )
    return SessionGateway(
        connection = connection,
        permissionFlow = PermissionFlow(),
        elicitationFlow = ElicitationFlow(),
        bridgeFactory = { sessionId -> SessionBridge(sessionId, null) },
        scope = scope,
    )
}

/**
 * The gateway builds its [AcpSessionRegistry] internally and exposes no injection
 * seam, so tests reach the private field reflectively — the same approach
 * [com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHubTest] uses for `_connectionState`.
 */
internal fun gatewayField(
    gateway: SessionGateway,
    name: String,
): Any {
    val field = SessionGateway::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(gateway)!!
}

/**
 * Installs an SDK client on the connection's socket owner. Its client property
 * is `private set` and only assigned by a real connect, so a test that needs
 * `attachSession` past its disconnected check has to set it reflectively.
 */
internal fun installSdkClient(
    gateway: SessionGateway,
    client: Client,
) {
    val connection = gatewayField(gateway, "connection")
    val transportField = connection.javaClass.getDeclaredField("transportClient")
    transportField.isAccessible = true
    val transport = transportField.get(connection)!!
    val socketField = transport.javaClass.getDeclaredField("socket")
    socketField.isAccessible = true
    val socket = socketField.get(transport)!!
    val clientField = socket.javaClass.getDeclaredField("client")
    clientField.isAccessible = true
    clientField.set(socket, client)
}

/**
 * A [Client] whose next outbound request is failed by [transport].
 *
 * The SDK's [Client] is a final class — it can be neither proxied nor subclassed — and
 * this module has no mocking library. Its collaborator [Transport] is an interface
 * though, so the failure is injected at the wire instead: a real client over a real
 * protocol whose transport refuses every message. Only requests routed through it fail.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
internal fun clientOver(transport: Transport): Client =
    Client(
        Protocol(
            parentScope = CoroutineScope(UnconfinedTestDispatcher()),
            transport = transport,
            options = ProtocolOptions(protocolDebugName = "SessionGatewayTest"),
        ),
    )

/** A [Transport] whose every outbound message fails with [error]. */
internal class FailingSendTransport(
    private val error: Throwable,
) : Transport {
    /** Methods of the requests sent through this transport, in order. */
    val sentMethods = mutableListOf<String>()

    override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)

    override fun start() = Unit

    override fun send(frame: TransportFrame) {
        val message = (frame as? TransportFrame.Single)?.message
        sentMethods += (message as? JsonRpcRequest)?.method?.name ?: frame.toString()
        throw error
    }

    override fun onFrame(handler: (TransportFrame) -> Unit) = Unit

    override fun onError(handler: (Throwable) -> Unit) = Unit

    override fun onClose(handler: () -> Unit) = Unit

    override fun close() = Unit
}

/**
 * A gateway on the wire: answers each request whose method it has a script for with that
 * script's frames, in order, `$ID` standing for the request's id. Frames are raw JSON so
 * the test pins the gateway's catch-up contract, not the SDK's model of it.
 */
internal class ScriptedTransport(
    private val scripts: Map<String, List<String>>,
) : Transport {
    val sentMethods = mutableListOf<String>()

    /** The params of each request sent, in order. */
    val sentParams = mutableListOf<JsonElement?>()
    private var onFrame: (TransportFrame) -> Unit = {}

    override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)

    override fun start() = Unit

    override fun send(frame: TransportFrame) {
        val request = (frame as? TransportFrame.Single)?.message as? JsonRpcRequest ?: return
        sentMethods += request.method.name
        sentParams += request.params
        val id = ACPJson.encodeToJsonElement(request.id).toString()
        scripts[request.method.name].orEmpty().forEach { onFrame(parseTransportFrame(it.replace("\$ID", id))) }
    }

    override fun onFrame(handler: (TransportFrame) -> Unit) {
        onFrame = handler
    }

    override fun onError(handler: (Throwable) -> Unit) = Unit

    override fun onClose(handler: () -> Unit) = Unit

    override fun close() = Unit
}
