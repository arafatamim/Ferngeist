package com.tamimarafat.ferngeist.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.core.common.IoDispatcher
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
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.iconUrl
import com.tamimarafat.ferngeist.core.model.qualified
import com.tamimarafat.ferngeist.feature.chat.activity.ActivityConfirmation
import com.tamimarafat.ferngeist.feature.chat.activity.PhoneTurnClock
import com.tamimarafat.ferngeist.feature.chat.deps.ChatLocalStores
import com.tamimarafat.ferngeist.feature.chat.deps.ChatRepositories
import com.tamimarafat.ferngeist.feature.chat.git.ChatGitFileDiffLoader
import com.tamimarafat.ferngeist.feature.chat.git.ChatGitHistoryLoader
import com.tamimarafat.ferngeist.feature.chat.git.ChatGitStatusLoader
import com.tamimarafat.ferngeist.feature.chat.git.ChatGitTurnDiffLoader
import com.tamimarafat.ferngeist.feature.chat.markdown.image.TranscriptImageLoader
import com.tamimarafat.ferngeist.feature.chat.pending.reconcileEchoes
import com.tamimarafat.ferngeist.feature.chat.timing.ChatTurnTimings
import com.tamimarafat.ferngeist.feature.chat.title.ChatTitleController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        sessionFacadeFactory: ChatSessionFacadeFactory,
        private val presence: ChatSessionPresence,
        private val repositories: ChatRepositories,
        private val stores: ChatLocalStores,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val savedStateHandle: SavedStateHandle,
    ) : MviViewModel<ChatState, ChatIntent, ChatEffect>(initialChatState()) {
        companion object {
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
        private val activityConfirmation =
            ActivityConfirmation(savedStateHandle.get<Long>("updatedAt").takeIf { openedExistingSession })

        /** Called only for an explicit send; merely opening the chat never consumes the decision. */
        fun needsActivityConfirmation(): Boolean =
            activityConfirmation.needsConfirmation(
                lastPhoneTurn = PhoneTurnClock.lastTurn(serverId, trackedSessionId),
                now = System.currentTimeMillis(),
            )

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

        /** Recorded turn times, read and written on behalf of replies that need them. */
        private val turnTimings =
            ChatTurnTimings(serverId, viewModelScope, repositories.turnTimingRepository, ioDispatcher)

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
                persistence =
                    ChatPromptPersistence(
                        store = stores.pendingPrompts,
                        serverId = serverId,
                        durableSessionId = { durableSessionId },
                    ),
                state = state,
                update = { reducer -> updateState(reducer) },
            )

        /** The app-bar title, from the nav arg, the session store, and the agent. */
        private val titleController =
            ChatTitleController(
                scope = viewModelScope,
                state = state,
                update = { reducer -> updateState(reducer) },
                durableSessionId = { durableSessionId },
                fetchTitle = { sessionFacade.fetchSessionTitle() },
            )

        /** The latest turn's inline fallback diffs, re-read whenever the status refreshes. */
        private val gitTurnDiffLoader =
            ChatGitTurnDiffLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState { copy(git = git.reducer()) } },
                git = { state.value.git },
            )

        /** Gateway git status, the compare target and the compare picker's refs. */
        private val gitStatusLoader =
            ChatGitStatusLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState { copy(git = git.reducer()) } },
                acpSessionId = { trackedSessionId.takeIf { it != NEW_SESSION_ARG } },
                git = { state.value.git },
                onStatusRefreshed = gitTurnDiffLoader::rereadLoaded,
            )

        /** The git sheet's single-file diff. */
        private val gitFileDiffLoader =
            ChatGitFileDiffLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState { copy(git = git.reducer()) } },
                git = { state.value.git },
            )

        /** The file sheet's file, read from the gateway workspace. */
        private val fileLoader =
            ChatFileLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                update = { reducer -> updateState(reducer) },
                cwd = cwd,
                tree = {
                    state.value.git.status
                        ?.changed
                        .orEmpty()
                },
            )

        /** The transcript's Markdown images, read through this chat's session. */
        internal val transcriptImages = TranscriptImageLoader(workspace = sessionFacade, cwd = cwd)

        /** Commit history and a commit's files for the git sheet's History tab. */
        private val gitHistoryLoader =
            ChatGitHistoryLoader(
                scope = viewModelScope,
                facade = sessionFacade,
                history = { state.value.git.history },
                update = { reducer -> updateState { copy(git = git.copy(history = git.history.reducer())) } },
            )

        /** The reader picked a compare target by hand, so [preselectDefaultCompare] stands down. */
        private var compareChosenByReader = false

        /** Chat switcher rows and the close-session action behind them. */
        private val switcherController =
            ChatSwitcherController(
                scope = viewModelScope,
                presence = presence,
                chatListings = repositories.chatListings,
                launchableTargetRepository = repositories.launchableTargetRepository,
                serverId = serverId,
                currentSessionId = { trackedSessionId },
            )

        /** Live sessions across all servers, current included, for the chat switcher. */
        val switcherUiState: StateFlow<SwitcherUiState> = switcherController.state

        /**
         * False until the first-run swipe hint has been seen: gates the bubble's
         * auto-showing tooltip. Starts true so a device that already saw the
         * hint never flashes it while the store answers.
         */
        val switcherHintSeen: StateFlow<Boolean> =
            stores.switcherHints.seen
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

        /** The recent-value memory the pickers read and write. */
        val recentSelectionStore: RecentSelectionStore = stores.recentSelections

        init {
            updateState { copy(serverId = serverId, isNewChat = !openedExistingSession) }
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
                val snapshot = durableSessionId?.let { stores.scrollState.restore(serverId, it) }
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
                // A rejected resume also moves the chat onto a freshly minted session;
                // presence follows it so the notification tap target names the live id.
                sessionFacade.liveChatId.collect { chatId ->
                    val liveSessionId = chatId?.substringAfter('/')
                    if (liveSessionId != null) {
                        if (liveSessionId != trackedSessionId) {
                            if (trackedSessionId != NEW_SESSION_ARG) presence.screenClosed(serverId, trackedSessionId)
                            trackedSessionId = liveSessionId
                            // Record the minted id where a rebuilt ViewModel reads it:
                            // the nav arg stays the sentinel for this route's lifetime,
                            // so without this a process death would re-enter the create
                            // path and mint a second session for one user intent.
                            savedStateHandle[KEY_MINTED_SESSION_ID] = trackedSessionId
                            presence.screenOpened(serverId, trackedSessionId, cwd)
                            // The session list renders the store, and nothing else writes a
                            // row for a session minted here until the next listing.
                            val minted =
                                SessionSummary(id = trackedSessionId, cwd = cwd, updatedAt = System.currentTimeMillis())
                            presence.chatCreated(serverId, minted)
                        }
                        // Nothing records the gateway session that owns this chat: cold
                        // closes and reattaches find it through `acpSessionIds` on
                        // /v1/sessions, even after process death.
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
                    state.value.gatewayWorkspaceConnection?.let { gitStatusLoader.refresh() }
                    // Known before the sheet opens, so its first frame already compares against the default.
                    gitStatusLoader.loadRefs()
                }
            }
            viewModelScope.launch {
                state
                    .map { it.git.status?.branch to it.git.refs }
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
                    if (connection != null) gitStatusLoader.refresh()
                }
            }
            viewModelScope.launch {
                // Files only come and go when git status's path set changes, so list again then.
                // A gateway without the list leaves it null, and chips fall back to the turn's files.
                state
                    .map { it.git.status }
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
         * Seeds the app-bar title from the nav arg, then follows the session store and the
         * chat's icon. The title itself is decided by [titleController].
         */
        private fun resolveSessionTitle() {
            titleController.seed(sessionTitle)
            viewModelScope.launch {
                repositories.sessionRepository.getSessions(serverId).collect { sessions ->
                    titleController.onSessionsListed(sessions)
                }
            }
            viewModelScope.launch {
                val icon = repositories.launchableTargetRepository.getTarget(serverId)?.iconUrl
                if (icon != null) {
                    updateState { copy(iconUrl = icon) }
                }
            }
        }

        /**
         * A session/load announces no sessionReady, and the status asked for when the workspace
         * appeared ran before the gateway knew this session's directory, so ask again once ready.
         */
        private fun refreshGitStatusOnceReady(wasReady: Boolean) {
            if (wasReady || !state.value.isSessionReady) return
            state.value.gatewayWorkspaceConnection?.let { gitStatusLoader.refresh() }
            gitStatusLoader.loadRefs()
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
            val (reconciledPending, echoKeys) = reconcileEchoes(state.value.pendingMessages, snapshot.messages)
            val wasStreaming = state.value.isStreaming
            val wasReady = state.value.isSessionReady
            // The echo is the runtime's own record of the prompt, written just before the
            // turn starts on the bridge's scope, which outlives this screen. From here the
            // prompt is delivered, so its durable copy goes now rather than when the turn
            // ends: a screen closed mid-turn would otherwise restore and re-send it.
            // Only a phone-origin echo advances this clock; completion can arrive after the screen closes.
            if (echoKeys.isNotEmpty()) {
                durableSessionId?.let { PhoneTurnClock.record(serverId, it, System.currentTimeMillis(), echoKeys.keys) }
                offlineQueue.persistQueue()
            }
            durableSessionId?.let { PhoneTurnClock.observe(serverId, it, snapshot.messages) }
            val messages =
                durableSessionId?.let { turnTimings.withTurnTimings(it, snapshot.messages) } ?: snapshot.messages
            updateState {
                withSnapshot(snapshot, messages, reconciledPending, echoKeys, markdownProjection)
            }
            onTurnBoundary(wasStreaming, snapshot.isStreaming)
            refreshGitStatusOnceReady(wasReady)
            titleController.onSnapshot(snapshot)
        }

        /**
         * A starting turn retires the previous turn's inline git diffs: they
         * describe the tree as it is now, so the next turn must not borrow them.
         */
        private fun onTurnBoundary(
            wasStreaming: Boolean,
            isStreaming: Boolean,
        ) {
            if (!wasStreaming && isStreaming) gitTurnDiffLoader.clearTurnDiffs()
        }

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
                    state.value.gatewayWorkspaceConnection?.let { gitStatusLoader.refresh() }
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
            state.value.gatewayWorkspaceConnection?.let { gitStatusLoader.refresh() }
            // An agent turn changes the working tree, so a compare list read earlier is stale too.
            if (state.value.git.compareRef != null) gitStatusLoader.refreshCompare()
            // A turn may have committed, so history once opened follows along.
            if (state.value.git.history.isLoaded) gitHistoryLoader.loadLog(more = false)
        }

        /** The git sheet's history, commit and compare intents. */
        private fun handleGitSheetIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.LoadGitLog -> gitHistoryLoader.loadLog(intent.more)
                is ChatIntent.LoadGitCommit -> gitHistoryLoader.loadCommit(intent.sha)
                is ChatIntent.LoadGitRefs -> gitStatusLoader.loadRefs()
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
            val refs = current.git.refs
            val branch =
                current.git.status
                    ?.branch
                    .orEmpty()
            if (compareChosenByReader || current.git.compareRef != null || refs.unsupported) return
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
            if (ref == state.value.git.compareRef) return
            // The previous target's rows are no stand-in for the new one's.
            updateState {
                copy(
                    git =
                        git.copy(
                            compareRef = ref,
                            compareStatus = null,
                            isCompareStatusLoading = false,
                            compareStatusError = null,
                        ),
                )
            }
            gitStatusLoader.refreshCompare()
        }

        /** One-shot intents outside the send/streaming core: diff fetch, hint flag, session close. */
        private suspend fun handleAuxIntent(intent: ChatIntent) {
            when (intent) {
                is ChatIntent.RemoveQueuedMessage -> offlineQueue.removeQueuedMessage(intent.clientId)
                is ChatIntent.ForceSendMessage -> offlineQueue.forceSend(intent.clientId)
                is ChatIntent.LoadGitDiff -> gitFileDiffLoader.load(intent.path, intent.commit, intent.oldPath)
                is ChatIntent.LoadTurnFileDiff ->
                    gitTurnDiffLoader.loadTurnFile(
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
                is ChatIntent.MarkSwitcherHintSeen -> stores.switcherHints.markSeen()
                is ChatIntent.CloseSession -> closeSwitcherSession(intent)
                is ChatIntent.SubmitElicitation,
                is ChatIntent.DeclineElicitation,
                is ChatIntent.CancelElicitation,
                -> handleElicitationIntent(intent)
                else -> Unit
            }
        }

        private suspend fun closeSwitcherSession(intent: ChatIntent.CloseSession) {
            val rows = switcherUiState.value.groups.flatMap { it.sessions }
            val closingCurrent =
                rows.any {
                    it.isCurrent && it.serverId == intent.serverId && it.sessionId == intent.sessionId
                }
            val next = rows.filterNot { it.isCurrent }.maxByOrNull { it.updatedAt ?: Long.MIN_VALUE }
            val message = switcherController.close(intent.serverId, intent.sessionId)
            if (message != null) {
                emitEffect(ChatEffect.ShowError(message))
            } else if (closingCurrent) {
                emitEffect(next?.let { ChatEffect.SwitchSession(it) } ?: ChatEffect.NavigateBack)
            }
        }

        private suspend fun handleElicitationIntent(intent: ChatIntent) {
            when (intent) {
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
                durableSessionId?.let { stores.scrollState.save(serverId, it, snapshot) }
            }
            updateState {
                if (restoredScrollSnapshot == snapshot) {
                    this
                } else {
                    copy(restoredScrollSnapshot = snapshot)
                }
            }
        }
    }

