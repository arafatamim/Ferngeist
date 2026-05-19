package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.JsonRpcException
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.SessionSummary
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

/**
 * Central orchestrator for ACP transport and session lifecycle.
 *
 * AcpConnectionManager is the single entry point for connecting, initializing,
 * authenticating, and creating/loading sessions. It owns:
 * - [AcpTransportClient] (TCP/WebSocket transport, reconnection, diagnostics)
 * - [AcpSessionRegistry] (session lifecycle, session-scoped RPC dispatch)
 * - [AcpDiagnosticsStore] (diagnostic timeline exposed to the UI)
 *
 * ## Session lifecycle
 * Sessions are created via [createSession] (fresh) or [loadSession] (restored).
 * Both return a [SessionPort] — the chat layer never sees the concrete
 * [SessionBridge]. Internally, the manager stores [SessionBridge] references
 * and calls its internal methods ([SessionBridge.emitEvent],
 * [SessionBridge.beginHydration], etc.).
 *
 * ## RPC dispatch
 * Protobuf/JSON-RPC messages from the SDK arrive through
 * [BridgeSessionOperations], which maps them to [AppSessionEvent] values and
 * forwards them to the correct bridge via [emitToBridge].
 *
 * ## Error handling
 * Errors are recorded in [diagnosticsStore] and surfaced as
 * [ConnectionDiagnostics]. Auth-required conditions are detected generically
 * from error messages (not vendor-specific codes) to maximise ACP server
 * compatibility.
 */
