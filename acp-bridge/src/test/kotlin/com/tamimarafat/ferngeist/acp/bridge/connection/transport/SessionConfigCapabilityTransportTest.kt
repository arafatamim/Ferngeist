package com.tamimarafat.ferngeist.acp.bridge.connection.transport

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcSuccessResponse
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class SessionConfigCapabilityTransportTest {
    @Test
    fun `initialize advertises boolean session config capability`() =
        runTest {
            val delegate = ScriptedTransport()
            val client = Client(newProtocol(delegate))
            initializeClient(client, delegate)

            val initialize = delegate.request("initialize")
            val clientCapabilities =
                initialize.params!!.jsonObject["clientCapabilities"]!!.jsonObject
            val session = clientCapabilities["session"]!!.jsonObject
            val configOptions = session["configOptions"]!!.jsonObject

            assertEquals(JsonObject(emptyMap()), configOptions["boolean"])
        }

    @Test
    fun `boolean config change uses boolean request and maps boolean response`() =
        runTest {
            val delegate = ScriptedTransport()
            val client = Client(newProtocol(delegate))
            initializeClient(client, delegate)
            val session = createSession(client, delegate)

            val setting =
                async {
                    session.setConfigOption(
                        configId = SessionConfigId("brave_mode"),
                        value = SessionConfigOptionValue.of(true),
                    )
                }
            advanceUntilIdle()

            val request = delegate.request("session/set_config_option")
            val params = request.params!!.jsonObject
            assertEquals("boolean", params["type"]!!.jsonPrimitive.content)
            assertTrue(params["value"]!!.jsonPrimitive.boolean)

            delegate.respond(
                request,
                buildJsonObject {
                    putJsonArray("configOptions") {
                        addJsonObject {
                            put("id", "brave_mode")
                            put("name", "Brave Mode")
                            put("type", "boolean")
                            put("currentValue", true)
                        }
                    }
                },
            )
            advanceUntilIdle()

            val response = setting.await()
            val option = response.configOptions.single() as SessionConfigOption.BooleanOption
            assertTrue(option.currentValue)
        }

    private suspend fun TestScope.initializeClient(
        client: Client,
        delegate: ScriptedTransport,
    ) {
        val initialized = async { client.initialize(clientInfo()) }
        advanceUntilIdle()
        delegate.respond(delegate.request("initialize"), buildJsonObject { put("protocolVersion", 1) })
        advanceUntilIdle()
        initialized.await()
    }

    private suspend fun TestScope.createSession(
        client: Client,
        delegate: ScriptedTransport,
    ): ClientSession {
        val sessionCreation =
            async {
                client.newSession(
                    sessionParameters = SessionCreationParameters(cwd = "/tmp", mcpServers = emptyList()),
                    operationsFactory = ClientOperationsFactory { _, _ -> NoopSessionOperations },
                )
            }
        advanceUntilIdle()
        delegate.respond(
            delegate.request("session/new"),
            buildJsonObject {
                put("sessionId", "session-1")
                putJsonArray("configOptions") {
                    addJsonObject {
                        put("id", "brave_mode")
                        put("name", "Brave Mode")
                        put("type", "boolean")
                        put("currentValue", false)
                    }
                }
            },
        )
        advanceUntilIdle()
        return sessionCreation.await()
    }

    private fun newProtocol(delegate: Transport): Protocol =
        Protocol(
            parentScope = CoroutineScope(UnconfinedTestDispatcher()),
            transport = SessionConfigCapabilityTransport(delegate),
            options = ProtocolOptions(protocolDebugName = "SessionConfigCapabilityTransportTest"),
        ).also { it.start() }

    private fun clientInfo(): ClientInfo =
        ClientInfo(
            capabilities = ClientCapabilities(),
            implementation = Implementation(name = "Ferngeist", version = "1.0.0"),
        )

    private object NoopSessionOperations : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = error("unused")

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: JsonElement?,
        ) = Unit
    }

    private class ScriptedTransport : Transport {
        override val state: StateFlow<Transport.State> = MutableStateFlow(Transport.State.STARTED)
        private var onFrame: ((TransportFrame) -> Unit)? = null
        private val requests = mutableListOf<JsonRpcRequest>()

        override fun start() = Unit

        override fun send(frame: TransportFrame) {
            val request = (frame as? TransportFrame.Single)?.message as? JsonRpcRequest ?: return
            requests += request
        }

        fun respond(
            request: JsonRpcRequest,
            result: JsonElement,
        ) {
            onFrame?.invoke(TransportFrame.Single(JsonRpcSuccessResponse(id = request.id, result = result)))
        }

        override fun onFrame(handler: (TransportFrame) -> Unit) {
            onFrame = handler
        }

        override fun onError(handler: (Throwable) -> Unit) = Unit

        override fun onClose(handler: () -> Unit) = Unit

        override fun close() = Unit

        fun request(method: String): JsonRpcRequest = requests.last { it.method.name == method }
    }
}
