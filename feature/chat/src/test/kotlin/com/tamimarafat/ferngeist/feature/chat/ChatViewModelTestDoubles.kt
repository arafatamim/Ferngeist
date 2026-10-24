package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
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
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// region: Test facades

/**
 * Configurable [ChatSessionFacade] for offline-queue tests. Extends [FakeChatSessionFacade]
 * to inherit all no-op defaults, overriding only [sendMessage] (to control success/failure
 * per call) and [reconnect] (to track reconnect calls).
 */
open class TestFacade(
    private val sendResultProvider: () -> Boolean = { true },
    operationErrorText: String = "Session is not ready. Please retry in a moment.",
) : FakeChatSessionFacade(operationErrorText) {
    constructor(sendResult: Boolean) : this({ sendResult })

    var reconnectCount = 0
        private set

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        val result = sendResultProvider()
        if (!result) {
            emitOperationError()
        }
        return result
    }

    override suspend fun reconnect() {
        reconnectCount++
    }

    suspend fun emitSessionReady() {
        sessionReadyFlow.emit(Unit)
    }

    suspend fun emitLoadFailed(message: String) {
        loadFailedFlow.emit(message)
    }

    suspend fun emitSnapshot(snapshot: ChatSessionSnapshot) {
        sessionSnapshotFlow.emit(snapshot)
    }
}

class TestFacadeFactory(
    private val factory: () -> TestFacade,
) : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<TestFacade?>(null)

    /** Session id the ViewModel asked for; the sentinel means it will mint a new one. */
    var lastRequestedSessionId: String? = null
        private set

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        lastRequestedSessionId = sessionId
        val facade = factory()
        lastFacade.value = facade
        return facade
    }
}

/**
 * Fake [ChatSessionFacade] that simulates a session that is never ready.
 * [loadSession] emits a load-failed error; all operations emit [operationError].
 */
