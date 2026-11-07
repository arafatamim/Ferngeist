package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.Transport
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Exercises the real SDK RPC path without opening a socket or adding a production test seam. */
@OptIn(UnstableApi::class)
internal class ChatAuthTransport : Transport {
    override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)
    private var handler: ((TransportFrame) -> Unit)? = null
    private var authenticated = false
    var authFailure: String? = null
    val authenticatedMethods = mutableListOf<String>()
    var creations = 0
        private set

    suspend fun install(
        connection: AcpConnection,
        scope: CoroutineScope,
    ) {
        val protocol = Protocol(scope, this, ProtocolOptions(protocolDebugName = "ChatAuthTest"))
        protocol.start()
        val transportField = AcpConnection::class.java.getDeclaredField("transportClient").apply { isAccessible = true }
        val transport = transportField.get(connection)
        val socketField = transport.javaClass.getDeclaredField("socket").apply { isAccessible = true }
        val socket = socketField.get(transport)
        socket.javaClass
            .getDeclaredField("client")
            .apply { isAccessible = true }
            .set(socket, Client(protocol))
        connection.initialize()
        val stateField = AcpConnection::class.java.getDeclaredField("_connectionState").apply { isAccessible = true }
        val connectionState = stateField.get(connection)
        MutableStateFlow::class.java
            .getMethod("setValue", Any::class.java)
            .invoke(connectionState, AcpConnectionState.Connected)
    }

    override fun start() = Unit

    override fun send(frame: TransportFrame) {
        val request = (frame as? TransportFrame.Single)?.message as? JsonRpcRequest ?: return
        val result =
            when (request.method.name) {
                "initialize" -> Json.parseToJsonElement(INITIALIZE_RESPONSE)
                "session/new" -> {
                    creations++
                    if (!authenticated) {
                        throw JsonRpcException(
                            -32000,
                            "Authentication required",
                            buildJsonObject { put("private", "raw data") },
                        )
                    }
                    buildJsonObject { put("sessionId", "created-session") }
                }
                "authenticate" -> {
                    authenticatedMethods +=
                        request.params!!
                            .jsonObject["methodId"]!!
                            .jsonPrimitive.content
                    authFailure?.let { throw JsonRpcException(-32001, it, JsonNull) }
                    authenticated = true
                    JsonObject(emptyMap())
                }
                else -> error("Unexpected request ${request.method.name}")
            }
        handler?.invoke(TransportFrame.Single(JsonRpcSuccessResponse(request.id, result)))
    }

    override fun onFrame(handler: (TransportFrame) -> Unit) {
        this.handler = handler
    }

    override fun onError(handler: (Throwable) -> Unit) = Unit

    override fun onClose(handler: () -> Unit) = Unit

    override fun close() = Unit
}

private const val INITIALIZE_RESPONSE = """
    {"protocolVersion":1,"agentInfo":{"name":"Auth Agent","version":"1"},"agentCapabilities":{},
    "authMethods":[{"id":"login","name":"Sign in"},
    {"id":"terminal","name":"Terminal login","type":"terminal","args":["login"]}]}
"""