/**
 * The state a settled snapshot leaves on screen. [ChatState.error] is what the chat shows before the
 * snapshot, because a HYDRATING snapshot must not wipe a load error it has not replaced.
 */
private fun ChatState.withSnapshot(
    snapshot: ChatSessionSnapshot,
    messages: List<ChatMessage>,
    pending: List<ChatMessage>,
    echoKeys: Map<String, String>,
    markdown: MarkdownStateProjection,
): ChatState {
    val failed = snapshot.loadState == ChatLoadState.FAILED
    // A HYDRATING snapshot is an in-flight marker: it asserts that a load is
    // running, not that a reported failure is over. Letting it rewrite the error
    // to null and `isLoading` back to true replaces a visible load error with a
    // spinner that never resolves — the user sees "still loading" forever while
    // the message explaining why was dropped one emission ago.
    val loadErrorStands = !failed && snapshot.loadState == ChatLoadState.HYDRATING && error != null
    return copy(
        messages = messages,
        pendingMessages = pending,
        listKeys = if (echoKeys.isEmpty()) listKeys else listKeys + echoKeys,
        markdownDocuments = markdown.documents,
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
                        markdown.pendingInitialHydration
                ),
        isSessionReady =
            snapshot.loadState == ChatLoadState.READY &&
                !markdown.pendingInitialHydration,
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
