package com.tamimarafat.ferngeist.acp.bridge.facade

import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.connection.buildGatewayLaunchContext
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.toConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionSurface
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatRegistration
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session a chat opens with: which server and session, where it runs, and the snapshot it
 * paints before the live bridge answers.
 */
data class ChatOpening(
    val serverId: String,
    val sessionId: String,
    val cwd: String,
    val cachedSnapshot: ChatSessionSnapshot?,
)

/**
 * ACP implementation of [ChatSessionFacade].
 *
 * Owns the session lifecycle: connection, initialization, bridge attachment,
 * and user action dispatch. Session recovery belongs to the gateway and the
 * transport: the connection layer reconnects (`resume` -> attach ->
 * `session/load`) and the gateway restores the agent; this facade only starts a
 * fresh connect when the gateway says the session is gone.
 *
 * ponytail: hub integration helpers live here until the facade splits;
 * splitting now would scatter five one-call methods.
 */
@Suppress("TooManyFunctions")
class AcpChatSessionFacade(
    private val scope: CoroutineScope,
    private val connectionManager: AcpConnection,
    private val repositories: LaunchRepositories,
    private val opening: ChatOpening,
    private val hub: ChatConnectionSurface,
    private val sessionLoadTimeoutMs: Long = 60_000L,
) : ChatSessionFacade {
    private val serverId: String = opening.serverId
    private val initialSessionId: String = opening.sessionId
    private val cwd: String = opening.cwd

    private val workspace =
        GatewayWorkspaceReader(
            gatewayRepository = repositories.gatewayRepository,
            workspaceConnection = { _gatewayWorkspaceConnection.value },
            liveSessionId = ::liveSessionIdOrNull,
            cwd = cwd,
        )

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
        MutableStateFlow(opening.cachedSnapshot)

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
    private var pendingModelSelectionId: String? = null
    private var currentAcpCapabilities: AgentCapabilities? = null
    private val bridgeOperationMutex = Mutex()

    // Times the steps of the open in flight; null outside one. Only touched under
    // bridgeOperationMutex, so it needs no further synchronization.
    private var openTimer: OpenStepTimer? = null

    private val sessionLoadCoordinator by lazy {
        SessionLoadCoordinator(
            connectionManager = connectionManager,
            opening = opening,
            sessionLoadTimeoutMs = sessionLoadTimeoutMs,
            currentCapabilitiesProvider = { currentAcpCapabilities ?: connectionManager.agentCapabilities.value },
            onAttachBridge = ::attachSessionBridge,
            onSessionReady = { _sessionReady.emit(Unit) },
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
        scope.launch {
            connectionManager.events.collect { event ->
                if (event is AcpManagerEvent.SessionResumeRejected) reconnectAfterRejectedResume()
            }
        }
    }

    // ---- Public API ----

    /**
     * Loads an existing session, or mints a new one for the create-on-arrival route.
     *
     * The load flow:
     * 1. Ensures the transport is connected and initialised.
     * 2. Tries [AcpConnection.sessions] for an already-open bridge.
     * 3. If the agent advertises `session/load`, calls
     *    [com.tamimarafat.ferngeist.acp.bridge.connection.SessionGateway.attachSession]
     *    with a [sessionLoadTimeoutMs] deadline.
     *
     * A failure is reported as-is: the gateway owns session recovery, so there is
     * no fresh-session fallback here.
     */
    override suspend fun loadSession() = loadSessionInternal(quiet = false)

    override suspend fun loadSessionQuietly() = loadSessionInternal(quiet = true)

    /**
     * Shared load implementation. [quiet] suppresses the ready announcement so a
     * screen that already painted its cached snapshot never flashes loading UI.
     */
    private suspend fun loadSessionInternal(quiet: Boolean) {
        bridgeOperationMutex.withLock {
            val timer = OpenStepTimer().also { openTimer = it }
            try {
                loadSessionLocked(quiet)
            } finally {
                openTimer = null
                connectionManager.recordTiming("open", timer.report(sessionId = initialSessionId))
            }
        }
    }

    private suspend fun loadSessionLocked(quiet: Boolean) {
        if (!ensureConnectedAndInitialized()) {
            _loadFailed.emit(connectionFailure("Disconnected. Reconnect to refresh this session."))
            return
        }

        publishCapabilities()

        if (initialSessionId == NEW_SESSION_ARG) {
            // Create-on-arrival: the session list no longer pre-creates on its
            // browser transport; this chat's own connection mints the session.
            val bridge = connectionManager.sessions.createSession(cwd)
            if (bridge == null) {
                _loadFailed.emit(connectionFailure("Failed to create a new session."))
                return
            }
            attachSessionBridge(bridge)
            _sessionReady.emit(Unit)
            return
        }

        val outcome = sessionLoadCoordinator.run(announceReady = !quiet)
        openTimer?.mark(if (outcome is SessionLoadOutcome.Attached) "load" else "loadFailed")
        if (outcome is SessionLoadOutcome.Failed) _loadFailed.emit(outcome.message)
    }

    override suspend fun fetchSessionTitle(): String? =
        hub.resolveSessionTitle(
            serverId = serverId,
            sessionId = activeSessionId,
            cwd = cwd.takeIf { it.isNotBlank() },
        )

    override suspend fun tryRestoreWarmSession(): Boolean =
        bridgeOperationMutex.withLock {
            if (!connectionManager.isConnected) return@withLock false
            val bridge =
                connectionManager.sessions.getSession(activeSessionId)
                    ?: connectionManager.sessions.getSession(initialSessionId)
                    ?: return@withLock false
            // Hot cache hit: reuse hydrated bridge without reconnect or transcript wipe.
            attachSessionBridge(bridge)
            // Restore workspace link so diff/status works after reattach.
            restoreGatewayWorkspaceConnection()
            publishCapabilities()
            true
        }

    /**
     * No-op: the connection layer owns reconnect (`resume` -> attach ->
     * `session/load`) and the gateway owns session recovery, so a connection-state
     * change needs no bridge action here. The mapped [connectionState] flow already
     * drives the UI, and the bridge a chat holds stays valid across a transport
     * reset. Kept because [ChatSessionFacade] declares it.
     */
    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        // No-op: see the KDoc.
    }

    /**
     * Ensures the transport is connected so queued prompts can drain. A fresh
     * connect is the only path: the gateway launches a fresh runtime and
     * mints the session, and the session attach follows from [ensureSessionReadyForSend].
     */
    override suspend fun reconnect() {
        bridgeOperationMutex.withLock {
            ensureConnectedAndInitialized()
        }
    }

    override suspend fun fetchGitStatus(compare: String?): ChatGitStatus? = workspace.fetchGitStatus(compare)

    override suspend fun fetchWorktree(): ChatWorktree? = workspace.fetchWorktree()

    override suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? = workspace.fetchGitDiff(path, commit, oldPath, compare)

    override suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? = workspace.fetchGitLog(head, skip)

    override suspend fun fetchGitRefs(): ChatGitRefs? = workspace.fetchGitRefs()

    override suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? = workspace.fetchGitCommitFiles(sha)

    override suspend fun fetchFile(path: String): ChatFileContents? = workspace.fetchFile(path)

    override suspend fun fetchFileList(): List<String>? = workspace.fetchFileList()

    /**
     * The real ACP session this chat shows, or null while a create-on-arrival chat still
     * holds the `__new__` sentinel (see [NEW_SESSION_ARG]).
     */
    private fun liveSessionIdOrNull(): String? = activeSessionId.takeIf { it != NEW_SESSION_ARG }

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
                ChatOperationError(connectionFailure("Session is not ready. Please retry in a moment."), false),
            )
            return false
        }
        if (isSendCapabilityRejected(images, files)) return false

        try {
            bridge.sendPrompt(text, images, files)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Broad catch: a send can fail on any transport/protocol error and must
            // surface as an operation error rather than crash the sending coroutine.
            _operationError.emit(ChatOperationError(userFacingSendError(error), true))
            return false
        }
        return true
    }

    // Silent on failure by design: a prompt that is not taken goes out as a normal send,
    // which reports its own errors.
    override suspend fun steerMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): SteerOutcome = sessionBridge?.steer(text, images, files) ?: SteerOutcome.NOT_CONSUMED

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

    /** Forwards an elicitation submit to the active bridge. */
    override suspend fun submitElicitation(
        key: String,
        values: Map<String, com.tamimarafat.ferngeist.core.model.ChatElicitationValue>,
    ) {
        runCatching { sessionBridge?.submitElicitation(key, values) }
            .onFailure {
                _operationError.emit(ChatOperationError("Failed to submit elicitation", false))
            }
    }

    /** Forwards an elicitation decline to the active bridge. */
    override suspend fun declineElicitation(key: String) {
        runCatching { sessionBridge?.declineElicitation(key) }
            .onFailure {
                _operationError.emit(ChatOperationError("Failed to decline elicitation", false))
            }
    }

    /** Forwards an elicitation cancel to the active bridge. */
    override suspend fun cancelElicitation(key: String) {
        runCatching { sessionBridge?.cancelElicitation(key) }
            .onFailure {
                _operationError.emit(ChatOperationError("Failed to dismiss elicitation", false))
            }
    }

    /** Tears down bridge observers and invalidates the active bridge. */
    override fun clear() {
        invalidateActiveBridge()
        _gatewayWorkspaceConnection.value = null
        if (_liveChatId.value == null) {
            hub.abandon(connectionManager)
        }
    }

    // ---- Internal ----

    /**
     * The server's own reason a connect, initialize or session/new just failed, which those
     * calls report only as a boolean/null (the cause lands in the transport state or
     * diagnostics). [fallback] when none was recorded.
     */
    private fun connectionFailure(fallback: String): String {
        val state = connectionManager.connectionState.value
        if (state is AcpConnectionState.Failed) return formatAcpErrorMessage(state.error, fallback)
        return connectionManager.diagnostics.value.recentErrors
            .lastOrNull { it.source == "initialize" || it.source == "session/new" }
            ?.message ?: fallback
    }

    /**
     * Ensures the transport is connected and initialised.
     * Returns false and emits [loadFailed] if connection or auth fails.
     */
    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) {
            // Already connected — restore the gateway workspace connection from the live
            // config so the diff indicator works when resuming into an open session.
            restoreGatewayWorkspaceConnection()
            publishCapabilities()
            registerConnectingWithHub()
            openTimer?.mark("alreadyConnected")
            return true
        }
        val server = repositories.launchableTargetRepository.getTarget(serverId) ?: return false
        val connected = connectForTarget(server) ?: return false
        openTimer?.mark("websocket")
        val initialized = connected && initializeSession() != null
        openTimer?.mark("initialize")
        if (initialized) registerConnectingWithHub()
        return initialized
    }

    /**
     * Points the diff/status indicator at the live gateway runtime, when the transport
     * is on one. A manual connection has no workspace to read.
     */
    private fun restoreGatewayWorkspaceConnection() {
        val gateway = connectionManager.currentConnectionConfig() as? AcpConnectionConfig.Gateway ?: return
        _gatewayWorkspaceConnection.value =
            GatewayWorkspaceConnection(
                runtimeId = gateway.runtimeId,
                scheme = gateway.scheme,
                host = gateway.host,
                gatewayCredential = gateway.gatewayCredential,
            )
    }

    /**
     * Tracks this chat's connection in the hub under [sessionId]; assigns the returned
     * chat id to [_liveChatId]. Streaming is read through the manager, not a captured
     * bridge: it must stay true for a chat whose screen closed mid-turn, and it must
     * not be pinned to `false` by a re-registration while a turn runs.
     */
    private fun registerHubEntry(sessionId: String) {
        val gatewayConfig = connectionManager.currentConnectionConfig() as? AcpConnectionConfig.Gateway
        _liveChatId.value =
            hub.register(
                serverId = serverId,
                sessionId = sessionId,
                registration =
                    ChatRegistration(
                        gatewaySessionId = gatewayConfig?.resume?.sessionId,
                        gatewaySourceId = gatewayConfig?.sourceId.orEmpty(),
                        agentId = resolvedAgentId.orEmpty(),
                        isConnected = { connectionManager.isConnected },
                        isStreaming = {
                            connectionManager
                                .sessions
                                .getSession(activeSessionId)
                                ?.snapshot
                                ?.value
                                ?.isStreaming == true
                        },
                        manager = connectionManager,
                    ),
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
    private fun registerConnectingWithHub() {
        if (initialSessionId == NEW_SESSION_ARG) return
        registerHubEntry(sessionId = activeSessionId)
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
            is LaunchableTarget.Manual -> connectionManager.connect(server.toConnectionConfig())
        }

    /**
     * Builds a connection config for a gateway-backed agent by connecting it on
     * the gateway: one call launches a fresh runtime and mints the session,
     * so the app neither picks a runtime nor retries a launch.
     */
    private suspend fun buildGatewayConnectionConfig(target: LaunchableTarget.GatewayAgent): AcpConnectionConfig? {
        val gatewaySource = target.gatewaySource
        if (gatewaySource.gatewayCredential.isBlank()) {
            _loadFailed.emit("Gateway is not paired for ${target.name}.")
            return null
        }
        // A chat whose agent survived the app reattaches to the gateway session still running it.
        val context =
            buildGatewayLaunchContext(
                repositories.runtimeLauncher,
                target,
                acpSessionId = initialSessionId.takeUnless { it == NEW_SESSION_ARG },
            )
        openTimer?.mark("launch")
        return context.fold(
            onSuccess = { launch ->
                resolvedAgentId = target.binding.agentId
                val source = launch.gatewaySource
                _gatewayWorkspaceConnection.value =
                    GatewayWorkspaceConnection(
                        runtimeId = launch.runtimeId,
                        scheme = source.scheme,
                        host = source.host,
                        gatewayCredential = source.gatewayCredential,
                    )
                launch.config
            },
            onFailure = { error ->
                when (error) {
                    is GatewayCredentialExpiredException -> {
                        // The stored credential is dead (expired past the gateway's grace
                        // window). Clear it and surface the pairing flow instead of failing
                        // opaquely on every reconnect.
                        repositories.gatewaySourceRepository.deleteGateway(gatewaySource.id)
                        _loadFailed.emit(
                            "Gateway credential expired for ${target.name}. Please pair this gateway again.",
                        )
                    }

                    else ->
                        // The gateway's own words: a 502 carries the agent's exit error,
                        // which is the only thing that explains why it did not come up.
                        _loadFailed.emit(error.message ?: "Failed to connect to ${target.name}.")
                }
                null
            },
        )
    }

    /**
     * The connection layer's reconnect found the gateway session gone (the gateway
     * restarted, or the runtime failed): the gateway is the only recoverer, so the
     * app's only path is a fresh connect — the gateway spawns a runtime and mints a
     * new session. The dead session's transcript is not recoverable; the user is
     * told through the existing operation-error channel and the new session carries on.
     */
    private suspend fun reconnectAfterRejectedResume() {
        bridgeOperationMutex.withLock {
            val target =
                repositories.launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent ?: return
            val context =
                buildGatewayLaunchContext(repositories.runtimeLauncher, target).getOrElse { error ->
                    // The gateway's own words: a 502 carries the agent's exit error.
                    _loadFailed.emit(error.message ?: "Failed to connect to ${target.name}.")
                    return
                }
            val source = context.gatewaySource
            resolvedAgentId = target.binding.agentId
            _gatewayWorkspaceConnection.value =
                GatewayWorkspaceConnection(
                    runtimeId = context.runtimeId,
                    scheme = source.scheme,
                    host = source.host,
                    gatewayCredential = source.gatewayCredential,
                )
            if (!connectionManager.connect(context.config) || initializeSession() == null) {
                _loadFailed.emit("Failed to connect to ${target.name}.")
                return
            }
            publishCapabilities()
            _operationError.emit(
                ChatOperationError(
                    message = "The previous session could not be resumed. Opened a new session.",
                    stopStreaming = false,
                ),
            )
            // Attach to the session the gateway just minted, the same way a launch does.
            val outcome = sessionLoadCoordinator.run(sessionId = context.sessionId)
            if (outcome is SessionLoadOutcome.Failed) _loadFailed.emit(outcome.message)
        }
    }

    private fun attachSessionBridge(bridge: SessionPort) {
        activeSessionId = bridge.sessionId
        // The create-on-arrival sentinel never registers with the hub (see
        // registerConnectingWithHub); switch to the real id so snapshots store
        // under the same key the factory seeds from.
        snapshotChatId = "$serverId/${bridge.sessionId}"
        sessionBridge = bridge
        pendingModelSelectionId = null
        observeSessionBridge(bridge)
        registerWithHub(bridge)
    }

    /** Tracks this live session in the hub; synchronous so snapshots store under the entry immediately. */
    private fun registerWithHub(bridge: SessionPort) {
        registerHubEntry(sessionId = bridge.sessionId)
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
                    bridge.modelSelectionEvents.collect { event ->
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

        connectionManager.sessions.getSession(activeSessionId)?.let { existing ->
            attachSessionBridge(existing)
            _sessionReady.emit(Unit)
            return existing
        }

        val created =
            try {
                connectionManager.sessions.createSession(cwd)
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

    /** Clears the bridge reference, pending selection, and all observers. */
    private fun invalidateActiveBridge() {
        sessionBridge = null
        pendingModelSelectionId = null
        _sessionSnapshot.value = null
        clearBridgeObservers()
    }

    /** Publishes the current agent capabilities from the connection manager. */
    private fun publishCapabilities() {
        connectionManager.agentCapabilities.value?.let { caps ->
            currentAcpCapabilities = caps
            _agentCapabilities.value = mapCapabilities(caps)
        }
    }
}