open class FakeChatSessionFacade(
    /** Message [emitOperationError] carries; tests pass a sentinel to keep it distinct from a load failure. */
    protected val operationErrorText: String = "Session is not ready. Please retry in a moment.",
) : ChatSessionFacade {
    protected val connectionStateFlow = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    protected val diagnosticsFlow = MutableStateFlow(ChatConnectionDiagnostics())
    protected val sessionSnapshotFlow = MutableStateFlow<ChatSessionSnapshot?>(null)
    protected val agentCapabilitiesFlow = MutableStateFlow(ChatAgentCapabilities())
    protected val gatewayWorkspaceConnectionFlow = MutableStateFlow<GatewayWorkspaceConnection?>(null)

    protected val loadFailedFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    protected val operationErrorFlow = MutableSharedFlow<ChatOperationError>(extraBufferCapacity = 1)
    protected val streamingCancelledFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val cancelUnsupportedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val sessionReadyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    protected val modelUpdatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val connectionState: StateFlow<ChatConnectionState> = connectionStateFlow
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = diagnosticsFlow
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = sessionSnapshotFlow
    override val cachedSnapshot: StateFlow<ChatSessionSnapshot?> = sessionSnapshotFlow
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = agentCapabilitiesFlow
    override val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?> = gatewayWorkspaceConnectionFlow
    protected val liveChatIdFlow = MutableStateFlow<String?>(null)
    override val liveChatId: StateFlow<String?> = liveChatIdFlow

    /** Test hook: push a new snapshot into the session state. */
    protected fun setSessionSnapshot(snapshot: ChatSessionSnapshot?) {
        sessionSnapshotFlow.value = snapshot
    }

    override val loadFailed: SharedFlow<String> = loadFailedFlow
    override val operationError: SharedFlow<ChatOperationError> = operationErrorFlow
    override val streamingCancelled: SharedFlow<Unit> = streamingCancelledFlow
    override val cancelUnsupported: SharedFlow<Unit> = cancelUnsupportedFlow
    override val sessionReady: SharedFlow<Unit> = sessionReadyFlow
    override val modelUpdated: SharedFlow<Unit> = modelUpdatedFlow

    override suspend fun loadSession() {
        loadFailedFlow.emit("Session not found")
    }

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean {
        emitOperationError()
        return false
    }

    override suspend fun cancelStreaming() {
        emitOperationError()
    }

    override suspend fun setConfigOption(
        optionId: String,
        value: ChatConfigValue,
    ) {
        emitOperationError()
    }

    override suspend fun grantPermission(
        toolCallId: String,
        optionId: String,
    ) {
        // No-op: permissions not exercised in this fake.
    }

    override suspend fun denyPermission(toolCallId: String) {
        // No-op: permissions not exercised in this fake.
    }

    override suspend fun submitElicitation(
        key: String,
        values: Map<String, com.tamimarafat.ferngeist.core.model.ChatElicitationValue>,
    ) {
        // No-op: elicitations not exercised in this fake.
    }

    override suspend fun declineElicitation(key: String) {
        // No-op: elicitations not exercised in this fake.
    }

    override suspend fun cancelElicitation(key: String) {
        // No-op: elicitations not exercised in this fake.
    }

    override fun clear() {
        // No-op: no bridge state to tear down in this fake.
    }

    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {
        // No-op: connection state is driven directly via the state flows.
    }

    override suspend fun reconnect() {
        // No-op: reconnect is not exercised in this fake.
    }

    /** Title a `session/list` title fallback would return; null means nothing to apply. */
    var fetchedSessionTitle: String? = null

    /**
     * Titles keyed by 1-based attempt number, for exercising the fallback's retries. An
     * absent key falls back to [fetchedSessionTitle].
     */
    var fetchedSessionTitlesByCall: Map<Int, String?> = emptyMap()

    /** Number of times the view model asked the agent for this session's title. */
    var fetchSessionTitleCalls = 0
        private set

    override suspend fun fetchSessionTitle(): String? {
        fetchSessionTitleCalls++
        return fetchedSessionTitlesByCall[fetchSessionTitleCalls] ?: fetchedSessionTitle
    }

    // ---- Gateway working tree ----

    /** Git status this facade answers with; null stands for a chat with no gateway workspace. */
    var gitStatus: ChatGitStatus? = null

    /** Diff [fetchGitDiff] answers with when no per-path result matches. */
    var fetchGitDiffResult: List<ToolCallContent.Diff> = emptyList()

    /** Thrown by [fetchGitDiff] instead of answering, when set. */
    var fetchGitDiffError: Throwable? = null

    /** When set, [fetchGitDiff] suspends until it completes, so tests can observe the loading state. */
    var fetchGitDiffGate: CompletableDeferred<Unit>? = null

    /** Per-path gates for [fetchGitDiff]; take precedence over [fetchGitDiffGate]. */
    val fetchGitDiffGates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    /** Per-path results for [fetchGitDiff]; take precedence over [fetchGitDiffResult]. */
    val fetchGitDiffResults: MutableMap<String, List<ToolCallContent.Diff>> = mutableMapOf()

    /** Paths [fetchGitDiff] was asked for, in request order. */
    val gitDiffRequests: MutableList<String> = mutableListOf()

    /**
     * Both git reads mirror the real facade's contract: with no gateway workspace connection
     * to read through (nothing emitted via [emitGatewayWorkspaceConnection]) they answer null
     * instead of asking a gateway, so a caller never sees a fake that reads a workspace it
     * does not have.
     */
    override suspend fun fetchGitStatus(compare: String?): ChatGitStatus? {
        if (gatewayWorkspaceConnectionFlow.value == null) return null
        if (compare == null) return gitStatus
        gitCompareStatusRequests += compare
        return gitCompareStatus(compare)
    }

    /** Answers [fetchWorktree]; throw from it to fail the read. */
    var worktree: suspend () -> ChatWorktree? = { null }

    override suspend fun fetchWorktree(): ChatWorktree? {
        if (gatewayWorkspaceConnectionFlow.value == null) return null
        return worktree()
    }

    /** Answers a compare [fetchGitStatus] by its qualified ref; throw from it to fail the fetch. */
    var gitCompareStatus: suspend (compare: String) -> ChatGitStatus? = { ref -> gitStatus?.copy(compare = ref) }

    /** Qualified refs of each compare [fetchGitStatus] request, in order. */
    val gitCompareStatusRequests: MutableList<String> = mutableListOf()

    /** Answers [fetchGitRefs]; throw from it to fail the load. */
    var gitRefs: suspend () -> ChatGitRefs? = { null }

    /** The compare ref of each [fetchGitDiff] request, alongside [gitDiffRequests]. */
    val gitDiffCompareRequests: MutableList<String?> = mutableListOf()

    /** Commit and old path of each [fetchGitDiff] request, alongside [gitDiffRequests]. */
    val gitDiffCommitRequests: MutableList<Pair<String?, String?>> = mutableListOf()

    /** Answers [fetchGitLog] by (head, skip); throw from it to fail the page. */
    var gitLog: suspend (head: String?, skip: Int) -> ChatGitLogPage? = { _, _ -> null }

    /** (head, skip) of each [fetchGitLog] request, in order. */
    val gitLogRequests: MutableList<Pair<String?, Int>> = mutableListOf()

    override suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? {
        if (gatewayWorkspaceConnectionFlow.value == null) return null
        gitLogRequests += head to skip
        return gitLog(head, skip)
    }

    /** Answers [fetchGitCommitFiles]; throw from it to fail the commit. */
    var gitCommitFiles: suspend (sha: String) -> List<ChatChangedFile>? = { null }

    override suspend fun fetchGitRefs(): ChatGitRefs? =
        if (gatewayWorkspaceConnectionFlow.value == null) null else gitRefs()

    override suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? =
        if (gatewayWorkspaceConnectionFlow.value == null) null else gitCommitFiles(sha)

    override suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? {
        if (gatewayWorkspaceConnectionFlow.value == null) return null
        gitDiffRequests.add(path)
        gitDiffCommitRequests.add(commit to oldPath)
        gitDiffCompareRequests.add(compare)
        fetchGitDiffGates[path]?.await()
        fetchGitDiffGate?.await()
        fetchGitDiffError?.let { throw it }
        return fetchGitDiffResults[path] ?: fetchGitDiffResult
    }

    /** These tests exercise the git-backed reads; a plain-file open answers "no workspace". */
    override suspend fun fetchFile(path: String): ChatFileContents? = null

    override suspend fun fetchFileList(): List<String>? = null

    protected suspend fun emitOperationError() {
        operationErrorFlow.emit(ChatOperationError(operationErrorText, false))
    }

    /** Emits a gateway workspace connection to the view model's init collector. */
    fun emitGatewayWorkspaceConnection(connection: GatewayWorkspaceConnection) {
        gatewayWorkspaceConnectionFlow.value = connection
    }

    /** Test hook: mints a real hub chat id the way the transport's real-id attach does. */
    fun emitLiveChatId(chatId: String) {
        liveChatIdFlow.value = chatId
    }
}

