package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// region: Test facades

/**
 * Configurable [ChatSessionFacade] for offline-queue tests. Extends [FakeChatSessionFacade]
 * to inherit all no-op defaults, overriding only [sendMessage] (to control success/failure
 * per call) and [reconnect] (to track reconnect calls).
 */
class TestFacade(
    private val sendResultProvider: () -> Boolean = { true },
) : FakeChatSessionFacade() {
    constructor(sendResult: Boolean) : this({ sendResult })

    var reconnectCount = 0
        private set

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        val result = sendResultProvider()
        if (!result) {
            emitSendOperationError()
        }
        return result
    }

    override suspend fun reconnect() {
        reconnectCount++
    }

    suspend fun emitSessionReady() {
        sessionReadyFlow.emit(Unit)
    }

    suspend fun emitSnapshot(snapshot: ChatSessionSnapshot) {
        sessionSnapshotFlow.emit(snapshot)
    }

    private suspend fun emitSendOperationError() {
        operationErrorFlow.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }
}

class TestFacadeFactory(
    private val factory: () -> TestFacade,
) : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<TestFacade?>(null)

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val facade = factory()
        lastFacade.value = facade
        return facade
    }
}

/**
 * Fake [ChatSessionFacade] that simulates a session that is never ready.
 * [loadSession] emits a load-failed error; all operations emit [operationError].
 */
open class FakeChatSessionFacade : ChatSessionFacade {
    protected val connectionStateFlow = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    protected val diagnosticsFlow = MutableStateFlow(ChatConnectionDiagnostics())
    protected val sessionSnapshotFlow = MutableStateFlow<ChatSessionSnapshot?>(null)
    protected val agentCapabilitiesFlow = MutableStateFlow(ChatAgentCapabilities())
    protected val gatewayWorkspaceConnectionFlow = MutableStateFlow<GatewayWorkspaceConnection?>(null)

    protected val loadFailedFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    protected val operationErrorFlow = MutableSharedFlow<ChatOperationError>(extraBufferCapacity = 1)
    protected val streamingCancelledFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val cancelUnsupportedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val sessionReadyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val modelUpdatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val connectionState: StateFlow<ChatConnectionState> = connectionStateFlow
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = diagnosticsFlow
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = sessionSnapshotFlow
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = agentCapabilitiesFlow
    override val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?> = gatewayWorkspaceConnectionFlow
    override val liveChatId: StateFlow<String?> = MutableStateFlow(null)

    /** Test hook: push a new snapshot into the session state. */
    protected fun setSessionSnapshot(snapshot: ChatSessionSnapshot?) {
        sessionSnapshotFlow.value = snapshot
    }

    override val loadFailed: SharedFlow<String> = loadFailedFlow
    override val operationError: SharedFlow<ChatOperationError> = operationErrorFlow
    override val streamingCancelled: SharedFlow<Unit> = streamingCancelledFlow
    override val cancelUnsupported: SharedFlow<Unit> = cancelUnsupportedFlow
    override val sessionReady: SharedFlow<Unit> = sessionReadyFlow
    override val modelUpdated: SharedFlow<Unit> = modelUpdatedFlow

    override suspend fun loadSession() {
        loadFailedFlow.emit("Session not found")
    }

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        emitOperationError()
        return false
    }

    override suspend fun cancelStreaming() {
        emitOperationError()
    }

    override suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) {
        emitOperationError()
    }

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        // No-op: permissions not exercised in this fake.
    }

    override suspend fun denyPermission(toolCallId: String) {
        // No-op: permissions not exercised in this fake.
    }

    override fun clear() {
        // No-op: no bridge state to tear down in this fake.
    }

    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        // No-op: connection state is driven directly via the state flows.
    }

    override suspend fun reconnect() {
        // No-op: reconnect is not exercised in this fake.
    }

    protected suspend fun emitOperationError() {
        operationErrorFlow.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }

    /** Emits a gateway workspace connection to the view model's init collector. */
    suspend fun emitGatewayWorkspaceConnection(connection: GatewayWorkspaceConnection) {
        gatewayWorkspaceConnectionFlow.value = connection
    }
}

/** Facade that immediately emits a pre-configured snapshot so [ChatSessionFacade.applySnapshot] is exercised. */
class SnapshotChatSessionFacade(
    snapshot: ChatSessionSnapshot,
) : FakeChatSessionFacade() {
    init {
        setSessionSnapshot(snapshot)
    }
}

/** Factory that creates [SnapshotChatSessionFacade] with the given snapshot. */
class SnapshotChatSessionFacadeFactory(
    private val snapshot: ChatSessionSnapshot,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade = SnapshotChatSessionFacade(snapshot)
}

/** Factory that returns [FakeChatSessionFacade] instances. */
class FakeChatSessionFacadeFactory : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<FakeChatSessionFacade?>(null)

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val facade = FakeChatSessionFacade()
        lastFacade.value = facade
        return facade
    }
}

// endregion
