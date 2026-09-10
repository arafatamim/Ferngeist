package com.tamimarafat.ferngeist.feature.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.mikepenz.markdown.model.State as MarkdownRenderState

/**
 * Orchestrates chat UI state by binding the session facade, scroll state, and markdown parsing.
 *
 * The view model holds no ACP details; all transport-specific logic is delegated to the facade.
 */
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        sessionFacadeFactory: ChatSessionFacadeFactory,
        private val sessionRepository: SessionRepository,
        private val chatScrollStateStore: ChatScrollStateStore,
        val recentSelectionStore: RecentSelectionStore,
        private val chatConnectionHub: ChatConnectionHub,
        private val gatewayRepository: GatewayRepository,
        savedStateHandle: SavedStateHandle,
    ) : MviViewModel<ChatState, ChatIntent, ChatEffect>(initialChatState()) {
        companion object {
            private const val TRACE_TAG = "TSChatVM"

            private fun initialChatState(): ChatState = ChatState()
        }

        private val serverId: String = savedStateHandle["serverId"] ?: error("serverId is required")
        private val sessionId: String = savedStateHandle["sessionId"] ?: error("sessionId is required")
        val cwd: String = savedStateHandle["cwd"] ?: ""
        private val sessionUpdatedAt: Long? = savedStateHandle.get<Long>("updatedAt")?.takeIf { it > 0L }
        private val sessionTitle: String =
            savedStateHandle.get<String>("title")?.let { Uri.decode(it) }.orEmpty()

        /**
         * Session id this chat's hub presence is tracked under (deep-link and
         * push-suppression identity). Matches the nav-arg [sessionId] except for
         * create-on-arrival chats, whose nav arg is still the [NEW_SESSION_ARG]
         * sentinel until the facade mints a real session; the
         * [sessionFacade.liveChatId] collector promotes this and re-opens hub
         * presence under the real id once that happens.
         */
        private var trackedSessionId: String = sessionId

        private val sessionFacade: ChatSessionFacade =
            sessionFacadeFactory.create(
                scope = viewModelScope,
                serverId = serverId,
                sessionId = sessionId,
                cwd = cwd,
            )
        private val markdownStateStore =
            MarkdownStateStore(
                scope = viewModelScope,
                currentMessages = { state.value.messages },
                onMarkdownStatesChanged = { markdownStates ->
                    updateState {
                        if (markdownStates == this.markdownStates) {
                            this
                        } else {
                            copy(markdownStates = markdownStates)
                        }
                    }
                },
            )
        private val sessionCoordinator =
            ChatSessionCoordinator(
                scope = viewModelScope,
                facade = sessionFacade,
                callbacks =
                    object : ChatSessionCoordinator.Callbacks {
                        override suspend fun onLoadStarted() {
                            markdownStateStore.reset()
                            updateState {
                                copy(
                                    messages = emptyList(),
                                    markdownStates = emptyMap(),
                                    isLoading = true,
                                    isStreaming = false,
                                    isSessionReady = false,
                                    canCancelStreaming = true,
                                    error = null,
                                )
                            }
                        }

                        override suspend fun onSnapshot(snapshot: ChatSessionSnapshot) {
                            applySnapshot(snapshot)
                        }

                        override suspend fun onSessionReady() {
                            updateState {
                                copy(
                                    isLoading = false,
                                    isSessionReady = true,
                                    canCancelStreaming = true,
                                    error = null,
                                )
                            }
                        }

                        override suspend fun onSessionStored(
                            sessionId: String,
                            cwd: String,
                            updatedAt: Long,
                        ) {
                            sessionRepository.upsertSession(
                                serverId = serverId,
                                summary =
                                    SessionSummary(
                                        id = sessionId,
                                        cwd = cwd,
                                        updatedAt = updatedAt,
                                    ),
                            )
                        }

                        override suspend fun onLoadFailed(message: String) {
                            updateState {
                                copy(
                                    isLoading = false,
                                    isSessionReady = false,
                                    error = message,
                                )
                            }
                            emitEffect(ChatEffect.ShowError(message))
                        }

                        override suspend fun onOperationError(
                            message: String,
                            stopStreaming: Boolean,
                        ) {
                            if (stopStreaming) {
                                updateState {
                                    copy(
                                        isStreaming = false,
                                        error = message,
                                    )
                                }
                            }
                            // If an operation error arrives while a queued prompt is in-flight,
                            // mark that prompt FAILED so it is visible for manual retry.
                            inFlightClientId?.let { clientId ->
                                inFlightClientId = null
                                updateState {
                                    val updated =
                                        pendingMessages.map { msg ->
                                            if (msg.clientId == clientId &&
                                                msg.status == MessageDeliveryStatus.SENDING
                                            ) {
                                                msg.copy(status = MessageDeliveryStatus.FAILED)
                                            } else {
                                                msg
                                            }
                                        }
                                    copy(pendingMessages = updated)
                                }
                            }
                            emitEffect(ChatEffect.ShowError(message))
                        }

                        override suspend fun onStreamingCancelled() {
                            updateState { copy(isStreaming = false) }
                        }

                        override suspend fun onCancelUnsupported() {
                            updateState { copy(canCancelStreaming = false) }
                            emitEffect(ChatEffect.ShowMessage("Cancel is not supported by this server"))
                        }

                        override suspend fun onModelUpdated() {
                            emitEffect(ChatEffect.ShowMessage("Model updated"))
                        }

                        override suspend fun onCapabilitiesChanged(capabilities: ChatAgentCapabilities) {
                            updateState {
                                copy(
                                    canSendImages = capabilities.canSendImages,
                                    supportsEmbeddedContext = capabilities.supportsEmbeddedContext,
                                )
                            }
                        }
                    },
            )

        /** Queue of locally-created prompts that have not been delivered to the server yet. */
        private val offlineQueue = OfflineQueue()

        /** The clientId of the prompt currently being dispatched via [flushOfflineQueue].
         *  Used to wire [onOperationError] back to the specific SENDING bubble. */
        private var inFlightClientId: String? = null

        /** Guards [flushOfflineQueue] so overlapping sessionReady/retry calls cannot interleave. */
        private val flushMutex = Mutex()
        private var reconnectKickJob: Job? = null

        /**
         * In-flight [ChatIntent.LoadGitDiff] fetch. Cancelling it on a newer request
         * (and checking it after the suspend call) guarantees a stale response can
         * never overwrite the state a newer request set.
         */
        private var gitFileDiffJob: Job? = null

        /**
         * Serializes [ChatIntent.LoadGitDiff] request setup (job cancellation, state
         * initialization, job assignment) and the post-fetch identity check, so rapid
         * intents cannot race through those steps. `withLock` keeps the holder
         * cancellable, and the identity guard inside the lock retains latest-wins.
         */
        private val gitFileDiffMutex = Mutex()

        init {
            updateState { copy(serverId = serverId) }
            // Announce this chat's screen presence to the hub so the connection
            // notification deep-links back here instead of dropping the user on
            // the home screen. A create-on-arrival chat still holds the __new__
            // nav-arg sentinel here and is refused by the hub (it is a nav
            // placeholder, not a chat identity); its presence opens under the
            // real session id once the facade's liveChatId collector promotes it.
            if (sessionId != NEW_SESSION_ARG) {
                chatConnectionHub.chatScreenOpened(serverId, sessionId, cwd)
            }
            resolveSessionTitle()
            viewModelScope.launch {
                val snapshot = chatScrollStateStore.restore(serverId, sessionId)
                updateState { copy(restoredScrollSnapshot = snapshot) }
            }
            viewModelScope.launch {
                // The transport entry (created by the facade's register) becomes
                // screen-open for real ids here. Existing-session chats were already
                // announced in init; create-on-arrival chats still hold the __new__
                // sentinel nav arg and only become a real chat when the facade mints
                // a session, so this second open is an idempotent screen-on merge
                // under the real id — there is no store re-key or hub focus anymore.
                sessionFacade.liveChatId.collect { chatId ->
                    if (chatId != null) {
                        if (trackedSessionId == NEW_SESSION_ARG) {
                            trackedSessionId = chatId.substringAfter('/')
                            chatConnectionHub.chatScreenOpened(serverId, trackedSessionId, cwd)
                        }
                        // chatId is "$serverId/$sessionId" and carries the REAL
                        // session id even for create-on-arrival chats, where the
                        // nav arg is still the __new__ sentinel. Record which
                        // gateway session owns it so cold closes and reattaches
                        // can find it after process death.
                        val gatewaySessionId = chatConnectionHub.gatewaySessionIdFor(chatId)
                        if (gatewaySessionId != null) {
                            val realSessionId = chatId.substringAfter('/')
                            withContext(Dispatchers.IO) {
                                sessionRepository.setGatewaySessionId(serverId, realSessionId, gatewaySessionId)
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                sessionCoordinator.attachCachedThenLoad()
            }
            viewModelScope.launch {
                sessionFacade.connectionState.collect { connectionState ->
                    updateState {
                        copy(
                            connectionState = connectionState,
                            isSessionReady =
                                if (connectionState is ChatConnectionState.Connected) {
                                    isSessionReady
                                } else {
                                    false
                                },
                            isStreaming =
                                if (connectionState is ChatConnectionState.Connected) {
                                    isStreaming
                                } else {
                                    false
                                },
                        )
                    }
                }
            }

            // Flush offline queue when connection and session are both ready.
            viewModelScope.launch {
                sessionFacade.sessionReady.collect {
                    flushOfflineQueue()
                    // Retry the git-status fetch once the session is fully initialized,
                    // in case an earlier attempt raced session setup and failed.
                    state.value.gatewayWorkspaceConnection?.let { refreshGitStatus(it) }
                }
            }
            viewModelScope.launch {
                sessionFacade.diagnostics.collect { diagnostics ->
                    updateState { copy(connectionDiagnostics = diagnostics) }
                }
            }
            viewModelScope.launch {
                sessionFacade.gatewayWorkspaceConnection.collect { connection ->
                    updateState { copy(gatewayWorkspaceConnection = connection) }
                    if (connection != null) refreshGitStatus(connection)
                }
            }
        }

        /**
         * Fetches the git status for a gateway-backed working tree and stores line-based
         * add/delete totals in [ChatState.gitDiffStats]. The gateway returns per-file
         * `added`/`removed` counts (from `git diff --numstat`, with untracked files counted
         * from disk), so the totals are git's own authoritative numbers.
         *
         * Errors keep the previous value so a transient poll failure does not clear the
         * indicator.
         */
        private fun refreshGitStatus(connection: GatewayWorkspaceConnection) {
            viewModelScope.launch {
                runCatching {
                    val status =
                        gatewayRepository.fetchGitStatus(
                            scheme = connection.scheme,
                            host = connection.host,
                            gatewayCredential = connection.gatewayCredential,
                            runtimeId = connection.runtimeId,
                        )
                    GitDiffStats(
                        additions = status.changed.sumOf { it.added },
                        deletions = status.changed.sumOf { it.removed },
                    ).let { stats ->
                        updateState { copy(gitStatus = status, gitDiffStats = stats) }
                    }
                }
            }
        }

        /**
         * Fetches the unified diff for a single file in the gateway-backed working
         * tree and stores it in [ChatState.gitFileDiff] (the UI renders the first
         * item). The gateway returns a one-element list for a given [path].
         *
         * On exception the requested path is retained so the UI can retry the same
         * file, the diff is cleared, and a nonblank error is exposed.
         */
        private suspend fun loadGitFileDiff(path: String) {
            val connection = state.value.gatewayWorkspaceConnection
            if (connection == null) {
                setGitFileDiffError(path, "No gateway workspace connection to load git diff for $path")
                return
            }
            gitFileDiffMutex.withLock {
                gitFileDiffJob?.cancel()
                updateState {
                    copy(
                        gitFileDiffPath = path,
                        isGitFileDiffLoading = true,
                        gitFileDiffError = null,
                        gitFileDiff = null,
                    )
                }
                gitFileDiffJob =
                    viewModelScope.launch {
                        val currentJob = coroutineContext[Job]
                        val result =
                            runCatching {
                                gatewayRepository.fetchGitDiff(
                                    scheme = connection.scheme,
                                    host = connection.host,
                                    gatewayCredential = connection.gatewayCredential,
                                    runtimeId = connection.runtimeId,
                                    path = path,
                                )
                            }
                        gitFileDiffMutex.withLock {
                            if (currentJob != gitFileDiffJob) return@withLock
                            handleGitDiffResult(result, path)
                        }
                    }
            }
        }

        private suspend fun setGitFileDiffError(
            path: String,
            error: String,
        ) {
            gitFileDiffMutex.withLock {
                gitFileDiffJob?.cancel()
                gitFileDiffJob = null
                updateState {
                    copy(
                        gitFileDiffPath = path,
                        isGitFileDiffLoading = false,
                        gitFileDiff = null,
                        gitFileDiffError = error,
                    )
                }
            }
        }

        private fun handleGitDiffResult(
            result: Result<List<ToolCallContent.Diff>>,
            path: String,
        ) {
            result
                .onSuccess { diffs ->
                    updateState {
                        copy(
                            gitFileDiff = diffs,
                            isGitFileDiffLoading = false,
                            gitFileDiffError = null,
                        )
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) return
                    val message =
                        throwable.message?.takeIf { it.isNotBlank() }
                            ?: "Failed to load git diff for $path"
                    updateState {
                        copy(
                            gitFileDiff = null,
                            isGitFileDiffLoading = false,
                            gitFileDiffError = message,
                        )
                    }
                }
        }

        /**
         * Populates [ChatState.title] for the app bar. The deep-link path (push
         * notification tap) can't carry the real session name, so the nav-arg
         * `title` is blank and we look it up from the local session store. When the
         * nav arg already has a title (session list -> chat) we use that and skip the
         * lookup to avoid a stale read.
         */
        private fun resolveSessionTitle() {
            if (sessionTitle.isNotBlank()) {
                updateState { copy(title = sessionTitle) }
                return
            }
            viewModelScope.launch {
                val resolved = sessionRepository.getSession(serverId, sessionId)?.title
                if (!resolved.isNullOrBlank() && state.value.title.isNullOrBlank()) {
                    updateState { copy(title = resolved) }
                }
            }
        }

        /**
         * Applies a snapshot from the facade to UI state, keeping markdown hydration in sync.
         *
         * When the snapshot carries a USER message whose content+images match a
         * pending SENDING bubble (the reducer echo), that pending bubble is removed
         * because the echoed message in [messages] is the canonical delivery.
         */
        private suspend fun applySnapshot(snapshot: ChatSessionSnapshot) {
            val markdownProjection =
                markdownStateStore.onSnapshot(
                    messages = snapshot.messages,
                    loadState = snapshot.loadState,
                )
            val reconciledPending = reconcileSendingPendingBubbles(snapshot.messages)
            val failed = snapshot.loadState == ChatLoadState.FAILED
            updateState {
                copy(
                    messages = snapshot.messages,
                    pendingMessages = reconciledPending,
                    markdownStates = markdownProjection.markdownStates,
                    isStreaming = snapshot.isStreaming,
                    usage = snapshot.usage,
                    availableCommands = snapshot.availableCommands,
                    commandsAdvertised = snapshot.commandsAdvertised,
                    configOptions = snapshot.configOptions,
                    isLoading =
                        snapshot.loadState == ChatLoadState.HYDRATING ||
                            markdownProjection.pendingInitialHydration,
                    isSessionReady =
                        snapshot.loadState == ChatLoadState.READY &&
                            !markdownProjection.pendingInitialHydration,
                    error =
                        if (failed) {
                            snapshot.error ?: "Could not load this session. Check connection and retry."
                        } else {
                            null
                        },
                )
            }
            applyServerTitle(snapshot.title)
        }

        /**
         * Reconciles SENDING pending bubbles with their reducer echo in [messages].
         * A SENDING bubble is removed when a matching USER message appears in the
         * snapshot (content + images + files match), because the echoed message
         * in [messages] is the canonical delivery.
         */
        private fun reconcileSendingPendingBubbles(messages: List<ChatMessage>): List<ChatMessage> {
            val pendingSending =
                state.value.pendingMessages.filter {
                    it.status == MessageDeliveryStatus.SENDING
                }
            if (pendingSending.isEmpty()) return state.value.pendingMessages
            val echoClientIds = findEchoedClientIds(pendingSending, messages)
            return if (echoClientIds.isEmpty()) {
                state.value.pendingMessages
            } else {
                state.value.pendingMessages.filterNot {
                    (it.clientId ?: it.id) in echoClientIds
                }
            }
        }

        private fun findEchoedClientIds(
            pendingSending: List<ChatMessage>,
            messages: List<ChatMessage>,
        ): Set<String> {
            val echoClientIds = mutableSetOf<String>()
            for (sending in pendingSending) {
                val echoed =
                    messages.firstOrNull { msg ->
                        msg.role == ChatMessage.Role.USER &&
                            msg.content == sending.content &&
                            msg.images == sending.images &&
                            msg.files == sending.files
                    }
                if (echoed != null) {
                    echoClientIds.add(sending.clientId ?: sending.id)
                }
            }
            return echoClientIds
        }

        /**
         * Applies the server-provided session title when the current session has no title yet.
         * The server emits SessionInfoUpdate after the first assistant response completes;
         * this title is the canonical session name and should not overwrite an existing one.
         * Uses a targeted UPDATE (not upsert) to preserve updatedAt and all other columns.
         */
        private suspend fun applyServerTitle(serverTitle: String?) {
            if (serverTitle.isNullOrBlank() || !state.value.title.isNullOrBlank()) return
            updateState { copy(title = serverTitle) }
            sessionRepository.updateSessionTitle(
                serverId = serverId,
                sessionId = sessionId,
                title = serverTitle,
            )
        }

        override fun onCleared() {
            markdownStateStore.dispose()
            sessionCoordinator.clear()
            // Leaving the screen drops this chat's screen presence: the pooled
            // transport entry survives (screenOpen=false) as the notification
            // tap target until the hub evicts it via LRU pressure or an explicit
            // session-list close, so a backgrounded chat keeps streaming and the
            // tap can still return to it. A screen that never attached a
            // transport (create-on-arrival that failed to mint) has no pooled
            // entry, so chatScreenClosed is a no-op there.
            chatConnectionHub.chatScreenClosed(serverId, trackedSessionId)
        }

        /**
         * Routes UI intents to the coordinator so ACP details stay outside the view model.
         */
        override suspend fun handleIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.SendMessage -> {
                    val canSendNow =
                        state.value.isSessionReady &&
                            state.value.connectionState == ChatConnectionState.Connected
                    if (canSendNow) {
                        enqueueThenSend(intent.text, intent.images, intent.files)
                    } else {
                        enqueuePrompt(intent.text, intent.images, intent.files)
                    }
                    state.value.gatewayWorkspaceConnection?.let { refreshGitStatus(it) }
                }
                is ChatIntent.CancelStreaming -> sessionCoordinator.cancelStreaming()
                is ChatIntent.SetConfigOption -> sessionCoordinator.setConfigOption(intent.optionId, intent.value)
                is ChatIntent.GrantPermission -> sessionCoordinator.grantPermission(intent.toolCallId, intent.optionId)
                is ChatIntent.DenyPermission -> sessionCoordinator.denyPermission(intent.toolCallId)
                is ChatIntent.RetryLoad -> sessionCoordinator.loadSession()
                is ChatIntent.RetryMessage -> retryMessage(intent.clientId)
                is ChatIntent.RefreshGitStatus ->
                    state.value.gatewayWorkspaceConnection?.let { refreshGitStatus(it) }
                is ChatIntent.LoadGitDiff -> loadGitFileDiff(intent.path)
            }
        }

        // region: Offline queue

        /**
         * Optimistically adds a user bubble with [MessageDeliveryStatus.QUEUED] and
         * stores the prompt in [offlineQueue] for later delivery.
         */
        private fun enqueuePrompt(
            text: String,
            images: List<ChatImageData>,
            files: List<ChatFileData>,
        ) {
            if (text.isBlank() && images.isEmpty() && files.isEmpty()) return
            val clientId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val message =
                ChatMessage(
                    id = clientId,
                    role = ChatMessage.Role.USER,
                    content = text,
                    images = images,
                    files = files,
                    status = MessageDeliveryStatus.QUEUED,
                    clientId = clientId,
                )
            offlineQueue.enqueue(
                PendingPrompt(
                    clientId = clientId,
                    text = text,
                    images = images,
                    files = files,
                ),
            )
            updateState {
                copy(pendingMessages = pendingMessages + message)
            }
            if (state.value.connectionState != ChatConnectionState.Connected) {
                kickReconnect()
            }
        }

        /**
         * Ensures a single reconnect attempt is in flight so a prompt queued while
         * offline has a path forward. The next enqueue re-triggers once this settles.
         */
        private fun kickReconnect() {
            if (reconnectKickJob?.isActive == true) return
            reconnectKickJob = viewModelScope.launch { sessionCoordinator.reconnect() }
        }

        /**
         * Enqueues the prompt then immediately tries to send it while the session is ready.
         * Used by [handleIntent] for the online-send path so the offline queue is always the
         * source of truth and the flush path is uniform.
         */
        private suspend fun enqueueThenSend(
            text: String,
            images: List<ChatImageData>,
            files: List<ChatFileData>,
        ) {
            if (text.isBlank() && images.isEmpty() && files.isEmpty()) return
            val clientId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val message =
                ChatMessage(
                    id = clientId,
                    role = ChatMessage.Role.USER,
                    content = text,
                    images = images,
                    files = files,
                    status = MessageDeliveryStatus.QUEUED,
                    clientId = clientId,
                )
            val prompt =
                PendingPrompt(
                    clientId = clientId,
                    text = text,
                    images = images,
                    files = files,
                )
            offlineQueue.enqueue(prompt)
            updateState {
                copy(pendingMessages = pendingMessages + message)
            }
            flushOfflineQueue()
        }

        /**
         * Drains [offlineQueue] in FIFO order under [flushMutex].
         *
         * Each prompt transitions QUEUED -> SENDING *without* being removed from
         * [ChatState.pendingMessages].  The pending bubble stays visible until:
         * - The reducer echo arrives via [applySnapshot] (SENT: the echo replaces it), or
         * - [sendMessage] returns false (FAILED: no bridge), or
         * - An [onOperationError] fires while the prompt is in-flight (FAILED).
         */
        private suspend fun flushOfflineQueue() {
            flushMutex.withLock {
                while (!offlineQueue.isEmpty) {
                    val prompt = offlineQueue.dequeue() ?: break
                    // Transition QUEUED -> SENDING, keep the bubble visible.
                    inFlightClientId = prompt.clientId
                    updateState {
                        val updated =
                            pendingMessages.map { msg ->
                                if (msg.clientId == prompt.clientId &&
                                    msg.status == MessageDeliveryStatus.QUEUED
                                ) {
                                    msg.copy(status = MessageDeliveryStatus.SENDING)
                                } else {
                                    msg
                                }
                            }
                        copy(pendingMessages = updated)
                    }
                    val dispatched = sessionCoordinator.sendMessage(prompt.text, prompt.images, prompt.files)
                    inFlightClientId = null
                    if (!dispatched) {
                        // No bridge / not ready / unsupported -> mark FAILED immediately.
                        updateState {
                            val updated =
                                pendingMessages.map { msg ->
                                    if (msg.clientId == prompt.clientId &&
                                        msg.status == MessageDeliveryStatus.SENDING
                                    ) {
                                        msg.copy(status = MessageDeliveryStatus.FAILED)
                                    } else {
                                        msg
                                    }
                                }
                            copy(pendingMessages = updated)
                        }
                        emitEffect(ChatEffect.ShowError("Failed to send message"))
                    }
                    // When dispatched=true, the bubble stays SENDING until the echoed
                    // USER message appears in the snapshot (applySnapshot) or an
                    // operationError fires.
                }
            }
        }

        /** Retries a single FAILED message by re-enqueueing it and flushing.
         *  Other QUEUED prompts are preserved in FIFO order. */
        private suspend fun retryMessage(clientId: String) {
            val pendingMessage = state.value.pendingMessages.firstOrNull { it.clientId == clientId }
            if (pendingMessage == null || pendingMessage.status != MessageDeliveryStatus.FAILED) return

            val prompt =
                PendingPrompt(
                    clientId = clientId,
                    text = pendingMessage.content,
                    images = pendingMessage.images,
                    files = pendingMessage.files,
                )
            // Remove existing queue entry for this clientId, then enqueue at the back.
            offlineQueue.removeByClientId(clientId)
            offlineQueue.enqueue(prompt)
            // Reset the bubble's status from FAILED to QUEUED so flushOfflineQueue
            // can transition it QUEUED -> SENDING and the echo-reconcile in
            // applySnapshot can remove it on delivery confirmation.
            updateState {
                val updated =
                    pendingMessages.map { msg ->
                        if (msg.clientId == clientId &&
                            msg.status == MessageDeliveryStatus.FAILED
                        ) {
                            msg.copy(status = MessageDeliveryStatus.QUEUED)
                        } else {
                            msg
                        }
                    }
                copy(pendingMessages = updated)
            }
            flushOfflineQueue()
        }

        // endregion

        /**
         * Persists and mirrors the latest scroll snapshot for restore on re-entry.
         */
        fun persistScrollSnapshot(snapshot: ChatScrollSnapshot) {
            viewModelScope.launch {
                chatScrollStateStore.save(serverId, sessionId, snapshot)
            }
            updateState {
                if (restoredScrollSnapshot == snapshot) {
                    this
                } else {
                    copy(restoredScrollSnapshot = snapshot)
                }
            }
        }

        /** Debug-only trace logger. */
        private fun trace(message: String) {
            if (!BuildConfig.DEBUG) return
            runCatching { Log.d(TRACE_TAG, message) }
        }
    }

/** UI state for the chat screen. */
data class ChatState(
    val serverId: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingMessages: List<ChatMessage> = emptyList(),
    val markdownStates: Map<String, MarkdownRenderState> = emptyMap(),
    val restoredScrollSnapshot: ChatScrollSnapshot? = null,
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isSessionReady: Boolean = false,
    val canCancelStreaming: Boolean = true,
    val connectionState: ChatConnectionState = ChatConnectionState.Disconnected,
    val connectionDiagnostics: ChatConnectionDiagnostics = ChatConnectionDiagnostics(),
    val configOptions: List<ChatConfigOption> = emptyList(),
    val usage: UsageState? = null,
    val availableCommands: List<ChatCommand> = emptyList(),
    val commandsAdvertised: Boolean = false,
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val gatewayWorkspaceConnection: GatewayWorkspaceConnection? = null,
    val gitStatus: GatewayGitStatus? = null,
    val gitDiffStats: GitDiffStats? = null,
    val gitFileDiff: List<ToolCallContent.Diff>? = null,
    val gitFileDiffPath: String? = null,
    val isGitFileDiffLoading: Boolean = false,
    val gitFileDiffError: String? = null,
    val error: String? = null,
)

/** User intents emitted from the chat UI. */
sealed interface ChatIntent {
    data class SendMessage(
        val text: String,
        val images: List<ChatImageData> = emptyList(),
        val files: List<ChatFileData> = emptyList(),
    ) : ChatIntent

    data object CancelStreaming : ChatIntent

    data class SetConfigOption(
        val optionId: String,
        val value: ChatConfigValue,
    ) : ChatIntent

    data class GrantPermission(
        val toolCallId: String,
        val optionId: String,
    ) : ChatIntent

    data class DenyPermission(
        val toolCallId: String,
    ) : ChatIntent

    data object RetryLoad : ChatIntent

    /** Retry sending a previously failed (or queued) message identified by its [clientId]. */
    data class RetryMessage(
        val clientId: String,
    ) : ChatIntent

    /** Re-fetch the git status for the gateway-backed working tree (e.g. after a send). */
    data object RefreshGitStatus : ChatIntent

    /** Load the unified diff for a single changed file from the gateway-backed working tree. */
    data class LoadGitDiff(
        val path: String,
    ) : ChatIntent
}

/** One-shot effects emitted to the UI layer (snackbar, navigation, etc.). */
sealed interface ChatEffect {
    data class ShowError(
        val message: String,
    ) : ChatEffect

    data class ShowMessage(
        val message: String,
    ) : ChatEffect

    data object NavigateBack : ChatEffect
}
