package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.setNotificationHandler
import com.agentclientprotocol.transport.acpProtocolOnClientWebSocket
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPermissionOption
import com.tamimarafat.ferngeist.core.model.SessionSummary
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

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

    private val _diagnostics = MutableStateFlow(ConnectionDiagnostics())
    val diagnostics: StateFlow<ConnectionDiagnostics> = _diagnostics.asStateFlow()

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var currentConfig: AcpConnectionConfig? = null

    private var httpClient: HttpClient? = null
    private var protocol: Protocol? = null
    private var sdkClient: Client? = null

    private val sessionBridges = ConcurrentHashMap<String, SessionBridge>()
    private val sdkSessions = ConcurrentHashMap<String, ClientSession>()
    private val sessionModelSelections = ConcurrentHashMap<String, SessionModelSelection>()
    private val pendingPermissionRequests = ConcurrentHashMap<String, CompletableDeferred<RequestPermissionOutcome>>()

    private var _agentInfo: AgentInfo? = null
    val agentInfo: AgentInfo?
        get() = _agentInfo

    private var supportsSessionCancel: Boolean? = null
    private val maxDiagnosticRpcEntries = 40
    private val maxDiagnosticErrorEntries = 20

    val isConnected: Boolean
        get() = _connectionState.value is AcpConnectionState.Connected

    suspend fun connect(config: AcpConnectionConfig): Boolean {
        currentConfig = config
        _connectionState.value = AcpConnectionState.Connecting
        setWebSocketState(WebSocketState.CONNECTING)

        return try {
            disconnectInternal()

            val url = "${config.scheme}://${config.host}"
            updateDiagnostics { current ->
                current.copy(
                    serverUrl = url,
                    agentInfo = null,
                    supportsSessionCancel = null
                )
            }

            val client = HttpClient(CIO) {
                install(WebSockets)
            }
            val protocolOptions = ProtocolOptions(protocolDebugName = "FerngeistACP")
            val wsProtocol = client.acpProtocolOnClientWebSocket(
                url = url,
                protocolOptions = protocolOptions
            ) {
                config.authToken?.takeIf { it.isNotBlank() }?.let {
                    headers.append("Authorization", "Bearer $it")
                }
            }
            wsProtocol.start()

            httpClient = client
            protocol = wsProtocol
            sdkClient = Client(wsProtocol)

            _connectionState.value = AcpConnectionState.Connected
            setWebSocketState(WebSocketState.OPEN)
            reconnectAttempts = 0
            scope.launch { _events.emit(AcpManagerEvent.Connected) }
            true
        } catch (e: Exception) {
            _connectionState.value = AcpConnectionState.Failed(e)
            setWebSocketState(WebSocketState.FAILED)
            appendDiagnosticError("connect", e.message ?: "Unknown connection failure")
            scheduleReconnect()
            false
        }
    }

    suspend fun initialize(): Boolean {
        val client = sdkClient ?: return false
        return runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "initialize")
            val info = client.initialize(
                clientInfo = ClientInfo(
                    capabilities = ClientCapabilities(),
                    implementation = Implementation(name = "Ferngeist", version = "1.0.0")
                )
            )

            val mapped = AgentInfo(
                name = info.implementation?.name?.takeIf { it.isNotBlank() } ?: "Agent",
                version = info.implementation?.version?.takeIf { it.isNotBlank() } ?: "unknown"
            )
            _agentInfo = mapped
            supportsSessionCancel = true

            updateDiagnostics { current ->
                current.copy(
                    agentInfo = mapped,
                    supportsSessionCancel = supportsSessionCancel
                )
            }

            scope.launch { _events.emit(AcpManagerEvent.Initialized(mapped)) }
            true
        }.getOrElse { e ->
            appendDiagnosticError("initialize", e.message ?: "Initialization failed")
            scope.launch { _events.emit(AcpManagerEvent.Error(e)) }
            false
        }
    }

    suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        reconnectJob?.cancel()
        reconnectJob = null

        sdkSessions.clear()
        sessionBridges.clear()
        sessionModelSelections.clear()
        pendingPermissionRequests.values.forEach { it.cancel() }
        pendingPermissionRequests.clear()

        runCatching { protocol?.close() }
        protocol = null
        sdkClient = null

        runCatching { httpClient?.close() }
        httpClient = null

        _connectionState.value = AcpConnectionState.Disconnected
        setWebSocketState(WebSocketState.CLOSED)
        scope.launch { _events.emit(AcpManagerEvent.Disconnected) }
    }

    suspend fun listSessions(): List<SessionSummary> {
        val client = sdkClient ?: return emptyList()
        return runCatching {
            @OptIn(UnstableApi::class)
            client.listSessions().toList().map { sessionInfo ->
                SessionSummary(
                    id = sessionInfo.sessionId.value,
                    title = sessionInfo.title,
                    cwd = sessionInfo.cwd,
                    updatedAt = parseIsoOrMillis(sessionInfo.updatedAt)
                )
            }
        }.getOrElse {
            appendDiagnosticError("session/list", it.message ?: "Failed to list sessions")
            emptyList()
        }
    }

    suspend fun createSession(cwd: String = "/"): SessionBridge? {
        val client = sdkClient ?: return null
        return runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/new")
            val session = client.newSession(
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList<McpServer>()),
                operationsFactory = operationsFactory
            )
            registerSession(session, cwd)
        }.getOrElse {
            appendDiagnosticError("session/new", it.message ?: "Failed to create session")
            null
        }
    }

    suspend fun loadSession(
        sessionId: String,
        cwd: String,
        fallbackHistoryTimestampMs: Long? = null,
    ): SessionBridge? {
        trace("loadSession:start sessionId=$sessionId cwd=$cwd fallbackTs=$fallbackHistoryTimestampMs")
        val client = sdkClient ?: run {
            android.util.Log.e("AcpConnectionManager", "loadSession: sdkClient is NULL, returning null")
            return null
        }
        val proto = protocol ?: run {
            android.util.Log.e("AcpConnectionManager", "loadSession: protocol is NULL, returning null")
            return null
        }

        // --- Deadlock workaround ---
        // The SDK's built-in session/update notification handler calls
        // getSessionOrThrow(sessionId) which suspends on
        // _currentlyInitializingSessionsCount waiting for it to reach 0.
        // But loadSession() increments that counter BEFORE sending the RPC,
        // and the server may send session/update notifications before the
        // session/load response arrives.  Since notifications are processed
        // inline on the Protocol message loop, the handler blocks the loop
        // and the response can never be read → deadlock.
        //
        // Fix: temporarily replace the session/update handler with one that
        // routes replay notifications directly through our BridgeSessionOperations,
        // bypassing the SDK's session lookup entirely.  After loadSession()
        // returns the SDK session is fully registered, so we restore a handler
        // that delegates to the SDK session (preserving internal state updates).

        // Pre-create the bridge so replay notifications have somewhere to go.
        val loadingSessionId = SessionId(sessionId)
        val earlyBridge = SessionBridge(sessionId, this, scope)
        sessionBridges[sessionId] = earlyBridge
        earlyBridge.beginHydration()
        trace("loadSession:earlyBridge created and hydration started sessionId=$sessionId")
        val earlyOps = BridgeSessionOperations(sessionId)
        var discoveredAliasSessionId: String? = null


        proto.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { notification: SessionNotification ->
            val sid = notification.sessionId.value
            trace(
                "loadSession:preload-notification sid=$sid requested=$sessionId alias=$discoveredAliasSessionId " +
                    "update=${notification.update::class.simpleName}"
            )
            if (sid == sessionId || sid == discoveredAliasSessionId) {
                // Route directly to our operations, bypassing getSessionOrThrow()
                earlyOps.notify(notification.update, notification._meta)
            } else {
                val unknownSid = !sessionBridges.containsKey(sid) && !sdkSessions.containsKey(sid)
                if (discoveredAliasSessionId == null && unknownSid) {
                    discoveredAliasSessionId = sid
                    trace("loadSession:discoveredAliasSessionId=$sid for requested=$sessionId")
                    earlyOps.notify(notification.update, notification._meta)
                    return@setNotificationHandler
                }
                // For other sessions, try to route via the SDK session if it exists
                val existingSdkSession = sdkSessions[sid]
                if (existingSdkSession != null) {
                    val appEvent = mapSessionUpdateToEvent(notification.update)
                    if (appEvent != null) {
                        emitToBridge(sid, appEvent)
                    }
                } else {
                    val appEvent = mapSessionUpdateToEvent(notification.update)
                    if (appEvent != null) {
                        emitToBridge(sid, appEvent)
                    }
                }
            }
        }

        return try {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/load")
            val session = client.loadSession(
                sessionId = loadingSessionId,
                sessionParameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList<McpServer>()),
                operationsFactory = operationsFactory
            )

            // Some servers may resolve to an alias session ID. Keep the pre-created
            // replay bridge and move it under the resolved ID so early replay isn't lost.
            val resolvedSessionId = session.sessionId.value
            if (resolvedSessionId != sessionId) {
                trace("loadSession:resolvedSessionId differs requested=$sessionId resolved=$resolvedSessionId")
                sessionBridges.remove(sessionId)?.let { preCreated ->
                    sessionBridges[resolvedSessionId] = preCreated
                }
            }

            // Restore the normal SDK-delegating notification handler now that the
            // session is fully registered inside the SDK's _sessions map.
            installPostLoadNotificationHandler(proto)

            val bridge = registerSession(session, cwd)
            bridge.completeHydration()
            trace("loadSession:completeHydration emitted resolvedSessionId=${bridge.sessionId}")
            // Keep this event for compatibility with existing event consumers.
            bridge.emitEvent(AppSessionEvent.SessionLoadComplete)
            trace("loadSession:SessionLoadComplete emitted sessionId=${bridge.sessionId}")
            bridge
        } catch (t: Throwable) {
            android.util.Log.e("AcpConnectionManager", "loadSession: EXCEPTION: ${t::class.simpleName}: ${t.message}", t)
            trace("loadSession:failure sessionId=$sessionId error=${t.message}")
            // Restore the handler even on failure
            installPostLoadNotificationHandler(proto)
            // Clean up the early bridge if load failed
            earlyBridge.failHydration(t.message ?: "Failed to load session")
            sessionBridges.remove(sessionId)?.close()
            appendDiagnosticError("session/load", t.message ?: "Failed to load session")
            null
        }
    }

    /**
     * Installs a notification handler that properly routes session/update
     * notifications through the SDK session's handleNotification path for
     * already-registered sessions, and falls back to our bridge for others.
     *
     * This is the "steady state" handler used after loadSession() completes.
     */
    private fun installPostLoadNotificationHandler(proto: Protocol) {
        proto.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { notification: SessionNotification ->
            val sid = notification.sessionId.value
            trace(
                "postLoad-notification sid=$sid update=${notification.update::class.simpleName} " +
                    "hasBridge=${sessionBridges.containsKey(sid)} hasSdkSession=${sdkSessions.containsKey(sid)}"
            )
            // Try to find the SDK session; if it exists, the SDK's Client
            // has it in _sessions and getSession() will work.
            // But we can't call Client.getSessionOrThrow() (it's private),
            // and Client.getSession() throws if not completed.
            // Instead, route through our BridgeSessionOperations for all sessions.
            val appEvent = mapSessionUpdateToEvent(notification.update)
            if (appEvent != null) {
                emitToBridge(sid, appEvent)
            }
        }
    }

    suspend fun sendSessionMessage(sessionId: String, content: String, images: List<Pair<String, String>> = emptyList()) {
        val bridge = sessionBridges[sessionId]
            ?: throw IllegalStateException("Session bridge missing for sessionId=$sessionId")
        val session = sdkSessions[sessionId]
            ?: throw IllegalStateException("SDK session missing for sessionId=$sessionId")

        appendRpcEntry(RpcDirection.OutboundRequest, "session/prompt")

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
                    val appEvent = mapSessionUpdateToEvent(event.update)
                    if (appEvent != null) {
                        bridge.emitEvent(appEvent)
                    }
                }
                is Event.PromptResponseEvent -> {
                    receivedPromptResponse = true
                    val usage = extractUsageFromMeta(event.response._meta)
                    if (usage != null) {
                        applyUsageToDiagnostics(usage)
                        bridge.emitEvent(usage)
                    }
                    bridge.emitEvent(
                        AppSessionEvent.TurnComplete(mapStopReason(event.response.stopReason))
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
        val session = sdkSessions[sessionId] ?: return
        runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/cancel")
            session.cancel()
        }.onFailure {
            appendDiagnosticError("session/cancel", it.message ?: "Cancel failed")
        }
    }

    suspend fun setSessionMode(sessionId: String, modeId: String) {
        val session = sdkSessions[sessionId] ?: return
        runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/set_mode")
            session.setMode(SessionModeId(modeId))
        }.onFailure {
            appendDiagnosticError("session/set_mode", it.message ?: "Set mode failed")
        }
    }

    suspend fun setSessionModel(sessionId: String, modelId: String) {
        val session = sdkSessions[sessionId] ?: return
        runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/set_model")
            @OptIn(UnstableApi::class)
            session.setModel(com.agentclientprotocol.model.ModelId(modelId))
        }.onFailure {
            appendDiagnosticError("session/set_model", it.message ?: "Set model failed")
        }
    }

    suspend fun setSessionConfigOption(sessionId: String, optionId: String, value: String) {
        val session = sdkSessions[sessionId] ?: return
        runCatching {
            appendRpcEntry(RpcDirection.OutboundRequest, "session/set_config_option")
            @OptIn(UnstableApi::class)
            session.setConfigOption(SessionConfigId(optionId), SessionConfigOptionValue.of(value))
        }.onFailure {
            appendDiagnosticError("session/set_config_option", it.message ?: "Set config option failed")
        }
    }

    suspend fun respondPermissionSelected(sessionId: String, toolCallId: String, optionId: String) {
        val deferred = pendingPermissionRequests.remove(toolCallId) ?: return
        deferred.complete(RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    suspend fun respondPermissionCancelled(sessionId: String, toolCallId: String) {
        val deferred = pendingPermissionRequests.remove(toolCallId) ?: return
        deferred.complete(RequestPermissionOutcome.Cancelled)
        emitToBridge(sessionId, AppSessionEvent.ToolPermissionResolved(toolCallId))
    }

    fun getSession(sessionId: String): SessionBridge? = sessionBridges[sessionId]

    fun removeSession(sessionId: String) {
        sessionBridges.remove(sessionId)?.close()
        sdkSessions.remove(sessionId)
    }

    private val operationsFactory = ClientOperationsFactory { sessionId, _ ->
        BridgeSessionOperations(sessionId.value)
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
            pendingPermissionRequests[toolId] = deferred

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
            val appEvent = mapSessionUpdateToEvent(notification) ?: return
            emitToBridge(sessionId, appEvent)
        }
    }

    private suspend fun registerSession(session: ClientSession, cwd: String): SessionBridge {
        // Reuse the existing bridge if one was pre-created (e.g. by loadSession's
        // deadlock workaround), otherwise create a fresh one.
        val bridge = sessionBridges[session.sessionId.value]
            ?: SessionBridge(session.sessionId.value, this, scope)
        sdkSessions[session.sessionId.value] = session
        sessionBridges[session.sessionId.value] = bridge

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
            if (modelChoices.isNotEmpty()) {
                emitToBridge(
                    session.sessionId.value,
                    AppSessionEvent.ConfigOptionsUpdated(
                        listOf(
                            SessionConfigOption(
                                id = "model",
                                name = "Model",
                                description = "Select a model",
                                kind = "select",
                                currentValue = current,
                                options = modelChoices
                            )
                        )
                    )
                )
            }
            emitToBridge(session.sessionId.value, AppSessionEvent.ModelSelectionConfirmed(current))
            sessionModelSelections[session.sessionId.value] = SessionModelSelection(providerId = null, modelRef = current)
        }

        // unused currently but kept for parity with old behavior
        cwd

        bridge.markReady()
        return bridge
    }

    private suspend fun emitToBridge(sessionId: String, event: AppSessionEvent) {
        val bridge = sessionBridges[sessionId]
        if (bridge != null) {
            trace("emitToBridge sid=$sessionId event=${event::class.simpleName}")
            bridge.emitEvent(event)
            return
        }
        android.util.Log.e("AcpConnectionManager", "emitToBridge: NO BRIDGE for sessionId=$sessionId, available keys=${sessionBridges.keys}")
        appendDiagnosticError("session", "Session bridge not found for id: $sessionId")
    }

    private fun trace(message: String) {
        android.util.Log.d(TRACE_TAG, message)
    }

    @OptIn(UnstableApi::class)
    private fun mapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent? {
        return when (update) {
            is SessionUpdate.UserMessageChunk -> AppSessionEvent.UserMessage(
                text = extractText(update.content),
                append = true,
            )
            is SessionUpdate.AgentMessageChunk -> AppSessionEvent.AgentMessage(extractText(update.content))
            is SessionUpdate.AgentThoughtChunk -> AppSessionEvent.AgentThought(extractText(update.content))
            is SessionUpdate.ToolCall -> AppSessionEvent.ToolCallStarted(
                toolCallId = update.toolCallId.value,
                title = update.title,
                kind = update.kind?.toString()?.lowercase(),
                status = update.status?.toString()?.lowercase(),
            )
            is SessionUpdate.ToolCallUpdate -> AppSessionEvent.ToolCallUpdated(
                toolCallId = update.toolCallId.value,
                status = update.status?.toString()?.lowercase(),
                title = update.title,
                kind = update.kind?.toString()?.lowercase(),
                output = update.content?.joinToString("\n") { it.toString() },
                rawOutput = update.rawOutput?.toString(),
            )
            is SessionUpdate.PlanUpdate -> AppSessionEvent.PlanUpdated(
                content = update.entries.joinToString("\n") { it.toString() }
            )
            is SessionUpdate.AvailableCommandsUpdate -> AppSessionEvent.CommandsUpdated(
                update.availableCommands.map { it.name }
            )
            is SessionUpdate.CurrentModeUpdate -> AppSessionEvent.ModeChanged(update.currentModeId.value)
            is SessionUpdate.UsageUpdate -> {
                val costUsd = update.cost?.takeIf { it.currency.equals("USD", ignoreCase = true) }?.amount
                AppSessionEvent.UsageUpdated(
                    totalTokens = update.used.toInt(),
                    contextWindowTokens = update.size.toInt(),
                    costUsd = costUsd,
                )
            }
            is SessionUpdate.ConfigOptionUpdate -> {
                val mapped = update.configOptions.map { sdkOption ->
                    mapSdkConfigOption(sdkOption)
                }
                AppSessionEvent.ConfigOptionsUpdated(mapped)
            }
            is SessionUpdate.SessionInfoUpdate -> AppSessionEvent.SessionInfoUpdated(
                title = update.title,
                updatedAt = update.updatedAt,
            )
            is SessionUpdate.UnknownSessionUpdate -> AppSessionEvent.Unknown(update.toString())
            else -> AppSessionEvent.Unknown(update.toString())
        }
    }

    private fun extractText(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            else -> content.toString()
        }
    }

    @OptIn(UnstableApi::class)
    private fun mapSdkConfigOption(
        sdkOption: com.agentclientprotocol.model.SessionConfigOption
    ): com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption {
        return when (sdkOption) {
            is com.agentclientprotocol.model.SessionConfigOption.Select -> {
                val choices = when (val opts = sdkOption.options) {
                    is com.agentclientprotocol.model.SessionConfigSelectOptions.Flat ->
                        opts.options.map { selectOpt ->
                            SessionConfigChoice(
                                id = selectOpt.value.value,
                                label = selectOpt.name,
                                value = selectOpt.value.value,
                                description = selectOpt.description,
                            )
                        }
                    is com.agentclientprotocol.model.SessionConfigSelectOptions.Grouped ->
                        opts.groups.flatMap { group ->
                            group.options.map { selectOpt ->
                                SessionConfigChoice(
                                    id = selectOpt.value.value,
                                    label = selectOpt.name,
                                    value = selectOpt.value.value,
                                    description = selectOpt.description,
                                )
                            }
                        }
                }
                com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    kind = "select",
                    currentValue = sdkOption.currentValue.value,
                    options = choices,
                )
            }
            else -> {
                // BooleanOption or any future subtypes
                com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    kind = "boolean",
                    currentValue = null,
                )
            }
        }
    }

    private fun mapStopReason(reason: StopReason): String {
        return when (reason) {
            StopReason.END_TURN -> "end_turn"
            StopReason.MAX_TOKENS -> "max_tokens"
            StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
            StopReason.REFUSAL -> "refusal"
            StopReason.CANCELLED -> "cancelled"
        }
    }

    private fun extractUsageFromMeta(meta: kotlinx.serialization.json.JsonElement?): AppSessionEvent.UsageUpdated? {
        if (meta == null) return null
        return null
    }

    private fun parseIsoOrMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()

        // Numeric epoch support: handle both seconds and milliseconds.
        raw.toLongOrNull()?.let { numeric ->
            return if (numeric in 1_000_000_000L..9_999_999_999L) {
                numeric * 1000L
            } else {
                numeric
            }
        }

        // ISO-8601 support (Instant / OffsetDateTime forms).
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }


    private fun scheduleReconnect() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            val config = currentConfig ?: return@launch
            while (!isConnected) {
                reconnectAttempts++
                delay((1000L * reconnectAttempts).coerceAtMost(5000L))
                if (connect(config)) {
                    initialize()
                    break
                }
            }
            reconnectJob = null
        }
    }

    private fun setWebSocketState(state: WebSocketState) {
        updateDiagnostics { current -> current.copy(websocketState = state) }
    }

    private fun appendRpcEntry(
        direction: RpcDirection,
        method: String,
        rpcId: String? = null,
        summary: String? = null,
    ) {
        val entry = RpcDiagnosticEntry(
            direction = direction,
            method = method,
            rpcId = rpcId,
            summary = summary
        )
        updateDiagnostics { current ->
            val next = (current.recentRpc + entry).takeLast(maxDiagnosticRpcEntries)
            current.copy(recentRpc = next)
        }
    }

    private fun appendDiagnosticError(source: String, message: String) {
        val entry = DiagnosticErrorEntry(source = source, message = message)
        updateDiagnostics { current ->
            val next = (current.recentErrors + entry).takeLast(maxDiagnosticErrorEntries)
            current.copy(recentErrors = next)
        }
    }

    private fun applyUsageToDiagnostics(usage: AppSessionEvent.UsageUpdated) {
        updateDiagnostics { current ->
            current.copy(
                lastTotalTokens = usage.totalTokens ?: current.lastTotalTokens,
                lastContextWindowTokens = usage.contextWindowTokens ?: current.lastContextWindowTokens,
                lastCostAmount = usage.costUsd ?: current.lastCostAmount,
                lastCostCurrency = if (usage.costUsd != null) "USD" else current.lastCostCurrency
            )
        }
    }

    private inline fun updateDiagnostics(
        transform: (ConnectionDiagnostics) -> ConnectionDiagnostics,
    ) {
        _diagnostics.update { current ->
            transform(current).copy(lastUpdatedAtMs = System.currentTimeMillis())
        }
    }
}

private data class SessionModelSelection(
    val providerId: String?,
    val modelRef: String,
)

data class AgentInfo(
    val name: String,
    val version: String
)

sealed interface AcpManagerEvent {
    data object Connected : AcpManagerEvent
    data object Disconnected : AcpManagerEvent
    data class Initialized(val agentInfo: AgentInfo) : AcpManagerEvent
    data class Error(val throwable: Throwable) : AcpManagerEvent
}
