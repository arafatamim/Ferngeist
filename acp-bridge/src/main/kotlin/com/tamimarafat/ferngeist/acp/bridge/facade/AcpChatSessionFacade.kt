package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionSurface
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import com.tamimarafat.ferngeist.gateway.launchGatewayRuntime
import com.tamimarafat.ferngeist.gateway.refreshGatewaySourceIfNeeded
import com.tamimarafat.ferngeist.gateway.resolveGatewayWebSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ACP implementation of [ChatSessionFacade].
 *
 * Owns the full session lifecycle: connection, initialization, bridge
 * attachment, recovery, and user action dispatch. Maps all ACP types
 * to chat-domain equivalents before exposing them to the feature layer.
 *
 * ponytail: hub integration helpers live here until the facade splits;
 * splitting now would scatter five one-call methods.
 */
@Suppress("TooManyFunctions")
class AcpChatSessionFacade(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnectionManager,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewaySourceRepository: GatewaySourceRepository,
    private val gatewayRepository: GatewayRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
    private val hub: ChatConnectionSurface,
    private val sessionLoadTimeoutMs: Long = 180_000L,
    private val bridgeRecoveryRetryDelayMs: Long = 3_000L,
    initialCachedSnapshot: ChatSessionSnapshot? = null,
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

    // Paint cache is seeded from the hub's snapshot cache at creation (the durable
    // store: observeSessionBridge writes hub.storeSnapshot per emission). This flow
    // never mutates after construction — reopening builds a fresh facade seeded from
    // the hub, so a live mirror here would duplicate the hub write per chunk.
    override val cachedSnapshot: StateFlow<ChatSessionSnapshot?> =
        MutableStateFlow(initialCachedSnapshot)

    private val _agentCapabilities =
        MutableStateFlow(ChatAgentCapabilities())
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = _agentCapabilities.asStateFlow()

    // ---- Gateway workspace ----
    private val _gatewayWorkspaceConnection = MutableStateFlow<GatewayWorkspaceConnection?>(null)
    override val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?> =
        _gatewayWorkspaceConnection.asStateFlow()

    // ---- Hub tracking ----
    private val _liveChatId = MutableStateFlow<String?>(null)
    override val liveChatId: StateFlow<String?> = _liveChatId.asStateFlow()
    private var resolvedAgentId: String? = null
    private var snapshotChatId: String = "$serverId/$initialSessionId"

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

    // Serializes gateway session lookups. Each spawn/attach path may refresh the
    // shared gateway credential first, and concurrent refreshes can invalidate
    // each other's proof (a replayed-proof rejection) on a cold multi-chat start.
    private val gatewayLookupMutex = Mutex()

    private val sessionLoadCoordinator by lazy {
        SessionLoadCoordinator(
            connectionManager = connectionManager,
            initialSessionId = initialSessionId,
            cwd = cwd,
            sessionLoadTimeoutMs = sessionLoadTimeoutMs,
            currentCapabilitiesProvider = { currentAcpCapabilities ?: connectionManager.agentCapabilities.value },
            onAttachBridge = ::attachSessionBridge,
            onLoadFailed = { _loadFailed.emit(it) },
            onSessionReady = { _sessionReady.emit(Unit) },
            onOperationError = { _operationError.emit(it) },
            onRecoveryDisabled = { shouldRecoverBridge = false },
        )
    }

    private val sessionModelUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val modelUpdated: SharedFlow<Unit> = sessionModelUpdated

    init {
        scope.launch {
            connectionManager.connectionState.collect { state ->
                _connectionState.value = mapConnectionState(state)
                // The hub publishes `connected` snapshots taken at register/
                // focus time; re-read the live lambdas so dots tracking
                // liveChats stay truthful across drops and reconnects.
                hub.refresh()
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
    override suspend fun loadSession() = loadSessionInternal(quiet = false)

    override suspend fun loadSessionQuietly() = loadSessionInternal(quiet = true)

    /**
     * Shared load implementation. [quiet] suppresses the ready announcement so a
     * screen that already painted its cached snapshot never flashes loading UI.
     */
    private suspend fun loadSessionInternal(quiet: Boolean) {
        bridgeOperationMutex.withLock {
            shouldRecoverBridge = true
            cancelBridgeRecovery()

            if (!ensureConnectedAndInitialized()) {
                _loadFailed.emit("Disconnected. Reconnect to refresh this session.")
                return
            }

            publishCapabilities()

            if (initialSessionId == NEW_SESSION_ARG) {
                // Create-on-arrival: the session list no longer pre-creates on its
                // browser transport; this chat's own connection mints the session.
                val bridge = connectionManager.createSession(cwd)
                if (bridge == null) {
                    _loadFailed.emit("Failed to create a new session.")
                    return
                }
                attachSessionBridge(bridge)
                _sessionReady.emit(Unit)
                return
            }

            when (val outcome = sessionLoadCoordinator.run(announceReady = !quiet)) {
                is SessionLoadOutcome.Attached -> return
                is SessionLoadOutcome.Failed -> _loadFailed.emit(outcome.message)
            }
        }
    }

    override suspend fun tryRestoreWarmSession(): Boolean =
        bridgeOperationMutex.withLock {
            if (!connectionManager.isConnected) return@withLock false
            val bridge =
                connectionManager.getSession(activeSessionId)
                    ?: connectionManager.getSession(initialSessionId)
                    ?: return@withLock false
            // Hot cache hit: reuse hydrated bridge without reconnect or transcript wipe.
            attachSessionBridge(bridge)
            // Restore workspace link so diff/status works after reattach.
            connectionManager
                .currentConnectionConfig()
                ?.takeIf { it.gatewayCredential != null && it.gatewayRuntimeId != null }
                ?.let { config ->
                    _gatewayWorkspaceConnection.value =
                        GatewayWorkspaceConnection(
                            runtimeId = config.gatewayRuntimeId!!,
                            scheme = config.gatewayScheme ?: "http",
                            host = config.gatewayHost ?: config.host,
                            gatewayCredential = config.gatewayCredential!!,
                        )
                }
            publishCapabilities()
            true
        }

    /**
     * Reacts to connection-state changes from the transport.
     * On reconnect the facade tries to reattach the session bridge automatically;
     * on disconnect/error the bridge is invalidated so state is not stale.
     */
    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        when (connectionState) {
            ChatConnectionState.Connected -> scheduleBridgeRecovery()
            else -> invalidateActiveBridge()
        }
    }

    override suspend fun reconnect() {
        bridgeOperationMutex.withLock {
            shouldRecoverBridge = true
            // Kick a connection attempt. On success the Connected state drives
            // scheduleBridgeRecovery -> sessionReady -> offline-queue flush; on
            // failure connect() has already armed the transport reconnect loop.
            ensureConnectedAndInitialized()
        }
    }

    /**
     * Sends a user message through the active bridge.
     *
     * If no bridge is attached yet, attempts a late-bound session load/create.
     * Image support is gated on the agent's capability advertisement.
     *
     * @return true when the payload was dispatched to a live session;
     *         false when no bridge is available or the payload is unsupported.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        val bridge =
            if (text.isBlank() && images.isEmpty() && files.isEmpty()) {
                null
            } else {
                ensureSessionReadyForSend()
            }
        if (bridge == null) {
            _operationError.emit(
                ChatOperationError("Session is not ready. Please retry in a moment.", false),
            )
            return false
        }
        if (isSendCapabilityRejected(images, files)) return false

        try {
            bridge.sendPrompt(text, images, files)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Broad catch: a send can fail on any transport/protocol error and must
            // surface as an operation error rather than crash the sending coroutine.
            _operationError.emit(ChatOperationError(userFacingSendError(error), true))
            return false
        }
        return true
    }

    /** Emits an operation error and returns true when the agent lacks image/file support. */
    private suspend fun isSendCapabilityRejected(
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        val capabilities = connectionManager.agentCapabilities.value ?: return false
        if (images.isNotEmpty() && !capabilities.promptCapabilities.image) {
            _operationError.emit(
                ChatOperationError("This agent does not advertise image prompt support.", false),
            )
            return true
        }
        if (files.isNotEmpty() && !capabilities.promptCapabilities.embeddedContext) {
            _operationError.emit(
                ChatOperationError("This agent does not advertise file attachment support.", false),
            )
            return true
        }
        return false
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
    override suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) {
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
        runCatching { bridge.setConfigOption(optionId, toAcpConfigValue(value)) }
            .onFailure { error ->
                _operationError.emit(ChatOperationError("Failed to update configuration", false))
            }
    }

    /** Forwards a permission grant to the active bridge. */
    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        runCatching { sessionBridge?.grantPermission(toolCallId, optionId) }
            .onFailure { error ->
                _operationError.emit(ChatOperationError("Failed to grant permission", false))
            }
    }

    /** Forwards a permission denial to the active bridge. */
    override suspend fun denyPermission(toolCallId: String) {
        runCatching { sessionBridge?.denyPermission(toolCallId) }
            .onFailure { error ->
                _operationError.emit(ChatOperationError("Failed to deny permission", false))
            }
    }

    /** Tears down bridge observers, cancels recovery, and invalidates the active bridge. */
    override fun clear() {
        cancelBridgeRecovery()
        invalidateActiveBridge()
        clearBridgeObservers()
        _sessionSnapshot.value = null
        _gatewayWorkspaceConnection.value = null
        if (_liveChatId.value == null) {
            hub.abandon(connectionManager)
        }
    }

    // ---- Internal ----

    /**
     * Ensures the transport is connected and initialised.
     * Returns false and emits [loadFailed] if connection or auth fails.
     */
    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            // Already connected — restore the gateway workspace connection from the live
            // config so the diff indicator works when resuming into an open session.
            connectionManager
                .currentConnectionConfig()
                ?.takeIf { it.gatewayCredential != null && it.gatewayRuntimeId != null }
                ?.let { config ->
                    _gatewayWorkspaceConnection.value =
                        GatewayWorkspaceConnection(
                            runtimeId = config.gatewayRuntimeId!!,
                            scheme = config.gatewayScheme ?: "http",
                            host = config.gatewayHost ?: config.host,
                            gatewayCredential = config.gatewayCredential!!,
                        )
                }
            publishCapabilities()
            registerConnectingWithHub()
            return true
        }
        val server = launchableTargetRepository.getTarget(serverId) ?: return false
        val connected = connectForTarget(server) ?: return false
        val initialized = connected && initializeSession() != null
        if (initialized) registerConnectingWithHub()
        return initialized
    }

    /**
     * Tracks this chat's connection in the hub under [sessionId] with [isStreaming]
     * read live; assigns the returned chat id to [_liveChatId].
     */
    private suspend fun registerHubEntry(
        sessionId: String,
        isStreaming: () -> Boolean,
    ) {
        _liveChatId.value =
            hub.register(
                serverId = serverId,
                sessionId = sessionId,
                gatewaySessionId = connectionManager.currentConnectionConfig()?.sessionId,
                gatewaySourceId = connectionManager.currentConnectionConfig()?.gatewaySourceId.orEmpty(),
                agentId = resolvedAgentId.orEmpty(),
                isConnected = { connectionManager.isConnected },
                isStreaming = isStreaming,
                manager = connectionManager,
            )
    }

    /**
     * Makes this chat warm-visible in the hub as soon as its transport is up,
     * before any bridge attaches. Register is idempotent per chatId, so the
     * later [registerHubEntry] attach call only refreshes focus.
     *
     * Create-on-arrival chats are skipped: the `__new__` sentinel is not a real
     * session id, so registering it would count a transient ghost toward the hot
     * cap. Their only counting registration is the real-id entry once a bridge
     * attaches.
     */
    private suspend fun registerConnectingWithHub() {
        if (initialSessionId == NEW_SESSION_ARG) return
        registerHubEntry(sessionId = activeSessionId, isStreaming = { false })
    }

    /**
     * Runs the transport initialisation handshake. Returns the agent capabilities
     * on success, or null after emitting [loadFailed] on auth-required failures.
     */
    private suspend fun initializeSession(): AgentCapabilities? =
        when (val result = connectionManager.initialize()) {
            is AcpInitializeResult.Ready -> {
                currentAcpCapabilities = result.agentCapabilities
                _agentCapabilities.value = mapCapabilities(result.agentCapabilities)
                result.agentCapabilities
            }
            is AcpInitializeResult.AuthenticationRequired -> {
                shouldRecoverBridge = false
                _loadFailed.emit(
                    "ACP authentication is required for this server. " +
                        "Reconnect from the server list and choose an auth method.",
                )
                null
            }
            null -> null
        }

    /** Connects to the target's transport. Returns null when no config could be built. */
    private suspend fun connectForTarget(server: LaunchableTarget): Boolean? =
        when (server) {
            is LaunchableTarget.GatewayAgent -> {
                val config = buildGatewayConnectionConfig(server) ?: return null
                connectionManager.connect(config)
            }
            is LaunchableTarget.Manual ->
                connectionManager.connect(
                    AcpConnectionConfig(
                        scheme = server.server.scheme,
                        host = server.server.host,
                        preferredAuthMethodId = server.server.preferredAuthMethodId,
                        serverDisplayName = server.name,
                    ),
                )
        }

    /**
     * Builds a connection config for a gateway-backed agent by starting a fresh
     * runtime on the gateway and obtaining the WebSocket handoff.
     */
    private suspend fun buildGatewayConnectionConfig(target: LaunchableTarget.GatewayAgent): AcpConnectionConfig? {
        val gatewaySource = target.gatewaySource
        if (gatewaySource.gatewayCredential.isBlank()) {
            _loadFailed.emit("Gateway is not paired for ${target.name}.")
            return null
        }
        // A second concurrent chat on the same agent needs its own runtime
        // process: the gateway leases one runtime per gateway session, so a
        // plain connect would resume the other chat's session. Reattaching to
        // this chat's own recorded runtime is the exception — that is the same
        // session, so it must be resumed rather than duplicated.
        val plan = resolveLaunchPlan(target) ?: return null
        return launchGatewayRuntime(
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            gatewaySource = gatewaySource,
            agentId = target.binding.agentId,
            requireSupportedProtocol = true,
            fresh = plan.fresh,
            reuseRuntimeId = plan.reuseRuntimeId,
        ).fold(
            onSuccess = { result ->
                resolvedAgentId = target.binding.agentId
                val source = result.gatewaySource
                val handoff = result.handoff
                _gatewayWorkspaceConnection.value =
                    GatewayWorkspaceConnection(
                        runtimeId = result.runtime.id,
                        scheme = source.scheme,
                        host = source.host,
                        gatewayCredential = source.gatewayCredential,
                    )
                AcpConnectionConfig(
                    scheme = source.scheme,
                    host = source.host,
                    webSocketUrl = resolveGatewayWebSocketUrl(source, handoff),
                    webSocketBearerToken = handoff.bearerToken,
                    preferredAuthMethodId = target.binding.preferredAuthMethodId,
                    gatewayRuntimeId = result.runtime.id,
                    gatewaySourceId = source.id,
                    serverDisplayName = target.name,
                    sessionId = handoff.sessionId,
                    attachToken = handoff.attachToken,
                    gatewayScheme = source.scheme,
                    gatewayHost = source.host,
                    gatewayCredential = source.gatewayCredential,
                )
            },
            onFailure = { error ->
                when (error) {
                    is GatewayCredentialExpiredException -> {
                        // The stored credential is dead (expired past the gateway's grace
                        // window). Clear it and surface the pairing flow instead of failing
                        // opaquely on every reconnect.
                        gatewaySourceRepository.deleteGateway(gatewaySource.id)
                        _loadFailed.emit(
                            "Gateway credential expired for ${target.name}. Please pair this gateway again.",
                        )
                    }

                    else ->
                        _loadFailed.emit(
                            "Failed to reconnect to ${target.name}: ${error.message ?: "unknown error"}",
                        )
                }
                null
            },
        )
    }

    /**
     * Frees a device gateway-session slot, so the following start mints a new
     * isolated runtime. Returns false when capacity could not be secured (error
     * already emitted).
     */
    private suspend fun reserveIsolatedSlot(gatewaySource: GatewaySource): Boolean =
        runCatching {
            hub.ensureGatewayCapacity(
                GatewayEndpoint(gatewaySource.scheme, gatewaySource.host, gatewaySource.gatewayCredential),
            )
            true
        }.getOrElse { error ->
            _loadFailed.emit(error.message ?: "All gateway sessions are in use.")
            false
        }

    /**
     * Lists how the gateway currently sees this agent's sessions, or null when
     * the lookup failed (callers then fall back to in-memory state).
     */
    private suspend fun gatewaySessions(
        gatewaySource: GatewaySource,
        agentId: String,
    ): List<GatewaySessionSummary>? =
        runCatching {
            gatedGatewayLookup(gatewaySource) { credential ->
                gatewayRepository.listGatewaySessions(
                    scheme = gatewaySource.scheme,
                    host = gatewaySource.host,
                    gatewayCredential = credential,
                )
            }
        }.getOrNull()
            ?.filter { it.agentId == agentId }

    /** The runtime plan for a gateway attach: an explicit reuse target, or a fresh spawn. */
    private data class GatewayLaunchPlan(
        val fresh: Boolean,
        val reuseRuntimeId: String?,
    )

    /**
     * Picks the runtime this chat attaches to, or null when an isolated runtime
     * was needed but its slot could not be secured (error already emitted).
     *
     * A gateway runtime leases exactly one session, so two chats that share a
     * runtime both end up resuming one session and fight over it — the chat
     * screen never populates. In-memory hub state cannot settle this (it is
     * empty after process death), so the gateway's own session list is the
     * authority: one call answers both halves of the decision.
     */
    private suspend fun resolveLaunchPlan(target: LaunchableTarget.GatewayAgent): GatewayLaunchPlan? {
        val gatewaySource = target.gatewaySource
        val ownSessionId =
            if (initialSessionId == NEW_SESSION_ARG) {
                null
            } else {
                hub.persistedGatewaySessionId(serverId, initialSessionId)
            }
        val sessions = gatewaySessions(gatewaySource, target.binding.agentId)

        // This chat's own session is still live: its runtime is the one to
        // attach to, rather than minting a second process for the same
        // conversation (the gateway's own reconnect-after-app-kill path).
        sessions
            ?.firstOrNull { it.sessionId == ownSessionId && it.isResumable }
            ?.let { return GatewayLaunchPlan(fresh = false, reuseRuntimeId = it.runtimeId) }

        // Someone else on this agent holds a live session. A plain start hands
        // back that runtime, so this chat needs its own process. The in-memory
        // check also covers a session the listing has not caught up with yet.
        val heldByAnother =
            sessions?.any { it.isResumable && it.sessionId != ownSessionId } == true ||
                hub.hasLiveGatewaySession(gatewaySource.id, target.binding.agentId)
        if (!heldByAnother) return GatewayLaunchPlan(fresh = false, reuseRuntimeId = null)
        if (!reserveIsolatedSlot(gatewaySource)) return null
        return GatewayLaunchPlan(fresh = true, reuseRuntimeId = null)
    }

    /**
     * Runs a gateway read under the shared lookup gate, folding an expired
     * credential into a null result so the caller falls back to a fresh spawn
     * instead of failing the whole connect.
     */
    private suspend fun <T> gatedGatewayLookup(
        gatewaySource: GatewaySource,
        block: suspend (String) -> T,
    ): T? =
        gatewayLookupMutex.withLock {
            val credential =
                withContext(Dispatchers.IO) {
                    refreshGatewaySourceIfNeeded(gatewaySource, gatewayRepository, gatewaySourceRepository)
                }.gatewayCredential
            if (credential.isBlank()) null else block(credential)
        }

    private suspend fun attachSessionBridge(bridge: SessionPort) {
        activeSessionId = bridge.sessionId
        // The create-on-arrival sentinel never registers with the hub (see
        // registerConnectingWithHub); switch to the real id so snapshots store
        // under the same key the factory seeds from.
        snapshotChatId = "$serverId/${bridge.sessionId}"
        sessionBridge = bridge
        pendingModelSelectionId = null
        shouldRecoverBridge = true
        cancelBridgeRecovery()
        observeSessionBridge(bridge)
        registerWithHub(bridge)
    }

    /** Tracks this live session in the hub; synchronous so snapshots store under the entry immediately. */
    private suspend fun registerWithHub(bridge: SessionPort) {
        registerHubEntry(sessionId = bridge.sessionId, isStreaming = { bridge.snapshot.value.isStreaming })
    }

    /** Launches snapshot and model-selection collection coroutines. */
    private fun observeSessionBridge(bridge: SessionPort) {
        clearBridgeObservers()
        bridgeObserverJobs =
            listOf(
                scope.launch {
                    bridge.snapshot.collect { snapshot ->
                        val mapped = mapSnapshot(snapshot)
                        _sessionSnapshot.value = mapped
                        hub.storeSnapshot(snapshotChatId, mapped)
                    }
                },
                scope.launch {
                    bridge.modelSelectionEvents?.collect { event ->
                        // Fires [sessionModelUpdated] when the confirmed model matches the
                        // user's pending selection (or the selection is blank).
                        val pendingModel = pendingModelSelectionId
                        if (pendingModel != null &&
                            (event.modelId.isNullOrBlank() || event.modelId == pendingModel)
                        ) {
                            pendingModelSelectionId = null
                            sessionModelUpdated.emit(Unit)
                        }
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
            }
        return created?.also {
            attachSessionBridge(it)
            _sessionReady.emit(Unit)
        }
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
                        if (attemptBridgeRecovery()) break
                    }
                } finally {
                    bridgeRecoveryJob = null
                }
            }
    }

    /**
     * Attempts one bridge-recovery cycle.
     * @return true if recovery succeeded (or should stop), false to retry.
     */
    private suspend fun attemptBridgeRecovery(): Boolean {
        if (!connectionManager.isConnected) return true

        val recovered =
            bridgeOperationMutex.withLock {
                sessionBridge
                    ?: recoverSessionBridge()
            }
        if (recovered != null) {
            _sessionReady.emit(Unit)
            return true
        }

        delay(bridgeRecoveryRetryDelayMs)
        return false
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
        _sessionSnapshot.value = null
        clearBridgeObservers()
    }

    /**
     * Tries to re-attach an existing session or (if the agent does not
     * support [loadSession]) create a new one. Returns null on failure.
     */
    @Suppress("TooGenericExceptionCaught")
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

    /** Publishes the current agent capabilities from the connection manager. */
    private fun publishCapabilities() {
        connectionManager.agentCapabilities.value?.let { caps ->
            currentAcpCapabilities = caps
            _agentCapabilities.value = mapCapabilities(caps)
        }
    }
}