class AcpConnectionManager(
    private val connectivityObserver: ConnectivityObserver,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TRACE_TAG = "TSAcpLoad"
    }

    /** Exposes the raw transport state — Connected, Connecting, Disconnected, Failed. */
    private val _connectionState = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Disconnected)
    val connectionState: StateFlow<AcpConnectionState> = _connectionState.asStateFlow()

    /**
     * Scoped management events: connected/disconnected, initialized, authenticated,
     * and transport errors. Consumed internally to update agent metadata; exposed
     * for callers that need to react to connection lifecycle changes.
     */
    private val _events = MutableSharedFlow<AcpManagerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AcpManagerEvent> = _events.asSharedFlow()

    /** Agent capabilities reported during initialization — prompt support, features, etc. */
    private val _agentCapabilities = MutableStateFlow<AcpAgentCapabilities?>(null)
    val agentCapabilities: StateFlow<AcpAgentCapabilities?> = _agentCapabilities.asStateFlow()

    /** Agent metadata (name, version) from initialization. */
    private val _agentInfo = MutableStateFlow<AgentInfo?>(null)
    val agentInfo: StateFlow<AgentInfo?> = _agentInfo.asStateFlow()

    /** Available authentication methods for this server. */
    private val authMethodsFlow = MutableStateFlow<List<AcpAuthMethodInfo>>(emptyList())

    /** Append-only diagnostic timeline for debugging and error display. */
    private val diagnosticsStore = AcpDiagnosticsStore()
    val diagnostics: StateFlow<ConnectionDiagnostics> = diagnosticsStore.diagnostics

    /** Tracks all active SDK sessions and their bridges, keyed by session ID. */
    private val sessionRegistry = AcpSessionRegistry()

    /**
     * Lightweight wrapper around the SDK's raw transport (TCP or WebSocket).
     * Owns connection lifecycle, reconnection, and diagnostics reporting.
     */
    private val transportClient = AcpTransportClient(
        connectivityObserver = connectivityObserver,
        scope = scope,
        diagnosticsStore = diagnosticsStore,
        updateConnectionState = { state -> _connectionState.value = state },
        emitManagerEvent = { event -> _events.emit(event) },
    )

    /**
     * Watches [events] to update agent metadata on connect/disconnect.
     * Clears capabilities, info, and auth methods when the transport disconnects.
     */
    init {
        scope.launch {
            events.collect { event ->
                when (event) {
                    is AcpManagerEvent.Initialized -> {
                        _agentCapabilities.value = event.result.agentCapabilities
                        _agentInfo.value = event.result.agentInfo
                        authMethodsFlow.value = event.result.authMethods
                    }

                    is AcpManagerEvent.Disconnected -> {
                        _agentCapabilities.value = null
                        _agentInfo.value = null
                        authMethodsFlow.value = emptyList()
                    }

                    else -> Unit
                }
            }
        }
    }

    /** Convenience property for checking transport state without subscribing to [connectionState]. */
    val isConnected: Boolean
        get() = _connectionState.value is AcpConnectionState.Connected

    /**
     * Opens a transport connection to an ACP server.
     * Delegates to [AcpTransportClient.connect] with a reset callback.
     */
    suspend fun connect(config: AcpConnectionConfig): Boolean =
        transportClient.connect(config, resetState = ::resetConnectionState)

    /**
     * Runs the ACP initialize handshake, returning capabilities, agent info,
     * and available auth methods. Updates local state flows on success.
     */
    suspend fun initialize(): AcpInitializeResult? {
        val result = transportClient.initialize()
        _agentCapabilities.value = result?.agentCapabilities
        _agentInfo.value = result?.agentInfo
        authMethodsFlow.value = result?.authMethods ?: emptyList()
        return result
    }

    /** Returns the current connection configuration, or null if not connected. */
    fun currentConnectionConfig(): AcpConnectionConfig? = transportClient.currentConnectionConfig()

    /**
     * Authenticates with a specific method (e.g. OAuth callback URL opener).
     * Delegates to [AcpTransportClient.authenticate].
     */
    suspend fun authenticate(methodId: String): AcpAuthenticateResult = transportClient.authenticate(methodId)

    /**
     * Gracefully closes the transport and resets connection state.
     * All active sessions are cleared and their bridges closed.
     */
    fun disconnect() {
        transportClient.disconnect(resetState = ::resetConnectionState)
    }

    /**
     * Lists available sessions from the server via session/list RPC.
     *
     * Individual list items carry the session ID, title, cwd, and last-updated
     * timestamp. An auth-required error is re-thrown as
     * [AcpAuthenticationRequiredException]; other errors are recorded in
     * diagnostics and an empty list is returned.
     */
    suspend fun listSessions(cwd: String? = null): List<SessionSummary> {
        val client = transportClient.sdkClient ?: return emptyList()
        return runCatching {
            @OptIn(UnstableApi::class) client.listSessions(cwd = cwd).toList().map { sessionInfo ->
                SessionSummary(
                    id = sessionInfo.sessionId.value,
                    title = sessionInfo.title,
                    cwd = sessionInfo.cwd,
                    updatedAt = AcpSessionUpdateMapper.parseIsoOrMillis(sessionInfo.updatedAt),
                )
            }
        }.getOrElse {
            toAuthRequiredException(it)?.let { error -> throw error }
            diagnosticsStore.appendError(
                "session/list",
                formatAcpErrorMessage(it, "Failed to list sessions"),
            )
            emptyList()
        }
    }

    /**
     * Creates a new ACP session and returns a [SessionPort] for the chat layer.
     *
     * Internally this creates a [SessionBridge] (the concrete implementation),
     * registers it in [sessionRegistry], and starts forwarding SDK events.
     * Callers receive only the [SessionPort] interface, keeping session
     * internals (event emission, hydration) private to the connection layer.
     */
    suspend fun createSession(cwd: String = "/"): SessionPort? {
        val client = transportClient.sdkClient ?: return null
        return runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/new")
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                operationsFactory = operationsFactory,
            )
            registerSession(session)
        }.getOrElse {
            toAuthRequiredException(it)?.let { error -> throw error }
            diagnosticsStore.appendError(
                "session/new",
                formatAcpErrorMessage(it, "Failed to create session"),
            )
            null
        }
    }

    /**
     * Loads (or reattaches to) an existing session and returns a [SessionPort].
     *
     * If the session is already loaded, returns the existing handle immediately.
     * Otherwise, sends a session/load RPC. During load, events are buffered via
     * [SessionBridge.beginHydration] and flushed on completion. As with
     * [createSession], the returned [SessionPort] hides internal bridge
     * operations.
     */
    suspend fun loadSession(
        sessionId: String,
        cwd: String,
    ): SessionPort? {
        getLoadedSession(sessionId)?.let { existing ->
            return existing
        }

        val client = transportClient.sdkClient ?: run {
            logError("loadSession: sdkClient is NULL, returning null")
            return null
        }
        diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/load")
        return runCatching {
            val bridge = sessionRegistry.getBridge(sessionId) ?: SessionBridge(sessionId, this).also {
                sessionRegistry.storeBridge(sessionId, it)
            }
            bridge.beginHydration()

            val session = client.loadSession(
                sessionId = SessionId(sessionId),
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList()),
                operationsFactory = operationsFactory,
            )
            val registeredBridge = registerSession(session)
            registeredBridge.completeHydration()
            registeredBridge.emitEvent(AppSessionEvent.SessionLoadComplete)
            registeredBridge
        }.getOrElse { error ->
            toAuthRequiredException(error)?.let { authError -> throw authError }
            if (isSessionAlreadyLoadedError(error)) {
                getLoadedSession(sessionId)?.let { existing ->
                    diagnosticsStore.appendError(
                        "session/load",
                        "Session is already loaded locally. Reusing the active session.",
                    )
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
            throw error
        }
    }

    /**
     * Sends a user prompt message (text + optional images) to a session.
     *
     * This is the main write path for user input. It:
     * 1. Resolves the [SessionBridge] and SDK [ClientSession] from the registry
     * 2. Fires an optimistic [AppSessionEvent.UserMessage] so the UI displays
     *    the user's text immediately
     * 3. Streams prompt events from the SDK, mapping each [SessionUpdate] and
     *    terminal [PromptResponseEvent] to [AppSessionEvent] values
     * 4. Includes a defensive [AppSessionEvent.TurnComplete] if the prompt stream
     *    finishes without a PromptResponseEvent — some ACP server/transport combos
     *    can drop the terminal event on cancellation or teardown
     */
    suspend fun sendSessionMessage(
        sessionId: String,
        content: String,
        images: List<Pair<String, String>> = emptyList(),
    ) {
        val bridge = sessionRegistry.getBridge(sessionId) ?: throw IllegalStateException(
            "Session bridge missing for sessionId=$sessionId",
        )
        val session = sessionRegistry.getSdkSession(sessionId) ?: throw IllegalStateException(
            "SDK session missing for sessionId=$sessionId",
        )

        diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/prompt")

        // Emit the user message optimistically so the chat UI updates before
        // an explicit SessionUpdate.UserMessage event arrives from the server.
        bridge.emitEvent(
            AppSessionEvent.UserMessage(
                text = content,
                timestampMs = System.currentTimeMillis(),
            ),
        )

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
                        AppSessionEvent.TurnComplete(
                            AcpSessionUpdateMapper.mapStopReason(
                                event.response.stopReason,
                            ),
                        ),
                    )
                }
            }
        }

        // Defensive fallback: some servers/bridges can finish the prompt stream
        // without emitting a terminal PromptResponseEvent. Ensure the UI exits
        // streaming state.
        if (!receivedPromptResponse) {
            bridge.emitEvent(AppSessionEvent.TurnComplete("end_turn"))
        }

        // keep bridge referenced to avoid warning; calls rely on bridge events
        bridge.sessionId
    }

    /**
     * Cancels the current streaming turn on a session via session/cancel RPC.
     * Errors are recorded in diagnostics; session/cancel unavailability is
     * tracked to inform the UI that cancellation is not supported by this server.
     */
    suspend fun cancelSession(sessionId: String) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/cancel")
            session.cancel()
            diagnosticsStore.setSessionCancelSupport(isSupported = true)
        }.onFailure(::handleSessionCancelFailure)
    }

    /** Sets the session's active mode via session/set_mode RPC. */
    suspend fun setSessionMode(
        sessionId: String,
        modeId: String,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_mode")
            session.setMode(SessionModeId(modeId))
        }.onFailure {
            diagnosticsStore.appendError(
                "session/set_mode",
                formatAcpErrorMessage(it, "Set mode failed"),
            )
        }
    }

    /** Sets the session's legacy model via session/set_model RPC (unstable ACP API). */
    @OptIn(UnstableApi::class)
    suspend fun setSessionModel(
        sessionId: String,
        modelId: String,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(RpcDirection.OutboundRequest, "session/set_model")
            session.setModel(com.agentclientprotocol.model.ModelId(modelId))
        }.onFailure {
            diagnosticsStore.appendError(
                "session/set_model",
                formatAcpErrorMessage(it, "Set model failed"),
            )
        }
    }

    /**
     * Sets a native configuration option via session/set_config_option RPC.
     *
     * The SDK updates session.configOptions from the RPC response, but Ferngeist
     * does not collect that StateFlow directly. To keep dependent options (e.g.
     * model-specific reasoning effort lists) immediately in sync, we mirror the
     * authoritative response into the bridge even when the server does not emit
     * a separate config_option_update notify.
     */
    @OptIn(UnstableApi::class)
    suspend fun setSessionConfigOption(
        sessionId: String,
        optionId: String,
        value: SessionConfigValue,
    ) {
        val session = sessionRegistry.getSdkSession(sessionId) ?: return
        runCatching {
            diagnosticsStore.appendRpcEntry(
                RpcDirection.OutboundRequest,
                "session/set_config_option",
            )
            val response = session.setConfigOption(SessionConfigId(optionId), value.toSdkValue())
            emitToBridge(
                sessionId,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = response.configOptions.map(
                        AcpSessionUpdateMapper::mapSdkConfigOption,
                    ),
                ),
            )
        }.onFailure {
            diagnosticsStore.appendError(
                "session/set_config_option",
                formatAcpErrorMessage(it, "Set config option failed"),
            )
        }
    }

    /**
     * Resolves a pending tool permission request by selecting an option.
     * Completes the deferred [RequestPermissionOutcome] and emits a
     * [ToolPermissionResolved] event so the UI drops the inline permission card.
     */
    suspend fun respondPermissionSelected(
        sessionId: String,
        toolCallId: String,
        optionId: String,
    ) {
        val pending = sessionRegistry.takePendingPermissionRequest(toolCallId) ?: return
        pending.deferred.complete(
            RequestPermissionOutcome.Selected(PermissionOptionId(optionId)),
        )
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    /**
     * Cancels a pending tool permission request.
     * Completes the deferred [RequestPermissionOutcome] as Cancelled and emits
     * a [ToolPermissionResolved] event to clear the permission UI.
     */
    suspend fun respondPermissionCancelled(
        sessionId: String,
        toolCallId: String,
    ) {
        val pending = sessionRegistry.takePendingPermissionRequest(toolCallId) ?: return
        pending.deferred.complete(RequestPermissionOutcome.Cancelled)
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    /** Returns an existing session as a [SessionPort], or null if not registered. */
    fun getSession(sessionId: String): SessionPort? = sessionRegistry.getPort(sessionId)

    /**
     * Removes a session from the registry and closes its bridge.
     * Called when the user dismisses a session or the server rejects a load.
     */
    fun removeSession(sessionId: String) {
        clearSessionState(sessionId, closeBridge = true)
    }

    // ---- Internal: RPC dispatch bridge ----

    private val operationsFactory = ClientOperationsFactory { sessionId, _ ->
        createBridgeSessionOperations(sessionId.value)
    }

    private fun createBridgeSessionOperations(sessionId: String): ClientSessionOperations =
        BridgeSessionOperations(sessionId)

    /**
     * Bridges between the ACP SDK's [ClientSessionOperations] callbacks and
     * Ferngeist's [AppSessionEvent] stream.
     *
     * Each registered session gets an instance of this class, which handles
     * two callbacks:
     * - [requestPermissions]: creates a deferred outcome, maps SDK permission
     *   options to Ferngeist types, and emits a [ToolPermissionRequested] event.
     * - [notify]: maps each [SessionUpdate] to an [AppSessionEvent] and forwards
     *   it to the bridge.
     */
    private inner class BridgeSessionOperations(
        private val sessionId: String,
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: kotlinx.serialization.json.JsonElement?,
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
                    kind = it.kind.name.lowercase(),
                )
            }

            emitToBridge(
                sessionId,
                AppSessionEvent.ToolPermissionRequested(
                    toolCallId = toolId,
                    requestId = toolId,
                    title = toolCall.title,
                    options = options,
                ),
            )

            val outcome = deferred.await()
            return RequestPermissionResponse(outcome = outcome)
        }

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: kotlinx.serialization.json.JsonElement?,
        ) {
            val appEvent = AcpSessionUpdateMapper.mapSessionUpdateToEvent(notification) ?: return
            emitToBridge(sessionId, appEvent)
        }
    }

    /**
     * Registers an SDK session and creates/reattaches a [SessionBridge].
     *
     * Returns the concrete [SessionBridge] so internal callers can call
     * [SessionBridge.emitEvent], [SessionBridge.markReady], etc. External
     * consumers receive only the [SessionPort] interface via [createSession],
     * [loadSession], or [getSession].
     */
    private suspend fun registerSession(session: ClientSession): SessionBridge {
        // Reuse the existing bridge if one was pre-created by loadSession so
        // buffered history can be committed into the same runtime.
        val bridge = sessionRegistry.getBridge(session.sessionId.value) ?: SessionBridge(session.sessionId.value, this)
        sessionRegistry.storeSdkSession(session.sessionId.value, session)
        sessionRegistry.storeBridge(session.sessionId.value, bridge)

        if (session.modesSupported) {
            val modes = session.availableModes.map {
                SessionMode(
                    id = it.id.value,
                    name = it.name,
                    description = it.description,
                )
            }
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ModesUpdated(
                    modes = modes,
                    currentModeId = session.currentMode.value.value,
                ),
            )
        }

        @OptIn(UnstableApi::class) if (session.modelsSupported) {
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
                ),
            )
            // Stand-in for missing SDK client update type: the SDK exposes
            // currentModel on the session object, but not a corresponding
            // SessionUpdate.CurrentModelUpdate event for notify() consumers,
            // so Ferngeist mirrors the initial selection into app state.
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ModelSelectionConfirmed(current),
            )
        }

        @OptIn(UnstableApi::class) if (session.configOptionsSupported) {
            emitToBridge(
                session.sessionId.value,
                AppSessionEvent.ConfigOptionsUpdated(
                    options = session.configOptions.value.map(
                        AcpSessionUpdateMapper::mapSdkConfigOption,
                    ),
                ),
            )
        }

        bridge.markReady()
        return bridge
    }

    /**
     * Resolves a [SessionBridge] by session ID and forwards an event.
     * Logs an error if no bridge is registered for the given ID — this
     * should only happen during torn-down sessions or registry corruption.
     */
    private suspend fun emitToBridge(
        sessionId: String,
        event: AppSessionEvent,
    ) {
        val bridge = sessionRegistry.getBridge(sessionId)
        if (bridge != null) {
            trace("emitToBridge sid=$sessionId event=${event::class.simpleName}")
            bridge.emitEvent(event)
            return
        }
        logError(
            "emitToBridge: NO BRIDGE for sessionId=$sessionId, " + "available keys=${sessionRegistry.bridgeIds()}",
        )
        diagnosticsStore.appendError(
            "session",
            "Session bridge not found for id: $sessionId",
        )
    }

    /** Debug-level trace via Android logcat. Swallows failures on non-Android hosts. */
    private fun trace(message: String) {
        runCatching { android.util.Log.d(TRACE_TAG, message) }
    }

    /** Error-level trace via Android logcat. Swallows failures on non-Android hosts. */
    private fun logError(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching { android.util.Log.e("AcpConnectionManager", message, throwable) }
    }

    /** Clears all session state (SDK sessions, bridges, pending permissions). */
    private fun clearAllSessionState(closeBridges: Boolean) {
        sessionRegistry.clearAll(closeBridges = closeBridges)
    }

    /** Clears a single session: removes SDK session, closes bridge, cancels pending permissions. */
    private fun clearSessionState(
        sessionId: String,
        closeBridge: Boolean,
    ) {
        sessionRegistry.clearSession(sessionId, closeBridge = closeBridge)
    }

    /**
     * Returns an existing loaded session as [SessionPort] if the SDK session
     * is registered. Used by [loadSession] to short-circuit when the session
     * is already active.
     */
    private fun getLoadedSession(sessionId: String): SessionPort? {
        if (!sessionRegistry.hasSdkSession(sessionId)) return null
        return sessionRegistry.getPort(sessionId)
    }

    /**
     * Checks whether an RPC error means the session is already loaded.
     * JSON-RPC code -32602 + "already loaded" in the message body.
     */
    private fun isSessionAlreadyLoadedError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException
        if (rpcError?.code != -32602) return false
        return rpcError.message.contains("already loaded", ignoreCase = true)
    }

    /** Updates the diagnostics flag tracking whether the server supports session/cancel. */
    private fun updateSessionCancelSupport(isSupported: Boolean) {
        diagnosticsStore.setSessionCancelSupport(isSupported)
    }

    /**
     * Inspects a session/cancel failure to determine whether the server
     * genuinely doesn't support the method (as opposed to a network error).
     */
    private fun handleSessionCancelFailure(error: Throwable) {
        if (isUnsupportedSessionCancel(error)) {
            updateSessionCancelSupport(isSupported = false)
        }
        diagnosticsStore.appendError(
            "session/cancel",
            formatAcpErrorMessage(error, "Cancel failed"),
        )
    }

    /**
     * Checks whether an error is specifically a "Method not found:
     * session/cancel" JSON-RPC response, indicating the server lacks cancel
     * support.
     */
    private fun isUnsupportedSessionCancel(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Method not found", ignoreCase = true) && message.contains(
            "session/cancel",
            ignoreCase = true,
        )
    }

    /**
     * Suspends until network connectivity is restored, used by reconnection
     * logic. Delegates to [AcpTransportClient.awaitConnectivityForReconnect].
     */
    internal suspend fun awaitConnectivityForReconnect() {
        transportClient.awaitConnectivityForReconnect()
    }

    /** Converts a Ferngeist [SessionConfigValue] to the SDK's wire format. */
    @OptIn(UnstableApi::class)
    private fun SessionConfigValue.toSdkValue(): SessionConfigOptionValue = when (this) {
        is SessionConfigValue.StringValue -> SessionConfigOptionValue.of(value)
        is SessionConfigValue.BoolValue -> SessionConfigOptionValue.of(value)
        is SessionConfigValue.UnknownValue -> error("Unsupported config option value: $this")
    }

    /**
     * Resets all connection-scoped state: clears capabilities, agent info,
     * auth methods, and all active sessions + bridges.
     */
    private fun resetConnectionState() {
        _agentCapabilities.value = null
        _agentInfo.value = null
        authMethodsFlow.value = emptyList()
        clearAllSessionState(closeBridges = true)
    }

    /**
     * Wraps an authentication-required error in
     * [AcpAuthenticationRequiredException]. Returns null if the error is not
     * auth-related.
     */
    private fun toAuthRequiredException(
        error: Throwable,
    ): AcpAuthenticationRequiredException? {
        if (!isAuthenticationRequiredError(error)) return null
        val challenge = currentAuthChallenge(
            message = formatAcpErrorMessage(error, "Authentication required"),
        ) ?: return null
        diagnosticsStore.appendError("authentication", challenge.message)
        return AcpAuthenticationRequiredException(challenge)
    }

    /**
     * Builds an [AcpAuthChallenge] from the current agent info and available
     * auth methods, or null if agent info hasn't been received yet.
     */
    private fun currentAuthChallenge(message: String): AcpAuthChallenge? {
        val agent = _agentInfo.value ?: return null
        val methods = authMethodsFlow.value
        return AcpAuthChallenge(
            agentInfo = agent,
            authMethods = methods,
            message = message,
        )
    }

    /**
     * Broad auth-required detection across JSON-RPC error messages.
     *
     * ACP documents auth_required as a condition code, but some servers emit
     * only human-readable messages. Matching "auth_required" / "authentication
     * required" / "requires authentication" catches all known variants without
     * hard-coding vendor names.
     */
    private fun isAuthenticationRequiredError(error: Throwable): Boolean {
        val rpcError = error as? JsonRpcException
        val message = buildString {
            append(error.message.orEmpty())
            if (rpcError != null) {
                append(' ')
                append(rpcError.data?.toString().orEmpty())
            }
        }
        return message.contains("auth_required", ignoreCase = true) || message.contains(
            "authentication required",
            ignoreCase = true,
        ) || message.contains("requires authentication", ignoreCase = true)
    }
}

/** Agent metadata from the ACP initialize handshake. */
data class AgentInfo(
    val name: String,
    val version: String,
)

/**
 * Events emitted by [AcpConnectionManager.events] for connection lifecycle
 * observers.
 */
sealed interface AcpManagerEvent {
    data object Connected : AcpManagerEvent

    data object Disconnected : AcpManagerEvent

    data class Initialized(
        val result: AcpInitializeResult,
    ) : AcpManagerEvent

    data class Authenticated(
        val methodId: String,
    ) : AcpManagerEvent

    data class Error(
        val throwable: Throwable,
    ) : AcpManagerEvent
}
