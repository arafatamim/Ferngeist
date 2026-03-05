package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.mikepenz.markdown.model.State as MarkdownRenderState
import com.mikepenz.markdown.model.parseMarkdownFlow
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionLoadState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMode
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val connectionManager: AcpConnectionManager,
    private val serverRepository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<ChatState, ChatIntent, ChatEffect>(ChatState()) {
    companion object {
        private const val TRACE_TAG = "TSChatVM"
        private const val MARKDOWN_FLUSH_INTERVAL_MS = 28L
        private const val MARKDOWN_BATCH_SIZE = 24
        private const val MARKDOWN_STATE_EMIT_INTERVAL_MS = 120L
    }

    private val sessionLoadTimeoutMs = 20_000L

    private val serverId: String = savedStateHandle["serverId"] ?: error("serverId is required")
    private val sessionId: String = savedStateHandle["sessionId"] ?: error("sessionId is required")
    private val cwd: String = savedStateHandle["cwd"] ?: "/"
    private val sessionUpdatedAt: Long? = savedStateHandle.get<Long>("updatedAt")?.takeIf { it > 0L }
    private var activeSessionId: String = sessionId
    private var sessionBridge: SessionBridge? = null
    private var bridgeObserverJobs: List<Job> = emptyList()
    private var pendingModelSelectionId: String? = null
    private var initialMarkdownHydrated: Boolean = false
    private val markdownStateCache = linkedMapOf<String, MarkdownEntry>()
    private val pendingMarkdownQueue = linkedMapOf<String, String>()
    private val markdownParsingKeys = mutableSetOf<String>()
    private var markdownParserJob: Job? = null

    init {
        viewModelScope.launch {
            loadSession()
        }
        viewModelScope.launch {
            connectionManager.connectionState.collect { connectionState ->
                updateState { copy(connectionState = connectionState) }
            }
        }
        viewModelScope.launch {
            connectionManager.diagnostics.collect { diagnostics ->
                updateState { copy(connectionDiagnostics = diagnostics) }
            }
        }
    }

    private suspend fun loadSession() {
        trace("loadSession:start sessionId=$sessionId cwd=$cwd")
        initialMarkdownHydrated = false
        resetMarkdownCache()
        updateState {
            copy(
                messages = emptyList(),
                markdownStates = emptyMap(),
                isLoading = true,
                isStreaming = false,
                isSessionReady = false,
                error = null
            )
        }

        if (!ensureConnectedAndInitialized()) {
            val errorMessage = "Disconnected. Reconnect to refresh this session."
            updateState {
                copy(
                    isLoading = false,
                    error = if (messages.isEmpty()) errorMessage else null
                )
            }
            emitEffect(ChatEffect.ShowError(errorMessage))
            return
        }

        var loadTimedOut = false
        val bridge = try {
            withTimeout(sessionLoadTimeoutMs) {
                connectionManager.loadSession(
                    sessionId = sessionId,
                    cwd = cwd,
                    fallbackHistoryTimestampMs = sessionUpdatedAt,
                )
            }
        } catch (_: TimeoutCancellationException) {
            loadTimedOut = true
            null
        }
        if (bridge != null) {
            trace("loadSession:bridgeReady requested=$sessionId active=${bridge.sessionId}")
            activeSessionId = bridge.sessionId
            sessionBridge = bridge
            // Keep loading state active until SessionLoadComplete is received and
            // replay events are committed in a single state update.
            observeSessionBridge(bridge, deferHistoryHydration = true)
        } else {
            if (loadTimedOut) {
                val fallbackBridge = runCatching {
                    connectionManager.createSession(cwd)
                }.getOrNull()
                if (fallbackBridge != null) {
                    trace("loadSession:fallbackCreated active=${fallbackBridge.sessionId}")
                    activeSessionId = fallbackBridge.sessionId
                    sessionBridge = fallbackBridge
                    observeSessionBridge(fallbackBridge)
                    updateState {
                        copy(
                            isLoading = false,
                            isSessionReady = true,
                            error = null
                        )
                    }
                    emitEffect(
                        ChatEffect.ShowError(
                            "Server did not acknowledge session/load. Opened a new live session."
                        )
                    )
                    return
                }
            }

            val errorMessage = if (loadTimedOut) {
                "Session load timed out. Check server connection and retry."
            } else {
                "Could not load this session. Check connection and retry."
            }
            trace("loadSession:failed timedOut=$loadTimedOut sessionId=$sessionId")
            android.util.Log.e("ChatViewModel", "loadSession failed: bridge is null for sessionId=$sessionId")
            updateState {
                copy(
                    isLoading = false,
                    isSessionReady = false,
                    error = errorMessage
                )
            }
            emitEffect(ChatEffect.ShowError(errorMessage))
        }
    }

    private suspend fun ensureConnectedAndInitialized(): Boolean {
        if (connectionManager.isConnected) return true
        val server = serverRepository.getServer(serverId) ?: run {
            android.util.Log.e("ChatViewModel", "ensureConnectedAndInitialized: server not found for serverId=$serverId")
            return false
        }
        val connected = connectionManager.connect(
            AcpConnectionConfig(
                scheme = server.scheme,
                host = server.host,
                authToken = server.token.takeIf { it.isNotBlank() },
            )
        )
        if (!connected) return false
        val initResult = connectionManager.initialize()
        return initResult
    }

    private fun observeSessionBridge(
        bridge: SessionBridge,
        deferHistoryHydration: Boolean = false,
    ) {
        // Runtime handles hydration internally; kept only for call-site clarity.
        deferHistoryHydration
        clearBridgeObservers()
        bridgeObserverJobs = listOf(
            viewModelScope.launch {
                var lastSignature: String? = null
                bridge.snapshot.collect { snapshot ->
                    val signature = buildString {
                        append("state=")
                        append(snapshot.loadState)
                        append(" messages=")
                        append(snapshot.messages.size)
                        append(" streaming=")
                        append(snapshot.isStreaming)
                        append(" lastId=")
                        append(snapshot.messages.lastOrNull()?.id)
                        append(" lastRole=")
                        append(snapshot.messages.lastOrNull()?.role)
                        append(" lastLen=")
                        append(snapshot.messages.lastOrNull()?.content?.length ?: 0)
                        append(" commands=")
                        append(snapshot.availableCommands.size)
                        append(" modes=")
                        append(snapshot.availableModes.size)
                    }
                    if (signature != lastSignature) {
                        trace("snapshot session=${bridge.sessionId} $signature")
                        lastSignature = signature
                    }

                    val usageState = snapshot.usage?.let { usage ->
                        UsageState(
                            promptTokens = usage.promptTokens,
                            completionTokens = usage.completionTokens,
                            totalTokens = usage.totalTokens,
                            cachedReadTokens = usage.cachedReadTokens,
                            contextWindowTokens = usage.contextWindowTokens,
                            costUsd = usage.costUsd,
                        )
                    }
                    val requiredMarkdownEntries = collectRequiredMarkdownEntries(snapshot.messages)
                    if (!initialMarkdownHydrated && snapshot.loadState != SessionLoadState.FAILED) {
                        preparseMissingMarkdownEntries(requiredMarkdownEntries)
                        if (snapshot.loadState == SessionLoadState.READY) {
                            initialMarkdownHydrated = true
                        }
                    }
                    val markdownStates = buildMarkdownEntries(snapshot.messages)
                    updateState {
                        val failed = snapshot.loadState == SessionLoadState.FAILED
                        val pendingInitialMarkdown =
                            !failed &&
                                !initialMarkdownHydrated &&
                                snapshot.messages.isNotEmpty()
                        copy(
                            messages = snapshot.messages,
                            markdownStates = markdownStates,
                            isStreaming = snapshot.isStreaming,
                            usage = usageState,
                            availableCommands = snapshot.availableCommands,
                            commandsAdvertised = snapshot.commandsAdvertised,
                            availableModes = snapshot.availableModes,
                            currentModeId = snapshot.currentModeId,
                            configOptions = snapshot.configOptions,
                            isLoading = snapshot.loadState == SessionLoadState.HYDRATING || pendingInitialMarkdown,
                            isSessionReady = snapshot.loadState == SessionLoadState.READY && !pendingInitialMarkdown,
                            error = if (failed) {
                                snapshot.error ?: "Could not load this session. Check connection and retry."
                            } else {
                                null
                            }
                        )
                    }
                }
            },
            viewModelScope.launch {
                bridge.events.collect { event ->
                    trace("event session=${bridge.sessionId} type=${event::class.simpleName}")
                    if (event is AppSessionEvent.ModelSelectionConfirmed) {
                        val pendingModel = pendingModelSelectionId
                        if (pendingModel != null &&
                            (event.modelId.isNullOrBlank() || event.modelId == pendingModel)
                        ) {
                            pendingModelSelectionId = null
                            emitEffect(ChatEffect.ShowMessage("Model updated"))
                        }
                    }
                }
            }
        )
    }

    private fun clearBridgeObservers() {
        bridgeObserverJobs.forEach { it.cancel() }
        bridgeObserverJobs = emptyList()
    }

    private fun collectRequiredMarkdownEntries(
        messages: List<ChatMessage>,
    ): LinkedHashMap<String, String> {
        val requiredEntries = linkedMapOf<String, String>()
        messages.forEach { message ->
            if (message.role != ChatMessage.Role.ASSISTANT) return@forEach
            if (message.segments.isNotEmpty()) {
                message.segments.forEach { segment ->
                    if (segment.kind == AssistantSegment.Kind.MESSAGE && segment.text.isNotBlank()) {
                        requiredEntries[segment.id] = segment.text
                    }
                }
            } else if (message.content.isNotBlank()) {
                requiredEntries[message.id] = message.content
            }
        }
        return requiredEntries
    }

    private suspend fun preparseMissingMarkdownEntries(
        requiredEntries: Map<String, String>,
    ) {
        requiredEntries.forEach { (key, text) ->
            val cached = markdownStateCache[key]
            if (cached?.text == text) return@forEach
            try {
                val parsedState = withContext(Dispatchers.Default) {
                    parseMarkdownFlow(text)
                        .first { it !is MarkdownRenderState.Loading }
                }
                markdownStateCache[key] = MarkdownEntry(text = text, state = parsedState)
                pendingMarkdownQueue.remove(key)
                markdownParsingKeys.remove(key)
            } catch (error: Throwable) {
                trace("markdownPreparse:error key=$key message=${error.message}")
            }
        }
    }

    private fun buildMarkdownEntries(
        messages: List<ChatMessage>,
    ): Map<String, MarkdownRenderState> {
        val requiredEntries = collectRequiredMarkdownEntries(messages)

        val requiredKeys = requiredEntries.keys
        markdownStateCache.keys.toList()
            .filterNot(requiredKeys::contains)
            .forEach { markdownStateCache.remove(it) }
        pendingMarkdownQueue.keys.toList()
            .filterNot(requiredKeys::contains)
            .forEach { pendingMarkdownQueue.remove(it) }
        markdownParsingKeys.retainAll(requiredKeys)

        requiredEntries.forEach { (key, text) ->
            val cached = markdownStateCache[key]
            if (cached?.text == text) return@forEach
            pendingMarkdownQueue[key] = text
        }

        if (pendingMarkdownQueue.isNotEmpty()) {
            scheduleMarkdownParsing()
        }

        return requiredKeys.mapNotNull { key ->
            markdownStateCache[key]?.state?.let { key to it }
        }.toMap()
    }

    private fun scheduleMarkdownParsing() {
        if (markdownParserJob?.isActive == true) return
        markdownParserJob = viewModelScope.launch {
            var lastEmitAtMs = 0L
            try {
                while (pendingMarkdownQueue.isNotEmpty()) {
                    val batch = mutableListOf<Pair<String, String>>()
                    val iterator = pendingMarkdownQueue.entries.iterator()
                    while (iterator.hasNext() && batch.size < MARKDOWN_BATCH_SIZE) {
                        val entry = iterator.next()
                        iterator.remove()
                        batch += entry.key to entry.value
                        markdownParsingKeys += entry.key
                    }

                    val parsedBatch = mutableListOf<Pair<String, MarkdownEntry>>()
                    batch.forEach { (key, text) ->
                        try {
                            val parsedState = withContext(Dispatchers.Default) {
                                parseMarkdownFlow(text)
                                    .first { it !is MarkdownRenderState.Loading }
                            }
                            parsedBatch += key to MarkdownEntry(text = text, state = parsedState)
                        } catch (error: Throwable) {
                            trace("markdownParse:error key=$key message=${error.message}")
                        } finally {
                            markdownParsingKeys.remove(key)
                        }
                    }

                    parsedBatch.forEach { (key, entry) ->
                        val queuedOverride = pendingMarkdownQueue[key]
                        if (queuedOverride == null || queuedOverride == entry.text) {
                            markdownStateCache[key] = entry
                        }
                    }

                    val now = System.currentTimeMillis()
                    val shouldEmitState = pendingMarkdownQueue.isEmpty() ||
                        (now - lastEmitAtMs) >= MARKDOWN_STATE_EMIT_INTERVAL_MS
                    if (shouldEmitState) {
                        lastEmitAtMs = now
                        publishMarkdownStatesForCurrentMessages()
                    }

                    if (pendingMarkdownQueue.isNotEmpty()) {
                        delay(MARKDOWN_FLUSH_INTERVAL_MS)
                    }
                }
            } finally {
                markdownParserJob = null
            }
        }
    }

    private fun publishMarkdownStatesForCurrentMessages() {
        updateState {
            val nextMarkdownStates = buildMarkdownEntries(messages)
            if (nextMarkdownStates == markdownStates) this
            else copy(markdownStates = nextMarkdownStates)
        }
    }

    private fun resetMarkdownCache() {
        markdownParserJob?.cancel()
        markdownParserJob = null
        markdownStateCache.clear()
        pendingMarkdownQueue.clear()
        markdownParsingKeys.clear()
    }

    private data class MarkdownEntry(
        val text: String,
        val state: MarkdownRenderState,
    )

    override fun onCleared() {
        resetMarkdownCache()
        clearBridgeObservers()
        super.onCleared()
    }
    
    override suspend fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> sendMessage(intent.text, intent.images)
            is ChatIntent.CancelStreaming -> cancelStreaming()
            is ChatIntent.SetMode -> setMode(intent.modeId)
            is ChatIntent.SetConfigOption -> setConfigOption(intent.optionId, intent.value)
            is ChatIntent.GrantPermission -> grantPermission(intent.toolCallId, intent.optionId)
            is ChatIntent.DenyPermission -> denyPermission(intent.toolCallId)
            is ChatIntent.ToggleToolCallExpansion -> toggleToolCallExpansion(intent.toolCallId)
            is ChatIntent.RetryLoad -> loadSession()
        }
    }

    private suspend fun sendMessage(text: String, images: List<ChatImageData>) {
        if (text.isBlank() && images.isEmpty()) return


        val bridge = ensureSessionReadyForSend()
        if (bridge == null) {
            val errorMessage = "Session is not ready. Please retry in a moment."
            trace("sendMessage: bridge missing activeSessionId=$activeSessionId")
            emitEffect(ChatEffect.ShowError(errorMessage))
            return
        }

        // Send prompt to ACP session
        val imagePairs = images.map { Pair(it.base64, it.mimeType) }
        try {
            trace("sendMessage: session=${bridge.sessionId} textLen=${text.length} images=${imagePairs.size}")
            // We don't collect here because observeSessionBridge is already collecting bridge.events
            bridge.sendPrompt(text, imagePairs)
        } catch (e: Exception) {
            val errorMessage = userFacingSendError(e)
            android.util.Log.e("ChatViewModel", "sendMessage failed for sessionId=$activeSessionId", e)
            trace("sendMessage:error session=$activeSessionId message=${e.message}")
            updateState {
                copy(
                    isStreaming = false,
                    error = errorMessage
                )
            }
            emitEffect(ChatEffect.ShowError(errorMessage))
        }
    }

    private suspend fun ensureSessionReadyForSend(): SessionBridge? {
        sessionBridge?.let { return it }
        if (!ensureConnectedAndInitialized()) return null

        connectionManager.getSession(activeSessionId)?.let { existing ->
            sessionBridge = existing
            observeSessionBridge(existing)
            updateState { copy(isSessionReady = true, isLoading = false, error = null) }
            return existing
        }

        val created = runCatching { connectionManager.createSession(cwd) }.getOrNull() ?: return null
        activeSessionId = created.sessionId
        sessionBridge = created
        observeSessionBridge(created)
        updateState { copy(isSessionReady = true, isLoading = false, error = null) }
        return created
    }

    private fun userFacingSendError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Request timeout", ignoreCase = true) ->
                "Request timed out. Please try again."
            raw.contains("Invalid params", ignoreCase = true) ->
                "Send failed due to an invalid request format."
            raw.isNotBlank() ->
                "Send failed. Please try again."
            else ->
                "Send failed due to an unknown error."
        }
    }

    private suspend fun cancelStreaming() {
        trace("cancelStreaming requested session=$activeSessionId")
        val result = runCatching { sessionBridge?.cancel() }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (isSessionCancelUnsupported(error)) {
                updateState { copy(canCancelStreaming = false) }
                emitEffect(ChatEffect.ShowMessage("Cancel is not supported by this server"))
                return
            }
            emitEffect(ChatEffect.ShowError("Failed to cancel the current turn"))
            return
        }
        updateState { copy(isStreaming = false) }
    }

    private fun trace(message: String) {
        Log.d(TRACE_TAG, message)
    }

    private fun isSessionCancelUnsupported(error: Throwable): Boolean {
        val raw = error.message.orEmpty()
        return raw.contains("Method not found", ignoreCase = true) &&
            raw.contains("session/cancel", ignoreCase = true)
    }

    private suspend fun setMode(modeId: String) {
        sessionBridge?.setMode(modeId)
    }

    private suspend fun setConfigOption(optionId: String, value: String) {
        if (optionId == "model") {
            pendingModelSelectionId = value
            sessionBridge?.setModel(value)
        } else {
            sessionBridge?.setConfigOption(optionId, value)
        }
    }

    private suspend fun grantPermission(toolCallId: String, optionId: String) {
        sessionBridge?.grantPermission(toolCallId, optionId)
    }

    private suspend fun denyPermission(toolCallId: String) {
        sessionBridge?.denyPermission(toolCallId)
    }

    private fun toggleToolCallExpansion(toolCallId: String) {
        updateState { 
            copy(
                expandedToolCalls = if (expandedToolCalls.contains(toolCallId)) {
                    expandedToolCalls - toolCallId
                } else {
                    expandedToolCalls + toolCallId
                }
            ) 
        }
    }

}
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val markdownStates: Map<String, MarkdownRenderState> = emptyMap(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: AcpConnectionState = AcpConnectionState.Disconnected,
    val connectionDiagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    val currentModeId: String? = null,
    val availableModes: List<SessionMode> = emptyList(),
    val configOptions: List<SessionConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<String> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val expandedToolCalls: Set<String> = emptySet(),
    val error: String? = null,
)

data class UsageState(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedReadTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val costUsd: Double? = null,
)

sealed interface ChatIntent {
    data class SendMessage(val text: String, val images: List<ChatImageData> = emptyList()) : ChatIntent
    data object CancelStreaming : ChatIntent
    data class SetMode(val modeId: String) : ChatIntent
    data class SetConfigOption(val optionId: String, val value: String) : ChatIntent
    data class GrantPermission(val toolCallId: String, val optionId: String) : ChatIntent
    data class DenyPermission(val toolCallId: String) : ChatIntent
    data class ToggleToolCallExpansion(val toolCallId: String) : ChatIntent
    data object RetryLoad : ChatIntent
}

sealed interface ChatEffect {
    data class ShowError(val message: String) : ChatEffect
    data class ShowMessage(val message: String) : ChatEffect
    data object NavigateBack : ChatEffect
}
