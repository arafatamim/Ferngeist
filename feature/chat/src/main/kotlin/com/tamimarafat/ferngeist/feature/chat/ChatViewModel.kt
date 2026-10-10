package com.tamimarafat.ferngeist.feature.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.iconUrl
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Orchestrates chat UI state by binding the session facade, scroll state, and markdown parsing.
 *
 * The view model holds no ACP details; all transport-specific logic is delegated to the facade.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        sessionFacadeFactory: ChatSessionFacadeFactory,
        private val sessionRepository: SessionRepository,
        private val chatScrollStateStore: ChatScrollStateStore,
        private val pendingPromptStore: PendingPromptStore,
        private val switcherHintStore: SwitcherHintStore,
        val recentSelectionStore: RecentSelectionStore,
        private val chatConnectionHub: ChatConnectionHub,
        private val gatewayRepository: GatewayRepository,
        private val launchableTargetRepository: LaunchableTargetRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : MviViewModel<ChatState, ChatIntent, ChatEffect>(initialChatState()) {
        companion object {
            private const val TRACE_TAG = "TSChatVM"

            /**
             * Delay before each `session/list` title attempt, relative to the end of the
             * first response. The first is immediate; the rest give a slow agent time to
             * name the session — it often does so just after the turn settles.
             */
            private val TITLE_FETCH_RETRY_DELAYS_MS = listOf(0L, 3_000L, 8_000L)

            /**
             * [SavedStateHandle] key holding the session id a create-on-arrival chat
             * minted, so a rebuilt ViewModel reattaches to that session instead of
             * minting a second one.
             */
            private const val KEY_MINTED_SESSION_ID = "mintedSessionId"

            /**
             * Byte-identical to the emitters the session list uses for the same
             * refusals: one rule, one phrasing, and [localizeChatError] finds the
             * resource for these keys.
             */
            private const val CLOSE_STREAMING_MESSAGE =
                "This session is still responding. Cancel or close it from inside the chat first."
            private const val CLOSE_GATEWAY_ONLY_MESSAGE = "Close is only available for gateway sessions."
            private const val CLOSE_FAILED_MESSAGE = "Failed to close session."

            private fun initialChatState(): ChatState = ChatState()
        }

        private val serverId: String = savedStateHandle["serverId"] ?: error("serverId is required")
        private val sessionId: String = savedStateHandle["sessionId"] ?: error("sessionId is required")

        /**
         * Real session id minted by a create-on-arrival chat, restored after process
         * death. Null for ordinary chats, whose nav arg is already the real id.
         */
        private val mintedSessionId: String? = savedStateHandle[KEY_MINTED_SESSION_ID]
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
        private var trackedSessionId: String = mintedSessionId ?: sessionId

        /**
         * True when this screen was opened on an existing session rather than the
         * create-on-arrival sentinel. Only an existing session can be attached
         * with `session/load`/`session/resume`, so only then can the transcript
         * be missing history the agent never replays.
         */
        private val openedExistingSession: Boolean = sessionId != NEW_SESSION_ARG

        /**
         * Identity for this chat's own local records (the durable prompt queue and
         * the scroll snapshot), or null while the chat is still the
         * [NEW_SESSION_ARG] sentinel.
         *
         * A create-on-arrival chat has no identity to key on until the facade mints
         * one, and the sentinel is shared by every new chat of a server. A record
         * written under it outlives the chat that wrote it and the *next* new chat
         * restores it — replaying a prompt into a session it never belonged to.
         * A newly minted id can never have a record of its own, so "no identity,
         * nothing to restore" loses nothing.
         */
        private val durableSessionId: String?
            get() = trackedSessionId.takeIf { it != NEW_SESSION_ARG }

        /**
         * True once the agent pushed a title over `session_info_update`. A pushed title is
         * the agent naming the session, so it is canonical: nothing replaces it afterwards.
         */
        private var titlePushedByAgent = false

        /**
         * True once this chat has asked the agent's session list for a title. Reset when a
         * new turn starts, so a name the agent assigns at any point still reaches the bar.
         */
        private var titleFetchDone = false

        private val sessionFacade: ChatSessionFacade =
            sessionFacadeFactory.create(
                scope = viewModelScope,
                serverId = serverId,
                sessionId = trackedSessionId,
                cwd = cwd,
            )
        private val markdownStateStore = MarkdownStateStore.retainedFor(durableSessionId?.let { "$serverId/$it" })
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
                                    markdownDocuments = emptyMap(),
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
                                    supportsSteering = capabilities.supportsSteering,
                                    resumedSession =
                                        openedExistingSession && !capabilities.supportsHistoryReplay,
                                )
                            }
                        }
                    },
            )

        /** Queue of locally-created prompts that have not been delivered to the server yet. */
        private val offlineQueue = OfflineQueue()

        /** Restores [offlineQueue] from [pendingPromptStore] at most once per view model. */
        private var pendingPromptsRestored = false

        /** Serializes [persistQueue] writes so a slower, older snapshot cannot land last. */
        private val persistMutex = Mutex()

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

        /** Live sessions across all servers, current included, for the chat switcher. */
        val switcherUiState: StateFlow<SwitcherUiState> =
            switcherFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwitcherUiState())

        /**
         * False until the first-run swipe hint has been seen: gates the bubble's
         * auto-showing tooltip. Starts true so a device that already saw the
         * hint never flashes it while the store answers.
         */
        val switcherHintSeen: StateFlow<Boolean> =
            switcherHintStore.seen
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun switcherFlow(): Flow<SwitcherUiState> =
            chatConnectionHub.warmServers
                .flatMapLatest { serverIds ->
                    if (serverIds.isEmpty()) {
                        flowOf(SwitcherUiState())
                    } else {
                        combineSwitcherRows(serverIds.sorted())
                    }
                }

        private fun combineSwitcherRows(serverIds: List<String>): Flow<SwitcherUiState> =
            combine(
                serverIds.map { sid ->
                    combine(
                        sessionRepository.getSessions(sid),
                        chatConnectionHub.connectedSessionIds(sid),
                    ) { sessions, liveIds ->
                        Triple(sid, sessions, liveIds)
                    }
                },
            ) { triples ->
                val sessionsByServer = triples.associate { it.first to it.second }
                val liveByServer = triples.associate { it.first to it.third }
                val servers =
                    triples.map { it.first }.distinct().map { sid ->
                        SwitcherServer(
                            id = sid,
                            name = launchableTargetRepository.getTarget(sid)?.name ?: sid,
                        )
                    }
                val groups =
                    deriveSwitcherGroups(
                        servers = servers,
                        sessionsByServer = sessionsByServer,
                        liveIdsByServer = liveByServer,
                        isGenerating = { sid, sess -> chatConnectionHub.isStreaming(sid, sess) },
                        currentServerId = serverId,
                        currentSessionId = trackedSessionId,
                    )
                SwitcherUiState(
                    groups = groups,
                    liveTotalCount = groups.sumOf { it.sessions.size },
                    hasOthers = groups.any { group -> group.sessions.any { !it.isCurrent } },
                )
            }

        init {
            updateState { copy(serverId = serverId) }
            // Announce this chat's screen presence to the hub so the connection
            // notification deep-links back here instead of dropping the user on
            // the home screen. A create-on-arrival chat still holds the __new__
            // nav-arg sentinel here and is refused by the hub (it is a nav
            // placeholder, not a chat identity); its presence opens under the
            // real session id once the facade's liveChatId collector promotes it.
            // trackedSessionId, not the nav arg: after process death a create-on-arrival
            // chat restores its minted id here, and onCleared closes under that same id.
            if (trackedSessionId != NEW_SESSION_ARG) {
                chatConnectionHub.chatScreenOpened(serverId, trackedSessionId, cwd)
            }
            resolveSessionTitle()
            viewModelScope.launch {
                val snapshot = durableSessionId?.let { chatScrollStateStore.restore(serverId, it) }
                updateState { copy(restoredScrollSnapshot = snapshot) }
            }
            viewModelScope.launch { restorePendingPrompts() }
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
                            // Record the minted id where a rebuilt ViewModel reads it:
                            // the nav arg stays the sentinel for this route's lifetime,
                            // so without this a process death would re-enter the create
                            // path and mint a second session for one user intent.
                            savedStateHandle[KEY_MINTED_SESSION_ID] = trackedSessionId
                            chatConnectionHub.chatScreenOpened(serverId, trackedSessionId, cwd)
                            // The session list renders the store, and nothing else writes a
                            // row for a session minted here until the next listing; the
                            // gateway id below is an UPDATE that also needs the row to exist.
                            val minted =
                                SessionSummary(id = trackedSessionId, cwd = cwd, updatedAt = System.currentTimeMillis())
                            withContext(Dispatchers.IO) { sessionRepository.upsertSession(serverId, minted) }
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
            // Nothing to scope to until create-on-arrival mints a session; sessionReady retries.
            val acpSessionId = acpSessionId() ?: return
            viewModelScope.launch {
                runCatching {
                    val status =
                        gatewayRepository.fetchGitStatus(
                            scheme = connection.scheme,
                            host = connection.host,
                            gatewayCredential = connection.gatewayCredential,
                            runtimeId = connection.runtimeId,
                            acpSessionId = acpSessionId,
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

        /** The ACP session this chat shows, scoping workspace calls to its cwd; null before it is minted. */
        private fun acpSessionId(): String? = trackedSessionId.takeIf { it != NEW_SESSION_ARG }

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
                                    acpSessionId = acpSessionId(),
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
         * Populates [ChatState.title] for the app bar, and the agent logo URL for
         * identity marks. Custom and manual agents have no registry logo, so a null icon
         * simply keeps the fallback.
         *
         * The nav arg carries the title the session list had when this chat was opened
         * (blank on the deep-link path, which cannot carry one) and seeds the first frame;
         * from there the session store is the source of truth, because every path that
         * learns a title writes it — the agent's push, this chat's `session/list` read,
         * and the session list's own refresh. A name the agent reports late then reaches
         * an already-open chat instead of being frozen at whatever the row said when the
         * user tapped it.
         */
        private fun resolveSessionTitle() {
            adoptTitle(sessionTitle)
            viewModelScope.launch {
                sessionRepository.getSessions(serverId).collect { sessions ->
                    val currentId = durableSessionId ?: return@collect
                    adoptTitle(sessions.firstOrNull { it.id == currentId }?.title)
                }
            }
            viewModelScope.launch {
                val icon = launchableTargetRepository.getTarget(serverId)?.iconUrl
                if (icon != null) {
                    updateState { copy(iconUrl = icon) }
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
            val (reconciledPending, echoKeys) = reconcileSendingPendingBubbles(snapshot.messages)
            val failed = snapshot.loadState == ChatLoadState.FAILED
            val wasStreaming = state.value.isStreaming
            // A HYDRATING snapshot is an in-flight marker: it asserts that a load is
            // running, not that a reported failure is over. Letting it rewrite the error
            // to null and `isLoading` back to true replaces a visible load error with a
            // spinner that never resolves — the user sees "still loading" forever while
            // the message explaining why was dropped one emission ago.
            val loadErrorStands =
                !failed && snapshot.loadState == ChatLoadState.HYDRATING && state.value.error != null
            // The echo is the runtime's own record of the prompt, written just before the
            // turn starts on the bridge's scope, which outlives this screen. From here the
            // prompt is delivered, so its durable copy goes now rather than when the turn
            // ends: a screen closed mid-turn would otherwise restore and re-send it.
            if (echoKeys.isNotEmpty()) persistQueue()
            updateState {
                copy(
                    messages = snapshot.messages,
                    pendingMessages = reconciledPending,
                    listKeys = if (echoKeys.isEmpty()) listKeys else listKeys + echoKeys,
                    markdownDocuments = markdownProjection.documents,
                    isStreaming = snapshot.isStreaming,
                    usage = snapshot.usage,
                    availableCommands = snapshot.availableCommands,
                    commandsAdvertised = snapshot.commandsAdvertised,
                    configOptions = snapshot.configOptions,
                    pendingElicitations = snapshot.pendingElicitations,
                    isLoading =
                        !loadErrorStands &&
                            (
                                snapshot.loadState == ChatLoadState.HYDRATING ||
                                    markdownProjection.pendingInitialHydration
                            ),
                    isSessionReady =
                        snapshot.loadState == ChatLoadState.READY &&
                            !markdownProjection.pendingInitialHydration,
                    error =
                        when {
                            failed ->
                                snapshot.error
                                    ?: "Could not load this session. Check connection and retry."
                            loadErrorStands -> error
                            else -> null
                        },
                )
            }
            if (wasStreaming && !snapshot.isStreaming) touchSessionAfterTurn()
            dropTitleThatIsThePrompt()
            applyServerTitle(snapshot.title)
            fetchGeneratedTitleIfNeeded(snapshot)
        }

        /**
         * A finished turn is the session's latest activity, so the list re-sorts it to the top
         * without waiting for its next listing.
         */
        private suspend fun touchSessionAfterTurn() {
            val id = durableSessionId ?: return
            withContext(Dispatchers.IO) { sessionRepository.touchSession(serverId, id, System.currentTimeMillis()) }
        }

        /**
         * A title stored before this chat knew its first message can be the prompt
         * itself, since that is what an agent synthesises for a session it has not
         * named. Now that the transcript can tell, drop it and let a real name land.
         */
        private fun dropTitleThatIsThePrompt() {
            val storedTitle = state.value.title
            val storedTitleIsThePrompt =
                storedTitle != null && sessionTitleOrNull(storedTitle, firstMessage()) == null
            if (!titlePushedByAgent && storedTitleIsThePrompt) {
                updateState { copy(title = null) }
            }
        }

        /**
         * Reconciles SENDING pending bubbles with their reducer echo in [messages].
         * A SENDING bubble is removed when a matching USER message appears in the
         * snapshot (content + images + files match), because the echoed message
         * in [messages] is the canonical delivery.
         */
        private fun reconcileSendingPendingBubbles(
            messages: List<ChatMessage>,
        ): Pair<List<ChatMessage>, Map<String, String>> {
            val pending = state.value.pendingMessages
            val pendingSending = pending.filter { it.status == MessageDeliveryStatus.SENDING }
            if (pendingSending.isEmpty()) return pending to emptyMap()
            val echoes = findEchoes(pendingSending, messages)
            if (echoes.isEmpty()) return pending to emptyMap()
            val echoedClientIds = echoes.values.toSet()
            return pending.filterNot { (it.clientId ?: it.id) in echoedClientIds } to echoes
        }

        /** Echo message id to the client id of the pending bubble it delivers. */
        private fun findEchoes(
            pendingSending: List<ChatMessage>,
            messages: List<ChatMessage>,
        ): Map<String, String> =
            buildMap {
                for (sending in pendingSending) {
                    // The last match: an identical earlier prompt ("continue") is not this echo.
                    val echoed =
                        messages.lastOrNull { msg ->
                            msg.role == ChatMessage.Role.USER &&
                                msg.content == sending.content &&
                                msg.images == sending.images &&
                                msg.files == sending.files
                        }
                    if (echoed != null) put(echoed.id, sending.clientId ?: sending.id)
                }
            }

        /**
         * Adopts [candidate] as the app-bar title, from any path that learns one: the nav
         * arg, the session store, or this chat's `session/list` read. Refused when the
         * agent already pushed a title — that is the agent's own name for the session, and
         * nothing replaces it — and when [sessionTitleOrNull] says the candidate is not a
         * name at all. Returns whether it was adopted.
         */
        private fun adoptTitle(candidate: String?): Boolean {
            if (titlePushedByAgent) return false
            val name = sessionTitleOrNull(candidate, firstMessage()) ?: return false
            if (state.value.title != name) {
                updateState { copy(title = name) }
            }
            return true
        }

        /**
         * Applies the title the agent pushed over `session_info_update`. Pushing a name is
         * the agent telling the client what to call the session, so it wins over anything
         * the session list reported and closes the app bar to further changes. Uses a
         * targeted UPDATE (not upsert) to preserve updatedAt and all other columns.
         */
        private suspend fun applyServerTitle(serverTitle: String?) {
            if (serverTitle.isNullOrBlank()) return
            if (titlePushedByAgent && state.value.title == serverTitle) return
            titlePushedByAgent = true
            updateState { copy(title = serverTitle) }
            // A create-on-arrival chat's nav arg is still the sentinel; only the
            // minted id names a real row.
            val targetId = durableSessionId ?: return
            sessionRepository.updateSessionTitle(
                serverId = serverId,
                sessionId = targetId,
                title = serverTitle,
            )
        }

        /**
         * Asks the agent's session list for this session's title. An agent that names a
         * session server-side does not necessarily push it — pi-acp pushes a name only for
         * an explicit `/name` — so the list is the only way to learn it. Re-armed for every
         * turn, and once on open for a session with history, so a name an agent assigns
         * later still reaches the app bar.
         */
        private fun fetchGeneratedTitleIfNeeded(snapshot: ChatSessionSnapshot) {
            if (snapshot.isStreaming) {
                // A turn is running; whether the agent has named the session is only
                // answerable once it settles, so re-arm the read for that snapshot.
                titleFetchDone = false
                return
            }
            if (!shouldAskForGeneratedTitle(snapshot)) return
            titleFetchDone = true
            viewModelScope.launch {
                for (delayMs in TITLE_FETCH_RETRY_DELAYS_MS) {
                    delay(delayMs)
                    if (titlePushedByAgent || !state.value.title.isNullOrBlank()) return@launch
                    val title = sessionFacade.fetchSessionTitle()
                    if (title.isNullOrBlank() || !adoptTitle(title)) continue
                    // Record it so the session list and every other screen see the same name.
                    val targetId = durableSessionId ?: return@launch
                    sessionRepository.updateSessionTitle(serverId, targetId, title)
                    return@launch
                }
            }
        }

        /**
         * Whether this settled snapshot is a moment to ask the agent's session list for a
         * title: the chat is loaded with a response in it, still has no title, and has not
         * already asked since the last turn began.
         */
        private fun shouldAskForGeneratedTitle(snapshot: ChatSessionSnapshot): Boolean =
            !titleFetchDone &&
                !titlePushedByAgent &&
                snapshot.loadState == ChatLoadState.READY &&
                state.value.title.isNullOrBlank() &&
                snapshot.messages.any { it.role == ChatMessage.Role.ASSISTANT } &&
                durableSessionId != null

        /**
         * The first thing the user said, which is what an agent synthesises a session
         * title from when it has not named the session itself.
         */
        private fun firstMessage(): String? =
            state.value.messages
                .firstOrNull { it.role == ChatMessage.Role.USER }
                ?.content

        override fun onCleared() {
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
                is ChatIntent.RemoveQueuedMessage,
                is ChatIntent.ForceSendMessage,
                is ChatIntent.LoadGitDiff,
                is ChatIntent.MarkSwitcherHintSeen,
                is ChatIntent.CloseSession,
                is ChatIntent.SubmitElicitation,
                is ChatIntent.DeclineElicitation,
                is ChatIntent.CancelElicitation,
                -> handleAuxIntent(intent)
            }
        }

        /** One-shot intents outside the send/streaming core: diff fetch, hint flag, session close. */
        private suspend fun handleAuxIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.RemoveQueuedMessage -> removeQueuedMessage(intent.clientId)
                is ChatIntent.ForceSendMessage -> forceSend(intent.clientId)
                is ChatIntent.LoadGitDiff -> loadGitFileDiff(intent.path)
                is ChatIntent.MarkSwitcherHintSeen -> switcherHintStore.markSeen()
                is ChatIntent.CloseSession -> closeSwitcherSession(intent.serverId, intent.sessionId)
                is ChatIntent.SubmitElicitation -> sessionCoordinator.submitElicitation(intent.key, intent.values)
                is ChatIntent.DeclineElicitation -> sessionCoordinator.declineElicitation(intent.key)
                is ChatIntent.CancelElicitation -> sessionCoordinator.cancelElicitation(intent.key)
                else -> Unit
            }
        }

        /**
         * Releases a live session the switcher listed, through the same hub call the
         * session list's Disconnect makes so both surfaces mean one thing by
         * "close": the gateway process stops and the local transport is released.
         * Refused while the session streams — the gateway would cut a live turn —
         * and for manual agents, which have no gateway leg to release.
         */
        private suspend fun closeSwitcherSession(
            targetServerId: String,
            targetSessionId: String,
        ) {
            if (chatConnectionHub.isStreaming(targetServerId, targetSessionId)) {
                emitEffect(ChatEffect.ShowError(CLOSE_STREAMING_MESSAGE))
                return
            }
            val endpoint = gatewayEndpoint(targetServerId)
            if (endpoint == null) {
                emitEffect(ChatEffect.ShowError(CLOSE_GATEWAY_ONLY_MESSAGE))
                return
            }
            runCatching {
                chatConnectionHub.closeSession(targetServerId, targetSessionId, endpoint)
            }.onFailure { error ->
                emitEffect(ChatEffect.ShowError(error.message ?: CLOSE_FAILED_MESSAGE))
            }
        }

        /** Gateway REST endpoint for [targetServerId], or null when the target is not gateway-backed. */
        private suspend fun gatewayEndpoint(targetServerId: String): GatewayEndpoint? =
            (launchableTargetRepository.getTarget(targetServerId) as? LaunchableTarget.GatewayAgent)
                ?.let { target ->
                    GatewayEndpoint(
                        scheme = target.gatewaySource.scheme,
                        host = target.gatewaySource.host,
                        credential = target.gatewaySource.gatewayCredential,
                    )
                }

        // region: Offline queue

        /**
         * Restores the prompts a previous screen lifetime persisted (see [persistQueue]) into
         * [offlineQueue] and [ChatState.pendingMessages], then gives them a path forward: an
         * immediate flush when the session is already live, otherwise the reconnect the offline
         * enqueue path uses, so a restored prompt stays QUEUED until the session is ready
         * instead of being marked FAILED by a flush that cannot dispatch it yet.
         *
         * Guarded so a second call cannot duplicate the queue.
         */
        private suspend fun restorePendingPrompts() {
            if (pendingPromptsRestored) return
            pendingPromptsRestored = true
            val queueId = durableSessionId ?: return
            val records = pendingPromptStore.restore(serverId, queueId)
            if (records.isEmpty()) return
            records.forEach { record ->
                offlineQueue.enqueue(
                    PendingPrompt(
                        clientId = record.clientId,
                        text = record.text,
                        images = record.images,
                        files = record.files,
                    ),
                )
                val message =
                    ChatMessage(
                        id = record.clientId,
                        role = ChatMessage.Role.USER,
                        content = record.text,
                        images = record.images,
                        files = record.files,
                        status = MessageDeliveryStatus.QUEUED,
                        clientId = record.clientId,
                    )
                updateState { copy(pendingMessages = pendingMessages + message) }
            }
            val canSendNow =
                state.value.isSessionReady && state.value.connectionState == ChatConnectionState.Connected
            if (canSendNow) {
                flushOfflineQueue()
            } else {
                kickReconnect()
            }
        }

        /**
         * Persists [offlineQueue] for this chat so a screen teardown cannot silently drop a
         * queued prompt. The snapshot is taken at write time under [persistMutex], so an older
         * write can never land after a newer one.
         */
        private suspend fun persistQueue() {
            persistMutex.withLock {
                val queueId = durableSessionId ?: return
                val records =
                    offlineQueue.snapshot().map {
                        QueuedPromptRecord(it.clientId, it.text, it.images, it.files)
                    }
                pendingPromptStore.save(serverId, queueId, records)
            }
        }

        /**
         * Optimistically adds a user bubble with [MessageDeliveryStatus.QUEUED] and
         * stores the prompt in [offlineQueue] for later delivery.
         *
         * Suspends only for the queue persist; the sole caller is the suspend intent
         * dispatch path, so no signature ripples.
         */
        private suspend fun enqueuePrompt(
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
            persistQueue()
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
            persistQueue()
            updateState {
                copy(pendingMessages = pendingMessages + message)
            }
            flushOfflineQueue()
        }

        /**
         * Drains [offlineQueue] in FIFO order under [flushMutex], one prompt per turn.
         *
         * Each send first waits for the agent to be idle. The mutex alone only covered
         * turns this view model started: a screen re-entered mid-turn (or a restored queue)
         * sent its prompt straight into the running turn. An agent that takes prompts
         * mid-turn is the exception: it gets each one at once (see [steerIntoTurn]).
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
                    val midTurn = state.first { !it.isStreaming || it.supportsSteering }.isStreaming
                    val prompt = offlineQueue.dequeue() ?: break
                    if (midTurn) steerIntoTurn(prompt) else startTurn(prompt)
                }
            }
        }

        /**
         * Sends [prompt]. The send lasts the whole turn, so for a steering agent the flush is held
         * only until the turn is under way: its next prompt must go into that turn, not behind it.
         */
        private suspend fun startTurn(prompt: PendingPrompt) {
            if (!state.value.supportsSteering) return sendWhenIdle(prompt)
            state.awaitTurnStartedOr(viewModelScope.launch { sendWhenIdle(prompt) })
        }

        private suspend fun sendWhenIdle(prompt: PendingPrompt) {
            // Transition QUEUED -> SENDING, keep the bubble visible.
            inFlightClientId = prompt.clientId
            setPendingStatus(prompt.clientId, MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.SENDING)
            val dispatched = sessionCoordinator.sendMessage(prompt.text, prompt.images, prompt.files)
            inFlightClientId = null
            if (!dispatched) {
                // No bridge / not ready / unsupported -> mark FAILED immediately.
                setPendingStatus(prompt.clientId, MessageDeliveryStatus.SENDING, MessageDeliveryStatus.FAILED)
                emitEffect(ChatEffect.ShowError("Failed to send message"))
            }
            // Only now is the durable copy dropped, and deliberately not from a
            // finally: until the send resolves the stored snapshot still contains
            // this prompt, so a screen teardown mid-send (which cancels
            // sendMessage and skips this line) restores it as QUEUED rather than
            // losing it. The cost is that a process death between a successful
            // send and this write can re-send one prompt after restart
            // (at-least-once); the record carries its clientId, so an echo-based
            // dedupe could tighten that later if it ever matters.
            persistQueue()
        }

        /**
         * Hands a prompt to the running turn; the agent feeds it to the model at its next
         * step. SENDING lets the runtime's local copy reconcile the bubble away as a normal
         * send's echo does. A prompt the agent does not take goes back to the front and
         * waits for the turn to end, so a refusal cannot spin the flush loop.
         */
        private suspend fun steerIntoTurn(prompt: PendingPrompt) {
            setPendingStatus(prompt.clientId, MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.SENDING)
            val outcome = sessionCoordinator.steerMessage(prompt.text, prompt.images, prompt.files)
            if (outcome == SteerOutcome.NotConsumed) {
                offlineQueue.enqueueFirst(prompt)
                setPendingStatus(prompt.clientId, MessageDeliveryStatus.SENDING, MessageDeliveryStatus.QUEUED)
                persistQueue()
                state.first { !it.isStreaming }
            } else {
                persistQueue()
            }
        }

        /**
         * Stops the running turn and makes [clientId]'s prompt the next one, folded together with
         * every prompt queued ahead of it so they all go at once. Prompts behind it keep waiting.
         * It keeps [clientId]'s bubble, whose content becomes the folded prompt the echo will carry.
         */
        private suspend fun forceSend(clientId: String) {
            val (merged, folded) = offlineQueue.foldThrough(clientId) ?: return
            updateState { copy(pendingMessages = pendingMessages.foldedInto(merged, folded)) }
            persistQueue()
            if (state.value.isStreaming) sessionCoordinator.cancelStreaming()
            // Normally a flush is already waiting on the turn; this covers one that is not.
            flushOfflineQueue()
        }

        /** Drops a prompt the user no longer wants sent. One already in flight cannot be recalled. */
        private suspend fun removeQueuedMessage(clientId: String) {
            val pending = state.value.pendingMessages.firstOrNull { it.clientId == clientId } ?: return
            if (pending.status == MessageDeliveryStatus.SENDING) return
            offlineQueue.removeByClientId(clientId)
            updateState { copy(pendingMessages = pendingMessages.filterNot { it.clientId == clientId }) }
            persistQueue()
        }

        /** Moves [clientId]'s pending bubble from [from] to [to]; any other status stays. */
        private fun setPendingStatus(
            clientId: String,
            from: MessageDeliveryStatus,
            to: MessageDeliveryStatus,
        ) = updateState { copy(pendingMessages = pendingMessages.withStatus(clientId, from, to)) }

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
            persistQueue()
            // Reset the bubble's status from FAILED to QUEUED so flushOfflineQueue
            // can transition it QUEUED -> SENDING and the echo-reconcile in
            // applySnapshot can remove it on delivery confirmation.
            setPendingStatus(clientId, MessageDeliveryStatus.FAILED, MessageDeliveryStatus.QUEUED)
            flushOfflineQueue()
        }

        // endregion

        /**
         * Persists and mirrors the latest scroll snapshot for restore on re-entry.
         */
        fun persistScrollSnapshot(snapshot: ChatScrollSnapshot) {
            viewModelScope.launch {
                durableSessionId?.let { chatScrollStateStore.save(serverId, it, snapshot) }
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

/** Suspends until a turn is streaming or [send] has finished, whichever comes first. */
private suspend fun StateFlow<ChatState>.awaitTurnStartedOr(send: Job) {
    val sendDone =
        flow {
            emit(false)
            send.join()
            emit(true)
        }
    combine(this, sendDone) { current, done -> current.isStreaming || done }.first { it }
}

private fun List<ChatMessage>.withStatus(
    clientId: String,
    from: MessageDeliveryStatus,
    to: MessageDeliveryStatus,
): List<ChatMessage> = map { if (it.clientId == clientId && it.status == from) it.copy(status = to) else it }

/** Gives [merged]'s bubble the folded content and drops the bubbles of the prompts it [folded] in. */
private fun List<ChatMessage>.foldedInto(
    merged: PendingPrompt,
    folded: Set<String>,
): List<ChatMessage> =
    mapNotNull {
        when (it.clientId) {
            merged.clientId -> it.copy(content = merged.text, images = merged.images, files = merged.files)
            in folded -> null
            else -> it
        }
    }

/** UI state for the chat screen. */
data class ChatState(
    val serverId: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingMessages: List<ChatMessage> = emptyList(),
    /**
     * Transcript list key per message id, where it differs from the id: the reducer's echo of a
     * sent prompt has a new id, and keeps the pending bubble's key so the list moves the same
     * item instead of swapping one for another.
     */
    val listKeys: Map<String, String> = emptyMap(),
    val markdownDocuments: Map<String, MarkdownRenderedDocument> = emptyMap(),
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
    val pendingElicitations: List<ChatElicitationRequest> = emptyList(),
    val canSendImages: Boolean = false,
    val supportsEmbeddedContext: Boolean = false,
    val supportsSteering: Boolean = false,
    /**
     * True when this screen attached to an existing session whose agent cannot
     * replay history, so earlier turns exist only on the agent's side and the
     * transcript starts empty. Drives the top-of-transcript resumed-session notice.
     */
    val resumedSession: Boolean = false,
    val gatewayWorkspaceConnection: GatewayWorkspaceConnection? = null,
    val gitStatus: GatewayGitStatus? = null,
    val gitDiffStats: GitDiffStats? = null,
    val gitFileDiff: List<ToolCallContent.Diff>? = null,
    val gitFileDiffPath: String? = null,
    val isGitFileDiffLoading: Boolean = false,
    val gitFileDiffError: String? = null,
    val error: String? = null,
    /**
     * Registry logo URL for this chat's agent, for identity marks like the
     * empty-session hero. Null for manual agents or when unresolved.
     */
    val iconUrl: String? = null,
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

    data class SubmitElicitation(
        val key: String,
        val values: Map<String, ChatElicitationValue>,
    ) : ChatIntent

    data class DeclineElicitation(
        val key: String,
    ) : ChatIntent

    data class CancelElicitation(
        val key: String,
    ) : ChatIntent

    data object RetryLoad : ChatIntent

    /** Retry sending a previously failed (or queued) message identified by its [clientId]. */
    data class RetryMessage(
        val clientId: String,
    ) : ChatIntent

    /** Stop the turn and send this QUEUED prompt, with those queued ahead of it, right away. */
    data class ForceSendMessage(
        val clientId: String,
    ) : ChatIntent

    /** Drop a QUEUED or FAILED prompt without sending it. */
    data class RemoveQueuedMessage(
        val clientId: String,
    ) : ChatIntent

    /** Re-fetch the git status for the gateway-backed working tree (e.g. after a send). */
    data object RefreshGitStatus : ChatIntent

    /** Load the unified diff for a single changed file from the gateway-backed working tree. */
    data class LoadGitDiff(
        val path: String,
    ) : ChatIntent

    /** Consumes the switcher bubble's first-run swipe hint so it never shows again. */
    data object MarkSwitcherHintSeen : ChatIntent

    /**
     * Releases another live session the switcher listed, the way the session
     * list's Disconnect does. Carries the row's own ids: the sheet lists every
     * warm server, so the target is not always this chat's server.
     */
    data class CloseSession(
        val serverId: String,
        val sessionId: String,
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