/** Facade that immediately emits a pre-configured snapshot so [ChatSessionFacade.applySnapshot] is exercised. */
class SnapshotChatSessionFacade(
    snapshot: ChatSessionSnapshot,
) : FakeChatSessionFacade() {
    init {
        setSessionSnapshot(snapshot)
    }
}

/** Factory that creates [SnapshotChatSessionFacade] with the given snapshot. */
class SnapshotChatSessionFacadeFactory(
    private val snapshot: ChatSessionSnapshot,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade = SnapshotChatSessionFacade(snapshot)
}

/** Factory that returns [FakeChatSessionFacade] instances. */
class FakeChatSessionFacadeFactory : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<FakeChatSessionFacade?>(null)

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val facade = FakeChatSessionFacade()
        lastFacade.value = facade
        return facade
    }
}

// endregion

// region: Connectivity

/**
 * [ConnectivityObserver] stub so a presence test hub can attach real (never
 * connected) transports via [com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub.acquireChatManager].
 */
class FakeConnectivityObserver(
    initialState: Boolean = false,
) : ConnectivityObserver {
    private val _isConnected = MutableStateFlow(initialState)
    override val isConnected: Flow<Boolean> = _isConnected
}

// endregion

// region: Stores

/** In-memory [PendingPromptStore] so ViewModel tests never touch DataStore. */
class InMemoryPendingPromptStore(
    initial: Map<Pair<String, String>, List<QueuedPromptRecord>> = emptyMap(),
) : PendingPromptStore {
    private val entries = initial.toMutableMap()

    override suspend fun restore(
        serverId: String,
        sessionId: String,
    ): List<QueuedPromptRecord> = entries[serverId to sessionId].orEmpty()

    override suspend fun save(
        serverId: String,
        sessionId: String,
        records: List<QueuedPromptRecord>,
    ) {
        if (records.isEmpty()) {
            entries.remove(serverId to sessionId)
        } else {
            entries[serverId to sessionId] = records
        }
    }
}

// endregion
