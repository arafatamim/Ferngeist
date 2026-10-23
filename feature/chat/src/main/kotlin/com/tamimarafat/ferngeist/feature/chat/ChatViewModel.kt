package com.tamimarafat.ferngeist.feature.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.common.MviViewModel
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionPresence
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.core.model.iconUrl
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        private val presence: ChatSessionPresence,
        private val launchableTargetRepository: LaunchableTargetRepository,
        private val turnTimingRepository: TurnTimingRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

        /** Recorded turn times for [storedTurnsSession], loaded once a reply needs them. */
        private var storedTurns: MutableMap<TurnKey, TurnTiming>? = null
        private var storedTurnsSession: String? = null

        private val sessionFacade: ChatSessionFacade =
            sessionFacadeFactory.create(
                scope = viewModelScope,
                serverId = serverId,
                sessionId = trackedSessionId,
                cwd = cwd,
            )
        private val markdownStateStore = MarkdownStateStore.retainedFor(durableSessionId?.let { "$serverId/$it" })
        private val sessionCoordinator: ChatSessionCoordinator =
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
                            offlineQueue.failInFlightPrompt()
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

        /** Durable prompt queue and the pending-message bubbles it mirrors into state. */
        private val offlineQueue: ChatOfflineQueueController =
            ChatOfflineQueueController(
                scope = viewModelScope,
                coordinator = sessionCoordinator,
                store = pendingPromptStore,
                serverId = serverId,
                durableSessionId = { durableSessionId },
                state = state,
                update = { reducer -> updateState(reducer) },
                emitError = { message -> emitEffect(ChatEffect.ShowError(message)) },
            )

        /** Gateway git status and per-file diff loading. */
        private val gitDiffLoader =
            ChatGitDiffLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState(reducer) },
                acpSessionId = { trackedSessionId.takeIf { it != NEW_SESSION_ARG } },
                compareRef = { state.value.compareRef },
            )

        /** The file sheet's file, read from the gateway workspace. */
        private val fileLoader =
            ChatFileLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState(reducer) },
                cwd = cwd,
                tree = {
                    state.value.gitStatus
                        ?.changed
                        .orEmpty()
                },
            )

        /** Commit history and a commit's files for the git sheet's History tab. */
        private val gitHistoryLoader =
            ChatGitHistoryLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                history = { state.value.gitHistory },
                update = { reducer -> updateState { copy(gitHistory = gitHistory.reducer()) } },
            )

        /** The reader picked a compare target by hand, so [preselectDefaultCompare] stands down. */
        private var compareChosenByReader = false

        /** Chat switcher rows and the close-session action behind them. */
        private val switcherController =
            ChatSwitcherController(
                scope = viewModelScope,
                presence = presence,
                sessionRepository = sessionRepository,
                launchableTargetRepository = launchableTargetRepository,
                serverId = serverId,
                currentSessionId = { trackedSessionId },
                emitError = { message -> emitEffect(ChatEffect.ShowError(message)) },
            )

        /** Live sessions across all servers, current included, for the chat switcher. */
        val switcherUiState: StateFlow<SwitcherUiState> = switcherController.state

        /**
         * False until the first-run swipe hint has been seen: gates the bubble's
         * auto-showing tooltip. Starts true so a device that already saw the
         * hint never flashes it while the store answers.
         */
        val switcherHintSeen: StateFlow<Boolean> =
            switcherHintStore.seen
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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
                presence.screenOpened(serverId, trackedSessionId, cwd)
            }
            resolveSessionTitle()
            viewModelScope.launch {
                val snapshot = durableSessionId?.let { chatScrollStateStore.restore(serverId, it) }
                updateState { copy(restoredScrollSnapshot = snapshot) }
            }
            viewModelScope.launch { offlineQueue.restorePendingPrompts() }
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
                            presence.screenOpened(serverId, trackedSessionId, cwd)
                            // The session list renders the store, and nothing else writes a
                            // row for a session minted here until the next listing; the
                            // gateway id below is an UPDATE that also needs the row to exist.
                            val minted =
                                SessionSummary(id = trackedSessionId, cwd = cwd, updatedAt = System.currentTimeMillis())
                            withContext(ioDispatcher) { sessionRepository.upsertSession(serverId, minted) }
                        }
                        // chatId is "$serverId/$sessionId" and carries the REAL
                        // session id even for create-on-arrival chats, where the
                        // nav arg is still the __new__ sentinel. Record which
                        // gateway session owns it so cold closes and reattaches
                        // can find it after process death.
                        val gatewaySessionId = presence.gatewaySessionIdFor(chatId)
                        if (gatewaySessionId != null) {
                            val realSessionId = chatId.substringAfter('/')
                            withContext(ioDispatcher) {
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
                    offlineQueue.flushOfflineQueue()
                    // Retry the git-status fetch once the session is fully initialized,
                    // in case an earlier attempt raced session setup and failed.
                    state.value.gatewayWorkspaceConnection?.let { gitDiffLoader.refresh() }
                    // Known before the sheet opens, so its first frame already compares against the default.
                    gitDiffLoader.loadRefs()
                }
            }
            viewModelScope.launch {
                state
                    .map { it.gitStatus?.branch to it.gitRefs }
                    .distinctUntilChanged()
                    .collect { preselectDefaultCompare() }
            }
            viewModelScope.launch {
                sessionFacade.diagnostics.collect { diagnostics ->
                    updateState { copy(connectionDiagnostics = diagnostics) }
                }
            }
            viewModelScope.launch {
                sessionFacade.gatewayWorkspaceConnection.collect { connection ->
                    updateState { copy(gatewayWorkspaceConnection = connection) }
                    if (connection != null) gitDiffLoader.refresh()
                }
            }
            viewModelScope.launch {
                // Files only come and go when git status's path set changes, so list again then.
                // A gateway without the list leaves it null, and chips fall back to the turn's files.
                state
                    .map { it.gitStatus }
                    .distinctUntilChanged()
                    .mapNotNull { status -> status?.changed?.mapTo(HashSet()) { it.path } }
                    .distinctUntilChanged()
                    .collectLatest {
                        val files = runCatching { sessionFacade.fetchFileList() }.getOrNull() ?: return@collectLatest
                        val byName = withContext(ioDispatcher) { files.groupBy { it.substringAfterLast('/') } }
                        updateState { copy(workspaceFiles = byName) }
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
         * A replay carries no times, so turns are recorded as they finish here and put back on
         * the replayed replies that match them.
         */
        private suspend fun withTurnTimings(messages: List<ChatMessage>): List<ChatMessage> {
            val sessionId = durableSessionId ?: return messages
            val keys = messages.turnKeys()
            if (keys.isEmpty()) return messages
            val stored =
                storedTurns?.takeIf { storedTurnsSession == sessionId }
                    ?: withContext(ioDispatcher) { turnTimingRepository.getTimings(sessionId) }
                        .toMutableMap()
                        .also {
                            storedTurns = it
                            storedTurnsSession = sessionId
                        }
            val fresh = messages.unstoredTurns(keys, stored)
            if (fresh.isNotEmpty()) {
                stored.putAll(fresh)
                viewModelScope.launch(ioDispatcher) {
                    fresh.forEach { (key, timing) -> turnTimingRepository.record(sessionId, key, timing) }
                }
            }
            return messages.withStoredTurns(keys, stored)
        }

        /**
         * A session/load announces no sessionReady, and the status asked for when the workspace
         * appeared ran before the gateway knew this session's directory, so ask again once ready.
         */
        private fun refreshGitStatusOnceReady(wasReady: Boolean) {
            if (wasReady || !state.value.isSessionReady) return
            state.value.gatewayWorkspaceConnection?.let { gitDiffLoader.refresh() }
            gitDiffLoader.loadRefs()
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
            val wasReady = state.value.isSessionReady
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
            if (echoKeys.isNotEmpty()) offlineQueue.persistQueue()
            val messages = withTurnTimings(snapshot.messages)
            updateState {
                copy(
                    messages = messages,
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
            onTurnBoundary(wasStreaming, snapshot.isStreaming)
            refreshGitStatusOnceReady(wasReady)
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
            withContext(ioDispatcher) { sessionRepository.touchSession(serverId, id, System.currentTimeMillis()) }
        }

        /**
         * A starting turn retires the previous turn's inline git diffs: they
         * describe the tree as it is now, so the next turn must not borrow them.
         */
        private fun onTurnBoundary(
            wasStreaming: Boolean,
            isStreaming: Boolean,
        ) {
            if (!wasStreaming && isStreaming) gitDiffLoader.clearTurnDiffs()
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
            presence.screenClosed(serverId, trackedSessionId)
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
                        offlineQueue.enqueueThenSend(intent.text, intent.images, intent.files)
                    } else {
                        offlineQueue.enqueuePrompt(intent.text, intent.images, intent.files)
                    }
                    state.value.gatewayWorkspaceConnection?.let { gitDiffLoader.refresh() }
                }
                is ChatIntent.CancelStreaming -> sessionCoordinator.cancelStreaming()
                is ChatIntent.SetConfigOption -> sessionCoordinator.setConfigOption(intent.optionId, intent.value)
                is ChatIntent.GrantPermission -> sessionCoordinator.grantPermission(intent.toolCallId, intent.optionId)
                is ChatIntent.DenyPermission -> sessionCoordinator.denyPermission(intent.toolCallId)
                is ChatIntent.RetryLoad -> sessionCoordinator.loadSession()
                is ChatIntent.RetryMessage -> offlineQueue.retryMessage(intent.clientId)
                is ChatIntent.RefreshGitStatus -> refreshGit()
                is ChatIntent.RemoveQueuedMessage,
                is ChatIntent.ForceSendMessage,
                is ChatIntent.LoadGitDiff,
                is ChatIntent.LoadTurnFileDiff,
                is ChatIntent.OpenFile,
                is ChatIntent.CloseFile,
                is ChatIntent.LoadGitLog,
                is ChatIntent.LoadGitCommit,
                is ChatIntent.LoadGitRefs,
                is ChatIntent.SelectCompareRef,
                is ChatIntent.MarkSwitcherHintSeen,
                is ChatIntent.CloseSession,
                is ChatIntent.SubmitElicitation,
                is ChatIntent.DeclineElicitation,
                is ChatIntent.CancelElicitation,
                -> handleAuxIntent(intent)
            }
        }

        private fun refreshGit() {
            state.value.gatewayWorkspaceConnection?.let { gitDiffLoader.refresh() }
            // An agent turn changes the working tree, so a compare list read earlier is stale too.
            if (state.value.compareRef != null) gitDiffLoader.refreshCompare()
            // A turn may have committed, so history once opened follows along.
            if (state.value.gitHistory.isLoaded) gitHistoryLoader.loadLog(more = false)
        }

        /** The git sheet's history, commit and compare intents. */
        private fun handleGitSheetIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.LoadGitLog -> gitHistoryLoader.loadLog(intent.more)
                is ChatIntent.LoadGitCommit -> gitHistoryLoader.loadCommit(intent.sha)
                is ChatIntent.LoadGitRefs -> gitDiffLoader.loadRefs()
                is ChatIntent.SelectCompareRef -> {
                    compareChosenByReader = true
                    selectCompareRef(intent.ref)
                }
                else -> Unit
            }
        }

        /**
         * Compares against the repo's default branch when the checkout is on another local branch,
         * until the reader picks any target by hand, `Working tree` included. Not persisted: a fresh
         * process opens against the default again.
         */
        private fun preselectDefaultCompare() {
            val current = state.value
            val refs = current.gitRefs
            val branch = current.gitStatus?.branch.orEmpty()
            if (compareChosenByReader || current.compareRef != null || refs.unsupported) return
            if (refs.default.isBlank() || branch == refs.default) return
            // Status names a detached HEAD "(detached)", which matches no local branch.
            if (refs.refs.none { it.kind == ChatGitRef.Kind.BRANCH && it.name == branch }) return
            // A branch beats a same-named remote or tag, whatever order the gateway listed them in.
            refs.refs
                .filter { it.name == refs.default && it.qualified != null && !it.isHead }
                .minByOrNull { it.kind.ordinal }
                ?.let(::selectCompareRef)
        }

        private fun selectCompareRef(ref: ChatGitRef?) {
            if (ref == state.value.compareRef) return
            // The previous target's rows are no stand-in for the new one's.
            updateState {
                copy(compareRef = ref, compareStatus = null, isCompareStatusLoading = false, compareStatusError = null)
            }
            gitDiffLoader.refreshCompare()
        }

        /** One-shot intents outside the send/streaming core: diff fetch, hint flag, session close. */
        private suspend fun handleAuxIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.RemoveQueuedMessage -> offlineQueue.removeQueuedMessage(intent.clientId)
                is ChatIntent.ForceSendMessage -> offlineQueue.forceSend(intent.clientId)
                is ChatIntent.LoadGitDiff -> gitDiffLoader.load(intent.path, intent.commit, intent.oldPath)
                is ChatIntent.LoadTurnFileDiff ->
                    gitDiffLoader.loadTurnFile(
                        intent.path,
                        intent.oldPath,
                        intent.revision,
                    )
                is ChatIntent.OpenFile ->
                    fileLoader.open(intent.path, intent.commit, intent.compare, intent.oldPath)
                is ChatIntent.CloseFile -> fileLoader.close()
                is ChatIntent.LoadGitLog,
                is ChatIntent.LoadGitCommit,
                is ChatIntent.LoadGitRefs,
                is ChatIntent.SelectCompareRef,
                -> handleGitSheetIntent(intent)
                is ChatIntent.MarkSwitcherHintSeen -> switcherHintStore.markSeen()
                is ChatIntent.CloseSession -> switcherController.close(intent.serverId, intent.sessionId)
                is ChatIntent.SubmitElicitation -> sessionCoordinator.submitElicitation(intent.key, intent.values)
                is ChatIntent.DeclineElicitation -> sessionCoordinator.declineElicitation(intent.key)
                is ChatIntent.CancelElicitation -> sessionCoordinator.cancelElicitation(intent.key)
                else -> Unit
            }
        }

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
