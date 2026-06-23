package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * ACP implementation of [ChatSessionFacade].
 *
 * Owns the full session lifecycle: connection, initialization, bridge
 * attachment, recovery, and user action dispatch. Maps all ACP types
 * to chat-domain equivalents before exposing them to the feature layer.
 */
class AcpChatSessionFacade(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnectionManager,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
    private val sessionLoadTimeoutMs: Long = 20_000L,
    private val bridgeRecoveryRetryDelayMs: Long = 3_000L,
) : ChatSessionFacade {

    // ---- Connection state mirroring ----
    private val _connectionState =
        MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    override val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    private val _diagnostics =
        MutableStateFlow(ChatConnectionDiagnostics())
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = _diagnostics.asStateFlow()

    // ---- Session state ----
    private val _sessionSnapshot = MutableStateFlow<ChatSessionSnapshot?>(null)
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = _sessionSnapshot.asStateFlow()

    private val _agentCapabilities =
        MutableStateFlow(ChatAgentCapabilities())
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = _agentCapabilities.asStateFlow()

    // ---- Events (one-shot) ----
    // SharedFlow is used for one-off signals so repeated emissions are not lost.
    private val _loadFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val loadFailed: SharedFlow<String> = _loadFailed

    private val _operationError = MutableSharedFlow<ChatOperationError>(extraBufferCapacity = 1)
    override val operationError: SharedFlow<ChatOperationError> = _operationError

    private val _streamingCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val streamingCancelled: SharedFlow<Unit> = _streamingCancelled

    private val _cancelUnsupported = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val cancelUnsupported: SharedFlow<Unit> = _cancelUnsupported

    private val _sessionReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionReady: SharedFlow<Unit> = _sessionReady

    // ---- Internal state ----
    private var activeSessionId: String = initialSessionId
    private var sessionBridge: SessionPort? = null
    private var bridgeObserverJobs: List<Job> = emptyList()
    private var bridgeRecoveryJob: Job? = null
    private var pendingModelSelectionId: String? = null
    private var currentAcpCapabilities: AgentCapabilities? = null
    private var shouldRecoverBridge: Boolean = false
    private val bridgeOperationMutex = Mutex()

    private val sessionModelUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val modelUpdated: SharedFlow<Unit> = sessionModelUpdated

    init {
        scope.launch {
            connectionManager.connectionState.collect { state ->
                _connectionState.value = mapConnectionState(state)
            }
        }
        scope.launch {
            connectionManager.diagnostics.collect { diag ->
                _diagnostics.value = mapDiagnostics(diag)
            }
        }
    }

    // ---- Public API ----

    /**
     * Loads an existing session or falls back to creating a new one.
     *
     * The load flow:
     * 1. Ensures the transport is connected and initialised.
     * 2. Tries [AcpConnectionManager.getSession] for an already-open bridge.
     * 3. If the agent advertises `session/load`, calls [AcpConnectionManager.loadSession]
     *    with a [sessionLoadTimeoutMs] deadline.
     * 4. On timeout or destroyed-bridge error during load, creates a fresh session
     *    so the user can keep chatting without re-entering the screen.
     */
    override suspend fun loadSession() {
        bridgeOperationMutex.withLock {
            shouldRecoverBridge = true
            cancelBridgeRecovery()

            if (!ensureConnectedAndInitialized()) {
                _loadFailed.emit("Disconnected. Reconnect to refresh this session.")
                return
            }

            publishCapabilities()

            connectionManager.getSession(initialSessionId)?.let { existing ->
                attachSessionBridge(existing)
                _sessionReady.emit(Unit)
                return
            }

            val capabilities =
                currentAcpCapabilities ?: connectionManager.agentCapabilities.value
            if (capabilities != null && !capabilities.loadSession) {
                shouldRecoverBridge = false
                _loadFailed.emit(
                    "This agent does not advertise session/load support."
                )
                return
            }

            var loadTimedOut = false
            val bridge =
                try {
                    withTimeout(sessionLoadTimeoutMs) {
                        connectionManager.loadSession(initialSessionId, cwd)
                    }
                } catch (_: AcpAuthenticationRequiredException) {
                    _loadFailed.emit(
                        "ACP authentication is required for this server. " +
                            "Return to the session list and authenticate first."
                    )
                    return
                } catch (_: TimeoutCancellationException) {
                    loadTimedOut = true
                    null
                } catch (error: Exception) {
                    if (isDestroyedBridgeStreamError(error)) {
                        // If the bridge process restarted mid-load, create a new session
                        // so the user can keep chatting without reopening the screen.
                        val fallbackBridge =
                            runCatching { connectionManager.createSession(cwd) }.getOrNull()
                        if (fallbackBridge != null) {
                            attachSessionBridge(fallbackBridge)
                            _sessionReady.emit(Unit)
                            _operationError.emit(
                                ChatOperationError(
                                    message = "The ACP bridge process restarted while loading this session. Opened a new live session.",
                                    stopStreaming = false,
                                ),
                            )
                            return
                        }
                    }
                    _loadFailed.emit(formatAcpErrorMessage(error, "Failed to load session"))
                    return
                }

            if (bridge != null) {
                attachSessionBridge(bridge)
                return
            }

            _loadFailed.emit(
                if (loadTimedOut) {
                    "Session load timed out. Check server connection and retry."
                } else {
                    "Could not load this session. Check connection and retry."
                },
            )
        }
    }

    /**
     * Reacts to connection-state changes from the transport.
     *
     * On reconnect the facade tries to reattach the session bridge automatically;
     * on disconnect/error the bridge is invalidated so state is not stale.
     */
    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        when (connectionState) {
            ChatConnectionState.Connected -> scheduleBridgeRecovery()
            else -> invalidateActiveBridge()
        }
    }

    /**
     * Sends a user message through the active bridge.
     *
     * If no bridge is attached yet, attempts a late-bound session load/create.
     * Image support is gated on the agent's capability advertisement.
     */
    override suspend fun sendMessage(text: String, images: List<ChatImageData>) {
        if (text.isBlank() && images.isEmpty()) return

        val bridge = ensureSessionReadyForSend()
        if (bridge == null) {
            _operationError.emit(
                ChatOperationError("Session is not ready. Please retry in a moment.", false),
            )
            return
        }

        val capabilities = connectionManager.agentCapabilities.value
        if (images.isNotEmpty() && capabilities != null && !capabilities.promptCapabilities.image) {
            _operationError.emit(
                ChatOperationError("This agent does not advertise image prompt support.", false),
            )
            return
        }

        val imagePairs = images.map { Pair(it.base64, it.mimeType) }
        try {
            bridge.sendPrompt(text, imagePairs)
        } catch (error: Exception) {
            _operationError.emit(ChatOperationError(userFacingSendError(error), true))
        }
    }

    /**
     * Cancels the current streaming turn.
     *
     * Emits [cancelUnsupported] if the server lacks the `session/cancel` RPC method.
     */
    override suspend fun cancelStreaming() {
        val bridge = sessionBridge
        if (bridge == null) {
            _operationError.emit(
                ChatOperationError("Session is not ready. Please retry in a moment.", false),
            )
            return
        }
        val result = runCatching { bridge.cancel() }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (isSessionCancelUnsupported(error)) {
                _cancelUnsupported.emit(Unit)
                return
            }
            _operationError.emit(ChatOperationError("Failed to cancel the current turn", false))
            return
        }
        _streamingCancelled.emit(Unit)
    }

    /**
     * Updates a session config option by mapping the chat-domain value back
     * to an ACP [SessionConfigValue] before dispatching through the bridge.
     *
     * Tracks pending model selections so the UI can show a confirmation toast
     * only when the server confirms the user's chosen model ID.
     */
    override suspend fun setConfigOption(optionId: String, value: ChatConfigValue) {
        val bridge = sessionBridge
        if (bridge == null) {
            _operationError.emit(
                ChatOperationError("Session is not ready. Please retry in a moment.", false),
            )
            return
        }
        val option =
            bridge.snapshot.value.configOptions
                .firstOrNull { it.id == optionId }
        val selectedModelId =
            if (option?.category is SessionConfigCategory.Model) {
                (value as? ChatConfigValue.StringValue)?.value
            } else {
                null
            }
        if (!selectedModelId.isNullOrBlank()) {
            // Track the pending model selection so we can show a "Model updated" toast
            // only when the confirmation matches the user's selection.
            pendingModelSelectionId = selectedModelId
        }
        bridge.setConfigOption(optionId, toAcpConfigValue(value))
    }

    /** Forwards a permission grant to the active bridge. */
    override suspend fun grantPermission(toolCallId: String, optionId: String) {
        sessionBridge?.grantPermission(toolCallId, optionId)
    }

    /** Forwards a permission denial to the active bridge. */
    override suspend fun denyPermission(toolCallId: String) {
        sessionBridge?.denyPermission(toolCallId)
    }

    /** Tears down bridge observers, cancels recovery, and invalidates the active bridge. */
    override fun clear() {
        cancelBridgeRecovery()
        invalidateActiveBridge()
        clearBridgeObservers()
    }

    // ---- Internal ----

    /**
     * Ensures the transport is connected and initialised.
     * Returns false and emits [loadFailed] if connection or auth fails.
     */
    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            publishCapabilities()
            return true
        }
        val server = launchableTargetRepository.getTarget(serverId) ?: return false
        val connected =
            when (server) {
                is LaunchableTarget.GatewayAgent -> {
                    val config = buildGatewayConnectionConfig(server) ?: return false
                    connectionManager.connect(config)
                }
                is LaunchableTarget.Manual -> {
                    connectionManager.connect(
                        AcpConnectionConfig(
                            scheme = server.server.scheme,
                            host = server.server.host,
                            preferredAuthMethodId = server.server.preferredAuthMethodId,
                            serverDisplayName = server.name,
                        ),
                    )
                }
                is LaunchableTarget.Paseo -> {
                    // Paseo targets are routed to PaseoChatSessionFacade by the dispatching
                    // factory and never reach the ACP facade; guard defensively.
                    _loadFailed.emit("Paseo targets are not handled by the ACP transport.")
                    return false
                }
            }
        if (!connected) return false
        return when (val result = connectionManager.initialize()) {
            is AcpInitializeResult.Ready -> {
                currentAcpCapabilities = result.agentCapabilities
                _agentCapabilities.value = mapCapabilities(result.agentCapabilities)
                true
            }
            is AcpInitializeResult.AuthenticationRequired -> {
                shouldRecoverBridge = false
                _loadFailed.emit(
                    "ACP authentication is required for this server. " +
                        "Reconnect from the server list and choose an auth method.",
                )
                false
            }
            null -> false
        }
    }

    /**
     * Builds a connection config for a gateway-backed agent by starting a fresh
     * runtime on the gateway and obtaining the WebSocket handoff.
     */
    private suspend fun buildGatewayConnectionConfig(
        target: LaunchableTarget.GatewayAgent,
    ): AcpConnectionConfig? {
        val gatewaySource = target.gatewaySource
        if (gatewaySource.gatewayCredential.isBlank()) {
            _loadFailed.emit("Gateway is not paired for ${target.name}.")
            return null
        }
        return try {
            val refreshedSource =
                refreshGatewaySourceIfNeeded(gatewaySource, gatewayRepository, gatewaySourceRepository)
            val runtime =
                gatewayRepository.startAgent(
                    scheme = refreshedSource.scheme,
                    host = refreshedSource.host,
                    gatewayCredential = refreshedSource.gatewayCredential,
                    agentId = target.binding.agentId,
                )
            val handoff =
                gatewayRepository.connectRuntime(
                    scheme = refreshedSource.scheme,
                    host = refreshedSource.host,
                    gatewayCredential = refreshedSource.gatewayCredential,
                    runtimeId = runtime.id,
                    sessionMode = "resilient",
                )
            AcpConnectionConfig(
                scheme = refreshedSource.scheme,
                host = refreshedSource.host,
                webSocketUrl = resolveGatewayWebSocketUrl(refreshedSource, handoff),
                webSocketBearerToken = handoff.bearerToken,
                preferredAuthMethodId = target.binding.preferredAuthMethodId,
                gatewayRuntimeId = runtime.id,
                gatewaySourceId = refreshedSource.id,
                serverDisplayName = target.name,
                sessionId = handoff.sessionId,
                attachToken = handoff.attachToken,
                gatewayScheme = refreshedSource.scheme,
                gatewayHost = refreshedSource.host,
                gatewayCredential = refreshedSource.gatewayCredential,
            )
        } catch (error: Throwable) {
            _loadFailed.emit(
                "Failed to reconnect to ${target.name}: ${error.message ?: "unknown error"}",
            )
            null
        }
    }

    /** Stores the bridge reference and resets session-local state. */
    private fun bindSessionBridge(bridge: SessionPort) {
        activeSessionId = bridge.sessionId
        sessionBridge = bridge
        pendingModelSelectionId = null
        shouldRecoverBridge = true
        cancelBridgeRecovery()
    }

    /** Binds the bridge and starts collecting its snapshot / event flows. */
    private fun attachSessionBridge(bridge: SessionPort) {
        bindSessionBridge(bridge)
        observeSessionBridge(bridge)
    }

    /** Launches snapshot and model-selection collection coroutines. */
    private fun observeSessionBridge(bridge: SessionPort) {
        clearBridgeObservers()
        bridgeObserverJobs =
            listOf(
                scope.launch {
                    bridge.snapshot.collect { snapshot ->
                        _sessionSnapshot.value = mapSnapshot(snapshot)
                    }
                },
                scope.launch {
                    bridge.modelSelectionEvents?.collect { event ->
                        handleModelSelectionConfirmed(event)
                    }
                },
            )
    }

    /** Cancels all active bridge observer coroutines. */
    private fun clearBridgeObservers() {
        bridgeObserverJobs.forEach { it.cancel() }
        bridgeObserverJobs = emptyList()
    }

    /**
     * Fires [sessionModelUpdated] when the confirmed model matches the
     * user's pending selection (or the selection is blank).
     */
    private suspend fun handleModelSelectionConfirmed(event: AppSessionEvent.ModelSelectionConfirmed) {
        val pendingModel = pendingModelSelectionId
        if (pendingModel != null &&
            (event.modelId.isNullOrBlank() || event.modelId == pendingModel)
        ) {
            pendingModelSelectionId = null
            sessionModelUpdated.emit(Unit)
        }
    }

    /**
     * Ensures a connected & initialised session exists, creating one if necessary.
     * @return the active [SessionPort], or null if connection / creation fails.
     */
    private suspend fun ensureSessionReadyForSend(): SessionPort? {
        sessionBridge?.let { return it }
        if (!ensureConnectedAndInitialized()) return null

        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            _sessionReady.emit(Unit)
            return existing
        }

        val created =
            try {
                connectionManager.createSession(cwd)
            } catch (_: AcpAuthenticationRequiredException) {
                _loadFailed.emit(
                    "ACP authentication is required for this server. " +
                        "Return to the session list and authenticate first.",
                )
                null
            } ?: return null
        attachSessionBridge(created)
        _sessionReady.emit(Unit)
        return created
    }

    /**
     * Launches a background coroutine that retries bridge recovery until
     * a session becomes available or the bridge is removed.
     */
    private fun scheduleBridgeRecovery() {
        if (!shouldRecoverBridge || sessionBridge != null || bridgeRecoveryJob != null) return

        bridgeRecoveryJob =
            scope.launch {
                try {
                    while (shouldRecoverBridge && sessionBridge == null) {
                        if (!connectionManager.isConnected) break

                        val recovered =
                            bridgeOperationMutex.withLock {
                                if (sessionBridge != null) {
                                    sessionBridge
                                } else {
                                    recoverSessionBridge()
                                }
                            }
                        if (recovered != null) {
                            _sessionReady.emit(Unit)
                            break
                        }

                        delay(bridgeRecoveryRetryDelayMs)
                    }
                } finally {
                    bridgeRecoveryJob = null
                }
            }
    }

    /** Cancels any active bridge recovery coroutine. */
    private fun cancelBridgeRecovery() {
        bridgeRecoveryJob?.cancel()
        bridgeRecoveryJob = null
    }

    /** Clears the bridge reference, pending selection, and all observers. */
    private fun invalidateActiveBridge() {
        sessionBridge = null
        pendingModelSelectionId = null
        clearBridgeObservers()
    }

    /**
     * Tries to re-attach an existing session or (if the agent does not
     * support [loadSession]) create a new one. Returns null on failure.
     */
    private suspend fun recoverSessionBridge(): SessionPort? {
        connectionManager.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            return existing
        }

        publishCapabilities()
        val capabilities = currentAcpCapabilities ?: connectionManager.agentCapabilities.value
        if (capabilities != null && !capabilities.loadSession) {
            val created =
                runCatching { connectionManager.createSession(cwd) }.getOrNull() ?: return null
            attachSessionBridge(created)
            return created
        }

        return try {
            withTimeout(sessionLoadTimeoutMs) {
                connectionManager.loadSession(activeSessionId, cwd)
            }
        } catch (_: AcpAuthenticationRequiredException) {
            _loadFailed.emit(
                "ACP authentication is required for this server. " +
                    "Return to the session list and authenticate first.",
            )
            null
        } catch (_: TimeoutCancellationException) {
            null
        } catch (error: Exception) {
            _loadFailed.emit(formatAcpErrorMessage(error, "Failed to load session"))
            null
        }?.also { attachSessionBridge(it) }
    }

    /** Maps a send error to a user-facing message. */
    private fun userFacingSendError(error: Throwable): String {
        val detailedMessage = formatAcpErrorMessage(error, "Send failed")
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Request timeout", true) -> "Request timed out. Please try again."
            raw.contains("Invalid params", true) -> "Send failed due to an invalid request format."
            detailedMessage != "Send failed" -> detailedMessage
            else -> "Send failed due to an unknown error."
        }
    }

    /** Returns true when the error indicates the server does not support session/cancel. */
    private fun isSessionCancelUnsupported(error: Throwable): Boolean {
        val raw = error.message.orEmpty()
        return raw.contains("Method not found", true) &&
            raw.contains("session/cancel", true)
    }

    /** Returns true when the error indicates the stream was destroyed (stale bridge). */
    private fun isDestroyedBridgeStreamError(error: Throwable): Boolean {
        val message = formatAcpErrorMessage(error, "").lowercase()
        return message.contains("write after a stream was destroyed") ||
            message.contains("stream was destroyed")
    }

    /** Publishes the current agent capabilities from the connection manager. */
    private fun publishCapabilities() {
        connectionManager.agentCapabilities.value?.let { caps ->
            currentAcpCapabilities = caps
            _agentCapabilities.value = mapCapabilities(caps)
        }
    }

    // ---- Mapping helpers ----

    private fun mapConnectionState(acp: AcpConnectionState): ChatConnectionState =
        when (acp) {
            AcpConnectionState.Disconnected -> ChatConnectionState.Disconnected
            AcpConnectionState.Connecting -> ChatConnectionState.Connecting
            AcpConnectionState.Connected -> ChatConnectionState.Connected
            is AcpConnectionState.Failed ->
                ChatConnectionState.Failed(acp.error.message)
        }

    private fun mapDiagnostics(
        diag: com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics,
    ): ChatConnectionDiagnostics =
        ChatConnectionDiagnostics(
            serverUrl = diag.serverUrl,
            pendingRequestCount = diag.pendingRequestCount,
            recentErrors = diag.recentErrors.map { it.message },
            lastUpdatedAtMs = diag.lastUpdatedAtMs,
        )

    private fun mapCapabilities(caps: AgentCapabilities): ChatAgentCapabilities =
        ChatAgentCapabilities(
            canSendImages = caps.promptCapabilities.image,
            supportsEmbeddedContext = caps.promptCapabilities.embeddedContext,
        )

    private fun mapSnapshot(snapshot: SessionSnapshot): ChatSessionSnapshot =
        ChatSessionSnapshot(
            loadState = mapLoadState(snapshot.loadState),
            messages = snapshot.messages,
            isStreaming = snapshot.isStreaming,
            configOptions = snapshot.configOptions.map { mapConfigOption(it) },
            availableCommands = snapshot.availableCommands.map { ChatCommand(it.name, it.description) },
            commandsAdvertised = snapshot.commandsAdvertised,
            error = snapshot.error,
            usage = snapshot.usage?.let {
                UsageState(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                    cachedReadTokens = it.cachedReadTokens,
                    contextWindowTokens = it.contextWindowTokens,
                    costAmount = it.costAmount,
                    costCurrency = it.costCurrency,
                )
            },
        )

    private fun mapLoadState(
        acp: SessionLoadState,
    ): ChatLoadState =
        when (acp) {
            SessionLoadState.IDLE ->
                // Treat IDLE as hydrating so the UI shows a loading state until a snapshot arrives.
                ChatLoadState.HYDRATING
            SessionLoadState.HYDRATING ->
                ChatLoadState.HYDRATING
            SessionLoadState.READY ->
                ChatLoadState.READY
            SessionLoadState.FAILED ->
                ChatLoadState.FAILED
        }

    private fun mapConfigOption(option: com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption): ChatConfigOption {
        val category = option.category?.let { mapConfigCategory(it) }
        return when (option) {
            is SessionConfigOption.Select ->
                ChatConfigOption.Select(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    currentValue = option.currentValue,
                    choices = option.choices.map { mapChoice(it) },
                    groups = option.groups.map { group ->
                        ChatConfigChoiceGroup(
                            id = group.id,
                            label = group.label,
                            choices = group.choices.map { mapChoice(it) },
                        )
                    },
                )
            is SessionConfigOption.BooleanOption ->
                ChatConfigOption.BooleanOption(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    currentValue = option.currentValue,
                )
            is SessionConfigOption.Unknown ->
                ChatConfigOption.Unknown(
                    id = option.id,
                    name = option.name,
                    description = option.description,
                    category = category,
                    kind = option.kind,
                    currentValue = option.currentValue?.let { mapConfigValue(it) },
                )
        }
    }

    private fun mapConfigCategory(
        acp: SessionConfigCategory,
    ): ChatConfigCategory =
        when (acp) {
            SessionConfigCategory.Mode -> ChatConfigCategory.Mode
            SessionConfigCategory.Model -> ChatConfigCategory.Model
            is SessionConfigCategory.Custom -> ChatConfigCategory.Custom(acp.rawValue)
        }

    private fun mapChoice(
        acp: SessionConfigChoice,
    ): ChatConfigChoice =
        ChatConfigChoice(
            id = acp.id,
            label = acp.label,
            value = acp.value,
            description = acp.description,
        )

    private fun mapConfigValue(
        acp: SessionConfigValue,
    ): ChatConfigValue =
        when (acp) {
            is SessionConfigValue.StringValue -> ChatConfigValue.StringValue(acp.value)
            is SessionConfigValue.BoolValue -> ChatConfigValue.BoolValue(acp.value)
            is SessionConfigValue.UnknownValue -> ChatConfigValue.UnknownValue(acp.debugValue)
        }

    private fun toAcpConfigValue(chat: ChatConfigValue): SessionConfigValue =
        when (chat) {
            is ChatConfigValue.StringValue -> SessionConfigValue.StringValue(chat.value)
            is ChatConfigValue.BoolValue -> SessionConfigValue.BoolValue(chat.value)
            is ChatConfigValue.UnknownValue -> SessionConfigValue.UnknownValue(chat.debugValue)
        }
}
