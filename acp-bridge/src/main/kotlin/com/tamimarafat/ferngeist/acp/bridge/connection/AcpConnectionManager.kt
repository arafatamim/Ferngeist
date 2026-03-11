package com.tamimarafat.ferngeist.acp.bridge.connection

import android.os.Build
import androidx.annotation.RequiresApi
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class AcpConnectionManager(
    private val connectivityObserver: ConnectivityObserver,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TRACE_TAG = "TSAcpLoad"
    }

    private val _connectionState = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Disconnected)
    val connectionState: StateFlow<AcpConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<AcpManagerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AcpManagerEvent> = _events.asSharedFlow()

    private val _agentCapabilities = MutableStateFlow<AcpAgentCapabilities?>(null)
    val agentCapabilities: StateFlow<AcpAgentCapabilities?> = _agentCapabilities.asStateFlow()

    private val diagnosticsStore = AcpDiagnosticsStore()
    val diagnostics: StateFlow<ConnectionDiagnostics> = diagnosticsStore.diagnostics

    private val sessionRegistry = AcpSessionRegistry()
    private val transportClient = AcpTransportClient(
        connectivityObserver = connectivityObserver,
        scope = scope,
        diagnosticsStore = diagnosticsStore,
        updateConnectionState = { state -> _connectionState.value = state },
        emitManagerEvent = { event -> _events.emit(event) },
    )

    init {
        scope.launch {
            events.collect { event ->
                when (event) {
                    is AcpManagerEvent.Initialized -> {
                        _agentCapabilities.value = event.result.agentCapabilities
                    }

                    is AcpManagerEvent.Disconnected -> {
                        _agentCapabilities.value = null
                    }

                    else -> Unit
                }
            }
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value is AcpConnectionState.Connected

    suspend fun connect(config: AcpConnectionConfig): Boolean {
        return transportClient.connect(config, resetState = ::resetConnectionState)
    }

    suspend fun initialize(): AcpInitializeResult? {
        val result = transportClient.initialize()
        _agentCapabilities.value = result?.agentCapabilities
        return result
    }

    suspend fun authenticate(methodId: String): AcpAuthenticateResult {
        return transportClient.authenticate(methodId)
    }

    suspend fun disconnect() {
        transportClient.disconnect(resetState = ::resetConnectionState)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listSessions(): List<SessionSummary> {
        val client = transportClient.sdkClient ?: return emptyList()
        return runCatching {
            @OptIn(UnstableApi::class)
            client.listSessions().toList().map { sessionInfo ->
                SessionSummary(
                    id = sessionInfo.sessionId.value,
                    title = sessionInfo.title,
                    cwd = sessionInfo.cwd,
                    updatedAt = AcpSessionUpdateMapper.parseIsoOrMillis(sessionInfo.updatedAt)
                )
            }
        }.getOrElse {
            diagnosticsStore.appendError("session/list", formatAcpErrorMessage(it, "Failed to list sessions"))
            emptyList()
        }
    }

    suspend fun createSession(cwd: String = "/"): SessionBridge? {
        val client = transportClient.sdkClient ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/new")
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                operationsFactory = operationsFactory
            )
            registerSession(session)
        }.getOrElse {
            diagnosticsStore.appendError("session/new", formatAcpErrorMessage(it, "Failed to create session"))
            null
        }
    }

    suspend fun loadSession(
        sessionId: String,
        cwd: String,
    ): SessionBridge? {
        getLoadedSession(sessionId)?.let { existing ->
            return existing
        }

        val client = transportClient.sdkClient ?: run {
            logError("loadSession: sdkClient is NULL, returning null")
            return null
        }
        diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/load")
        return runCatching {
            val bridge = sessionRegistry.getBridge(sessionId)
                ?: SessionBridge(sessionId, this).also { sessionRegistry.storeBridge(sessionId, it) }
            bridge.beginHydration()

            val session = client.loadSession(
                sessionId = SessionId(sessionId),
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList<McpServer>()),
                operationsFactory = operationsFactory,
            )
            val registeredBridge = registerSession(session)
            registeredBridge.completeHydration()
            registeredBridge.emitEvent(AppSessionEvent.SessionLoadComplete)
            registeredBridge
        }.getOrElse { error ->
            if (isSessionAlreadyLoadedError(error)) {
                getLoadedSession(sessionId)?.let { existing ->
                    diagnosticsStore.appendError("session/load", "Session is already loaded locally. Reusing the active session.")
                    return existing
                }

                val message = "This session is already active elsewhere. Reconnect or open a new session instead."
                sessionRegistry.getBridge(sessionId)?.failHydration(message)
                clearSessionState(sessionId, closeBridge = true)
                diagnosticsStore.appendError("session/load", message)
                return null
            }

            val message = formatAcpErrorMessage(error, "Failed to load session")
            sessionRegistry.getBridge(sessionId)?.failHydration(message)
            clearSessionState(sessionId, closeBridge = true)
            diagnosticsStore.appendError("session/load", message)
            null
        }
    }

    suspend fun sendSessionMessage(sessionId: String, content: String, images: List<Pair<String, String>> = emptyList()) {
        val bridge = sessionRegistry.getBridge(sessionId)
            ?: throw IllegalStateException("Session bridge missing for sessionId=$sessionId")
        val session = sessionRegistry.getSdkSession(sessionId)
            ?: throw IllegalStateException("SDK session missing for sessionId=$sessionId")

        diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/prompt")

        val blocks = mutableListOf<ContentBlock>()
        if (content.isNotEmpty()) {
            blocks += ContentBlock.Text(content)
        }
        for ((mimeType, data) in images) {
            blocks += ContentBlock.Image(data = data, mimeType = mimeType)
        }

        var receivedPromptResponse = false
        session.prompt(blocks).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(event.update)
                    if (appEvent != null) {
                        bridge.emitEvent(appEvent)
                    }
                }
                is Event.PromptResponseEvent -> {
                    receivedPromptResponse = true
                    bridge.emitEvent(
                        AppSessionEvent.TurnComplete(AcpSessionUpdateMapper.mapStopReason(event.response.stopReason))
                    )
                }
            }
        }

        // Defensive fallback: some servers/bridges can finish the prompt stream without
        // emitting a terminal PromptResponseEvent. Ensure the UI exits streaming state.
        if (!receivedPromptResponse) {
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
        }

        // keep bridge referenced to avoid warning; calls rely on bridge events
        bridge.sessionId
    }

    suspend fun cancelSession(sessionId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/cancel")
            session.cancel()
            diagnosticsStore.setSessionCancelSupport(isSupported = true)
        }.onFailure(::handleSessionCancelFailure)
    }

    suspend fun setSessionMode(sessionId: String, modeId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_mode")
            session.setMode(SessionModeId(modeId))
        }.onFailure {
            diagnosticsStore.appendError("session/set_mode", formatAcpErrorMessage(it, "Set mode failed"))
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun setSessionModel(sessionId: String, modelId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_model")
            session.setModel(com.agentclientprotocol.model.ModelId(modelId))
        }.onFailure {
            diagnosticsStore.appendError("session/set_model", formatAcpErrorMessage(it, "Set model failed"))
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun setSessionConfigOption(sessionId: String, optionId: String, value: SessionConfigValue) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_config_option")
            val response = session.setConfigOption(SessionConfigId(optionId), value.toSdkValue())
            // The SDK updates session.configOptions from the RPC response, but Ferngeist does not
            // collect that StateFlow directly. Mirror the authoritative response into the bridge so
            // dependent options (for example model-specific reasoning effort lists) update
            // immediately even when the server does not emit a separate config_option_update notify.
            emitToBridge(
                sessionId,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = response.configOptions.map(AcpSessionUpdateMapper::mapSdkConfigOption),
                )
            )
        }.onFailure {
            diagnosticsStore.appendError("session/set_config_option", formatAcpErrorMessage(it, "Set config option failed"))
        }
    }

    suspend fun respondPermissionSelected(sessionId: String, toolCallId: String, optionId: String) {
        val pending = sessionRegistry.takePendingPermissionRequest(toolCallId) ?: return
        pending.deferred.complete(RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    suspend fun respondPermissionCancelled(sessionId: String, toolCallId: String) {
        val pending = sessionRegistry.takePendingPermissionRequest(toolCallId) ?: return
        pending.deferred.complete(RequestPermissionOutcome.Cancelled)
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    fun getSession(sessionId: String): SessionBridge? = sessionRegistry.getBridge(sessionId)

    fun removeSession(sessionId: String) {
        clearSessionState(sessionId, closeBridge = true)
    }

    private val operationsFactory = ClientOperationsFactory { sessionId, _ ->
        createBridgeSessionOperations(sessionId.value)
    }

    private fun createBridgeSessionOperations(sessionId: String): ClientSessionOperations {
        return BridgeSessionOperations(sessionId)
    }

    private inner class BridgeSessionOperations(
        private val sessionId: String,
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: kotlinx.serialization.json.JsonElement?
        ): RequestPermissionResponse {
            val toolId = toolCall.toolCallId.value
            val deferred = CompletableDeferred<RequestPermissionOutcome>()
            sessionRegistry.addPendingPermissionRequest(
                toolCallId = toolId,
                sessionId = sessionId,
                deferred = deferred,
            )

            val options = permissions.map {
                SessionPermissionOption(
                    id = it.optionId.value,
                    label = it.name,
                    kind = it.kind.name.lowercase()
                )
            }

            emitToBridge(
                sessionId,
                AppSessionEvent.ToolPermissionRequested(
                    toolCallId = toolId,
                    requestId = toolId,
                    title = toolCall.title,
                    options = options
                )
            )

            val outcome = deferred.await()
            return RequestPermissionResponse(outcome = outcome)
        }

        override suspend fun notify(notification: SessionUpdate, _meta: kotlinx.serialization.json.JsonElement?) {
            val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(notification) ?: return
            emitToBridge(sessionId, appEvent)
        }
    }

    private suspend fun registerSession(session: ClientSession): SessionBridge {
        // Reuse the existing bridge if one was pre-created by loadSession so
        // buffered history can be committed into the same runtime.
        val bridge = sessionRegistry.getBridge(session.sessionId.value)
            ?: SessionBridge(session.sessionId.value, this)
        sessionRegistry.storeSdkSession(session.sessionId.value, session)
        sessionRegistry.storeBridge(session.sessionId.value, bridge)

        if (session.modesSupported) {
            val modes = session.availableModes.map {
                SessionMode(
                    id = it.id.value,
                    name = it.name,
                    description = it.description
                )
            }
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ModesUpdated(
                    modes = modes,
                    currentModeId = session.currentMode.value.value
                )
            )
        }

        @OptIn(UnstableApi::class)
        if (session.modelsSupported) {
            val current = session.currentModel.value.value
            val modelChoices = session.availableModels.map { model ->
                SessionConfigChoice(
                    id = model.modelId.value,
                    label = model.name,
                    value = model.modelId.value,
                    description = model.description,
                )
            }
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.LegacyModelOptionsUpdated(
                    choices = modelChoices,
                    currentModelId = current,
                )
            )
            // Stand-in for missing SDK client update type: the SDK exposes currentModel on the
            // session object, but not a corresponding SessionUpdate.CurrentModelUpdate event for
            // notify() consumers, so Ferngeist mirrors the initial selection into app state.
            emitToBridge(session.sessionId.value, AppSessionEvent.ModelSelectionConfirmed(current))
        }

        @OptIn(UnstableApi::class)
        if (session.configOptionsSupported) {
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = session.configOptions.value.map(AcpSessionUpdateMapper::mapSdkConfigOption),
                )
            )
        }

        bridge.markReady()
        return bridge
    }

    private suspend fun emitToBridge(sessionId: String, event: AppSessionEvent) {
        val bridge = sessionRegistry.getBridge(sessionId)
        if (bridge != null) {
            trace("emitToBridge sid=$sessionId event=${event::class.simpleName}")
            bridge.emitEvent(event)
            return
        }
        logError("emitToBridge: NO BRIDGE for sessionId=$sessionId, available keys=${sessionRegistry.bridgeIds()}")
        diagnosticsStore.appendError("session", "Session bridge not found for id: $sessionId")
    }

    private fun trace(message: String) {
        runCatching { android.util.Log.d(TRACE_TAG, message) }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        runCatching { android.util.Log.e("AcpConnectionManager", message, throwable) }
    }

    private fun clearAllSessionState(closeBridges: Boolean) {
        sessionRegistry.clearAll(closeBridges = closeBridges)
    }

    private fun clearSessionState(sessionId: String, closeBridge: Boolean) {
        sessionRegistry.clearSession(sessionId, closeBridge = closeBridge)
    }

    private fun getLoadedSession(sessionId: String): SessionBridge? {
        if (!sessionRegistry.hasSdkSession(sessionId)) return null
        return sessionRegistry.getBridge(sessionId)
    }

    private fun isSessionAlreadyLoadedError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException
        if (rpcError?.code != -32602) return false
        return rpcError.message.contains("already loaded", ignoreCase = true)
    }

    private fun updateSessionCancelSupport(isSupported: Boolean) {
        diagnosticsStore.setSessionCancelSupport(isSupported)
    }

    private fun handleSessionCancelFailure(error: Throwable) {
        if (isUnsupportedSessionCancel(error)) {
            updateSessionCancelSupport(isSupported = false)
        }
        diagnosticsStore.appendError("session/cancel", formatAcpErrorMessage(error, "Cancel failed"))
    }

    private fun isUnsupportedSessionCancel(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Method not found", ignoreCase = true) &&
            message.contains("session/cancel", ignoreCase = true)
    }

    internal suspend fun awaitConnectivityForReconnect() {
        transportClient.awaitConnectivityForReconnect()
    }

    @OptIn(UnstableApi::class)
    private fun SessionConfigValue.toSdkValue(): SessionConfigOptionValue {
        return when (this) {
            is SessionConfigValue.StringValue -> SessionConfigOptionValue.of(value)
            is SessionConfigValue.BoolValue -> SessionConfigOptionValue.of(value)
            is SessionConfigValue.UnknownValue -> error("Unsupported config option value: $this")
        }
    }

    private fun resetConnectionState() {
        _agentCapabilities.value = null
        clearAllSessionState(closeBridges = true)
    }

}

data class AgentInfo(
    val name: String,
    val version: String
)

sealed interface AcpManagerEvent {
    data object Connected : AcpManagerEvent
    data object Disconnected : AcpManagerEvent
    data class Initialized(val result: AcpInitializeResult) : AcpManagerEvent
    data class Authenticated(val methodId: String) : AcpManagerEvent
    data class Error(val throwable: Throwable) : AcpManagerEvent
}