/**
 * Coordinates the session-load lifecycle: reattaches an existing session or
 * loads/creates one within a timeout, mapping auth/timeout/bridge failures to
 * [SessionLoadOutcome] results the facade can surface.
 */
internal sealed interface SessionLoadOutcome {
    data object Attached : SessionLoadOutcome

    data class Failed(
        val message: String,
    ) : SessionLoadOutcome
}

internal class SessionLoadCoordinator(
    private val connectionManager: AcpConnectionManager,
    private val initialSessionId: String,
    private val cwd: String,
    private val sessionLoadTimeoutMs: Long,
    private val currentCapabilitiesProvider: () -> AgentCapabilities?,
    private val onAttachBridge: suspend (SessionPort) -> Unit,
    private val onLoadFailed: suspend (String) -> Unit,
    private val onSessionReady: suspend () -> Unit,
    private val onOperationError: suspend (ChatOperationError) -> Unit,
    private val onRecoveryDisabled: () -> Unit,
) {
    /**
     * Quiet revalidation: same load, but [announceReady] controls whether
     * [onSessionReady] fires on the fast-path so the UI keeps the already-painted
     * cached snapshot instead of flashing loading states. Live bridge snapshots
     * still flow through observers regardless.
     */
    suspend fun run(announceReady: Boolean = true): SessionLoadOutcome {
        connectionManager.getSession(initialSessionId)?.let { existing ->
            onAttachBridge(existing)
            if (announceReady) onSessionReady()
            return SessionLoadOutcome.Attached
        }

        val capabilities = currentCapabilitiesProvider()
        if (capabilities != null && !capabilities.loadSession) {
            onRecoveryDisabled()
            return SessionLoadOutcome.Failed("This agent does not advertise session/load support.")
        }

        return loadWithTimeout(announceReady)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadWithTimeout(announceReady: Boolean): SessionLoadOutcome {
        val bridge =
            try {
                withTimeout(sessionLoadTimeoutMs) {
                    connectionManager.loadSession(initialSessionId, cwd)
                }
            } catch (_: AcpAuthenticationRequiredException) {
                return SessionLoadOutcome.Failed(
                    "ACP authentication is required for this server. " +
                        "Return to the session list and authenticate first.",
                )
            } catch (_: TimeoutCancellationException) {
                return SessionLoadOutcome.Failed("Session load timed out. Check server connection and retry.")
            } catch (error: Exception) {
                // Broad catch: session/load can fail on transport, auth, or SDK
                // protocol errors; each is surfaced as a load failure instead of
                // crashing the loading coroutine. Destroyed-bridge errors fall
                // back to a fresh session so the user can keep chatting.
                return handleLoadFailure(error, announceReady)
            }
        return if (bridge != null) {
            onAttachBridge(bridge)
            SessionLoadOutcome.Attached
        } else {
            SessionLoadOutcome.Failed("Could not load this session. Check connection and retry.")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleLoadFailure(
        error: Exception,
        announceReady: Boolean = true,
    ): SessionLoadOutcome {
        if (isDestroyedBridgeStreamError(error)) {
            // If the bridge process restarted mid-load, create a new session
            // so the user can keep chatting without reopening the screen.
            val fallbackBridge =
                runCatching { connectionManager.createSession(cwd) }.getOrNull()
            if (fallbackBridge != null) {
                onAttachBridge(fallbackBridge)
                if (announceReady) onSessionReady()
                onOperationError(
                    ChatOperationError(
                        message =
                            "The ACP bridge process restarted while loading this session. " +
                                "Opened a new live session.",
                        stopStreaming = false,
                    ),
                )
                return SessionLoadOutcome.Attached
            }
        }
        return SessionLoadOutcome.Failed(formatAcpErrorMessage(error, "Failed to load session"))
    }
}
