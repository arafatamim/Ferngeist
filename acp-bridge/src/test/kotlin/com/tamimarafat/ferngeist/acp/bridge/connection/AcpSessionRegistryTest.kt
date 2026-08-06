package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SetSessionConfigOptionResponse
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.SetSessionModelResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK's [ClientSession] is an interface with no mocking library available in
 * this module, so we hand-roll a minimal fake. Only [ClientSession.close] is
 * implemented for real (it records the invocation); every other member is a
 * throwing stub because [AcpSessionRegistry] only ever calls `close()` on the
 * stored SDK session.
 */
@OptIn(UnstableApi::class)
internal class AcpSessionRegistryTest {

    private class RecordingClientSession : ClientSession {
        var closeCalled: Boolean = false

        override val sessionId: SessionId get() = error("unused")
        override val parameters: SessionCreationParameters get() = error("unused")
        override val client: Client get() = error("unused")
        override val operations: ClientSessionOperations get() = error("unused")

        override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> =
            error("unused")

        override suspend fun cancel() {
            error("unused")
        }

        override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
            closeCalled = true
            return CloseSessionResponse()
        }

        override val modesSupported: Boolean get() = false
        override val availableModes: List<SessionMode> get() = emptyList()
        override val currentMode: StateFlow<SessionModeId> get() = error("unused")

        override suspend fun setMode(
            modeId: SessionModeId,
            _meta: JsonElement?,
        ): SetSessionModeResponse = error("unused")

        override val modelsSupported: Boolean get() = false
        override val availableModels: List<ModelInfo> get() = emptyList()
        override val currentModel: StateFlow<ModelId> get() = error("unused")

        override suspend fun setModel(
            modelId: ModelId,
            _meta: JsonElement?,
        ): SetSessionModelResponse = error("unused")

        override val configOptionsSupported: Boolean get() = false
        override val configOptions: StateFlow<List<SessionConfigOption>> get() = error("unused")

        override suspend fun setConfigOption(
            configId: SessionConfigId,
            value: SessionConfigOptionValue,
            _meta: JsonElement?,
        ): SetSessionConfigOptionResponse = error("unused")
    }

    @Test
    fun `close is invoked when shouldCloseSdkSession returns true`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = RecordingClientSession()
        val registry = AcpSessionRegistry(scope) { true }

        registry.storeSdkSession("s1", session)
        registry.clearSession("s1", closeBridge = false)

        assertTrue(session.closeCalled)
    }

    @Test
    fun `close is not invoked when shouldCloseSdkSession returns false`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = RecordingClientSession()
        val registry = AcpSessionRegistry(scope) { false }

        registry.storeSdkSession("s1", session)
        registry.clearSession("s1", closeBridge = false)

        assertFalse(session.closeCalled)
    }

    @Test
    fun `clearAll respects shouldCloseSdkSession`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = RecordingClientSession()
        val registry = AcpSessionRegistry(scope) { true }

        registry.storeSdkSession("s1", session)
        registry.clearAll(closeBridges = false)

        assertTrue(session.closeCalled)
    }
}
