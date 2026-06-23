package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoConnectionState
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoProvider
import com.tamimarafat.ferngeist.acp.bridge.paseo.PaseoSessionPort
import com.tamimarafat.ferngeist.acp.bridge.paseo.toPaseoConnection
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigCategory
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOrigin
import com.tamimarafat.ferngeist.acp.bridge.session.SessionPort
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
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
 * Paseo-backed implementation of [ChatSessionFacade].
 *
 * Mirrors the role of `AcpChatSessionFacade` for the Paseo transport: resolves the
 * [LaunchableTarget.Paseo] target, connects the daemon, discovers the bound
 * provider's models/modes, and attaches a [SessionPort] (a `PaseoSessionPort`)
 * whose snapshot feeds the chat layer.
 */
internal class PaseoChatSessionFacade(
    private val scope: CoroutineScope,
    private val connectionManager: PaseoConnectionManager,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val serverId: String,
    private val initialSessionId: String,
    private val cwd: String,
) : ChatSessionFacade {
    private val _connectionState =
        MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    override val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    private val _diagnostics = MutableStateFlow(ChatConnectionDiagnostics())
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = _diagnostics.asStateFlow()

    private val _sessionSnapshot = MutableStateFlow<ChatSessionSnapshot?>(null)
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = _sessionSnapshot.asStateFlow()

    private val _agentCapabilities = MutableStateFlow(ChatAgentCapabilities(canSendImages = true))
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = _agentCapabilities.asStateFlow()

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

    private val _modelUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val modelUpdated: SharedFlow<Unit> = _modelUpdated

    private var activeSessionId: String = initialSessionId
    private var sessionPort: SessionPort? = null
    private var snapshotJob: Job? = null
    private var connectionStateJob: Job? = null
    private var provider: PaseoProvider? = null
    private val mutex = Mutex()

    override suspend fun loadSession() {
        mutex.withLock {
            val target = resolveTarget() ?: return
            if (!ensureConnected(target)) {
                _loadFailed.emit("Could not connect to Paseo daemon ${target.paseoSource.name}.")
                return
            }

            connectionManager.getSession(initialSessionId)?.let { existing ->
                attachPort(existing, target)
                _sessionReady.emit(Unit)
                return
            }

            val loaded = connectionManager.loadSession(initialSessionId)
            if (loaded != null) {
                attachPort(loaded, target)
                _sessionReady.emit(Unit)
                return
            }

            // Agent not found on the daemon — start a fresh one.
            val created =
                connectionManager.createSession(
                    provider = target.binding.provider,
                    cwd = cwd.ifBlank { target.binding.cwd },
                    model = target.binding.preferredModelId,
                )
            if (created == null) {
                _loadFailed.emit("Failed to start a ${target.binding.name} session on the daemon.")
                return
            }
            attachPort(created, target)
            _sessionReady.emit(Unit)
        }
    }

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
    ) {
        if (text.isBlank() && images.isEmpty()) return
        val port = ensureSessionForSend()
        if (port == null) {
            _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
            return
        }
        val imagePairs = images.map { Pair(it.base64, it.mimeType) }
        try {
            port.sendPrompt(text, imagePairs)
        } catch (error: Exception) {
            _operationError.emit(ChatOperationError(error.message ?: "Send failed.", true))
        }
    }

    override suspend fun cancelStreaming() {
        val port = sessionPort
        if (port == null) {
            _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
            return
        }
        runCatching { port.cancel() }
            .onSuccess { _streamingCancelled.emit(Unit) }
            .onFailure { _operationError.emit(ChatOperationError("Failed to cancel the current turn", false)) }
    }

    override suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) {
        val port = sessionPort ?: return
        port.setConfigOption(optionId, SessionSnapshotMapping.toBridgeConfigValue(value))
        if (optionId == PaseoSessionPort.CONFIG_MODEL) {
            _modelUpdated.emit(Unit)
        }
    }

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        sessionPort?.grantPermission(toolCallId, optionId)
    }

    override suspend fun denyPermission(toolCallId: String) {
        sessionPort?.denyPermission(toolCallId)
    }

    override fun clear() {
        snapshotJob?.cancel()
        snapshotJob = null
        connectionStateJob?.cancel()
        connectionStateJob = null
        sessionPort = null
    }

    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        if (connectionState !is ChatConnectionState.Connected) {
            sessionPort = null
        }
    }

    // ---- Internal ----

    private suspend fun resolveTarget(): LaunchableTarget.Paseo? {
        val target = launchableTargetRepository.getTarget(serverId)
        if (target !is LaunchableTarget.Paseo) {
            _loadFailed.emit("This session is not a Paseo target.")
            return null
        }
        return target
    }

    private suspend fun ensureConnected(target: LaunchableTarget.Paseo): Boolean {
        observeConnectionState()
        if (connectionManager.isConnected) {
            ensureProviderDiscovered(target)
            return true
        }
        _connectionState.value = ChatConnectionState.Connecting
        val source = target.paseoSource
        val connected =
            connectionManager.connect(
                sourceId = source.id,
                connection = source.toPaseoConnection(),
            )
        _connectionState.value =
            if (connected) ChatConnectionState.Connected else ChatConnectionState.Failed("Connection failed")
        if (connected) {
            val serverUrl = if (source.isRelay) source.relayEndpoint else "${source.scheme}://${source.host}"
            _diagnostics.value = ChatConnectionDiagnostics(serverUrl = serverUrl)
            ensureProviderDiscovered(target)
        }
        return connected
    }

    private suspend fun ensureProviderDiscovered(target: LaunchableTarget.Paseo) {
        if (provider != null) return
        val providers = connectionManager.discoverProviders(cwd.ifBlank { target.binding.cwd })
        provider = providers.firstOrNull { it.id == target.binding.provider }
    }

    private fun observeConnectionState() {
        if (connectionStateJob != null) return
        val flow = connectionManager.connectionState
        connectionStateJob =
            scope.launch {
                flow.collect { state ->
                    _connectionState.value = mapConnectionState(state)
                    if (state !is PaseoConnectionState.Connected) {
                        sessionPort = null
                    }
                }
            }
    }

    private suspend fun ensureSessionForSend(): SessionPort? {
        sessionPort?.let { return it }
        val target = resolveTarget() ?: return null
        if (!ensureConnected(target)) return null
        connectionManager.getSession(activeSessionId)?.let {
            attachPort(it, target)
            _sessionReady.emit(Unit)
            return it
        }
        val created =
            connectionManager.createSession(
                provider = target.binding.provider,
                cwd = cwd.ifBlank { target.binding.cwd },
                model = target.binding.preferredModelId,
            ) ?: return null
        attachPort(created, target)
        _sessionReady.emit(Unit)
        return created
    }

    private fun attachPort(
        port: SessionPort,
        target: LaunchableTarget.Paseo,
    ) {
        activeSessionId = port.sessionId
        sessionPort = port
        snapshotJob?.cancel()
        snapshotJob =
            scope.launch {
                port.snapshot.collect { snapshot ->
                    _sessionSnapshot.value = SessionSnapshotMapping.mapSnapshot(snapshot)
                }
            }
        // Publish the discovered provider's models/modes as config options.
        scope.launch { publishConfigOptions(port, target) }
    }

    private suspend fun publishConfigOptions(
        port: SessionPort,
        target: LaunchableTarget.Paseo,
    ) {
        val prov = provider ?: return
        val concrete = port as? PaseoSessionPort ?: return
        val options = mutableListOf<SessionConfigOption>()
        if (prov.models.isNotEmpty()) {
            options +=
                SessionConfigOption.Select(
                    id = PaseoSessionPort.CONFIG_MODEL,
                    name = "Model",
                    category = SessionConfigCategory.Model,
                    origin = SessionConfigOrigin.NativeConfigOption,
                    currentValue = target.binding.preferredModelId ?: prov.currentModelId,
                    choices = prov.models.map { SessionConfigChoice(id = it.id, label = it.label, value = it.id) },
                )
        }
        if (prov.modes.isNotEmpty()) {
            options +=
                SessionConfigOption.Select(
                    id = PaseoSessionPort.CONFIG_MODE,
                    name = "Mode",
                    category = SessionConfigCategory.Mode,
                    origin = SessionConfigOrigin.NativeConfigOption,
                    currentValue = prov.currentModeId,
                    choices = prov.modes.map { SessionConfigChoice(id = it.id, label = it.label, value = it.id) },
                )
        }
        if (options.isNotEmpty()) {
            concrete.emitEvent(AppSessionEvent.ConfigOptionsUpdated(options))
        }
    }

    private fun mapConnectionState(state: PaseoConnectionState): ChatConnectionState =
        when (state) {
            PaseoConnectionState.Disconnected -> ChatConnectionState.Disconnected
            PaseoConnectionState.Connecting -> ChatConnectionState.Connecting
            PaseoConnectionState.Connected -> ChatConnectionState.Connected
            is PaseoConnectionState.Failed -> ChatConnectionState.Failed(state.error)
        }
}
