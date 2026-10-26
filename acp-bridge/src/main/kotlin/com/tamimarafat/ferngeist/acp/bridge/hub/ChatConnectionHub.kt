package com.tamimarafat.ferngeist.acp.bridge.hub

import com.agentclientprotocol.annotations.UnstableApi
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.GatewaySessionResumeRejectedException
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** Gateway endpoint triple needed for session-scoped REST calls (close, capacity). */
data class GatewayEndpoint(
    val scheme: String,
    val host: String,
    val credential: String,
)

/**
 * The gateway no longer knows the session it is asked to resume: its own restart
 * dropped it, or the runtime failed. A fresh connect is the only way forward.
 */
private const val GATEWAY_SESSION_GONE_STATUS = 400

/**
 * The gateway no longer knows the session id it is asked to delete — the
 * recorded lease pointer is stale (restart/reap) and safe to drop.
 */
private const val GATEWAY_NOT_FOUND_STATUS = 404

/**
 * [ChatConnectionHub] tuning: how many chats stay hot, how many gateway sessions
 * this device may lease, the snapshot cache bound, and the clock the entry table
 * ranks focus with.
 */
data class HubLimits(
    val maxHotConnections: Int = 3,
    val maxGatewaySessionsPerDevice: Int = 5,
    val clock: () -> Long = System::currentTimeMillis,
    val maxSnapshotCacheSize: Int = 20,
)

/**
 * Single owner of every ACP manager lifetime *and* chat presence in the process.
 *
 * Chat managers are created via [acquireChatManager] (tracked as pending until
 * [register] promotes them into a hot entry) and torn down only here — evict,
 * explicit close, and abandon all call the manager's single [AcpConnection.release]
 * (disconnect-then-close, paired inside the manager), so callers can never
 * half-release a transport. Browser (listing) managers are created via
 * [createBrowserManager] and auto-released with their owner scope.
 *
 * Presence: each hot entry is keyed by the canonical [chatIdFor] composite and records
 * transport facts plus whether its chat screen is open and the cwd it was
 * opened with. Entries exist while screen-open or transport-attached; recency
 * bumps only on screen open or fresh-entry creation — never on transport
 * re-attach — so reconnects cannot re-rank the chat the user is viewing.
 * [onScreenChat] is the most recently focused screen-open chat, [tapTarget]
 * falls back to the most recently focused pooled (transport-only) chat so a
 * notification tap returns to a chat after back-out, and [warmServers] /
 * [connectedSessionIds] / [warmManagerFor] expose the connected subset.
 *
 * [anyConnected] and [anyActive] aggregate over every tracked manager so
 * process-wide observers (foreground service, battery gate) react to any
 * connection. Registrations are rare, so the aggregate recombines on a revision
 * bump via [flatMapLatest], discarding the previous combination each time.
 *
 * Session listing, listing auth, close/delete and gateway-lease reconcile live in
 * [com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog], which reaches in
 * here only for transports ([createBrowserManager]), presence and tracked-chat
 * teardown ([close]) — the hub owns pooling and presence, nothing else.
 *
 * Carries more functions than detekt's TooManyFunctions budget: the presence
 * surface is deliberately many small single-purpose operations so callers get
 * role-shaped entry points (screen-open, pooled tap target, aggregates), and
 * the count grew with the presence merge. Suppressed rather than merged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class ChatConnectionHub(
    private val gatewayRepository: GatewayRepository,
    private val scope: CoroutineScope,
    private val connectivityObserver: ConnectivityObserver,
    private val limits: HubLimits = HubLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatConnectionSurface {
    private data class Entry(
        val chatId: String,
        val serverId: String,
        val sessionId: String,
        val gatewaySessionId: String?,
        val gatewaySourceId: String,
        val agentId: String,
        val isConnected: () -> Boolean,
        val isStreaming: () -> Boolean,
        val manager: AcpConnection?,
        var lastFocusedMs: Long,
        // Open screens, counted rather than flagged: a fast A→B→A switch builds the new
        // A screen before the old one's teardown closes it, and a flag would end up closed.
        val openScreens: Int,
        val cwd: String,
    )

    private val Entry.screenOpen: Boolean get() = openScreens > 0

    private val entries = LinkedHashMap<String, Entry>()
    private val snapshots = LinkedHashMap<String, ChatSessionSnapshot>()
    private val pendingManagers = CopyOnWriteArrayList<AcpConnection>()
    private val browserManagers = CopyOnWriteArrayList<AcpConnection>()
    private val revision = MutableStateFlow(0L)

    /**
     * Mints a fresh attachToken for a gateway session on the transport's behalf — the
     * one gateway call a transport makes, so the managers take this instead of the
     * repository. A resurrected session on reconnect needs a token minted now, not the
     * one the handoff carried.
     *
     * Internal, not private: the 400 mapping is the one behaviour here worth a test.
     */
    internal val resumeAttachToken: suspend (
        gateway: AcpConnectionConfig.Gateway,
        session: AcpConnectionConfig.ResumeInfo,
    ) -> String = { gateway, session ->
        try {
            gatewayRepository
                .resumeSession(
                    scheme = gateway.scheme,
                    host = gateway.host,
                    gatewayCredential = gateway.gatewayCredential,
                    sessionId = session.sessionId,
                ).attachToken
        } catch (error: GatewayRequestException) {
            // 400 means the gateway no longer knows the session (its own restart, or a
            // runtime that failed): no amount of retrying brings it back, so the
            // transport must stop reconnecting and the chat must start a fresh connect.
            // Every other status stays the transport's own failure.
            if (error.statusCode != GATEWAY_SESSION_GONE_STATUS) throw error
            throw GatewaySessionResumeRejectedException(error.message, error)
        }
    }

    // Presence is deliberately in-memory, like the focus-ordered store this hub supersedes.
    // ponytail: a Room-backed or event-sourced presence table would replay
    // open/closed state after process death if that ever becomes a requirement.
    private val _tapTarget = MutableStateFlow<ChatPresence?>(null)
    private val _onScreenChat = MutableStateFlow<ChatPresence?>(null)

    /**
     * Notification tap target: the most recently focused screen-open chat, else
     * the most recently focused pooled (transport-only) chat so a backgrounded
     * connection still deep-links back into the chat the user left. Null only
     * when the hub tracks no chats.
     *
     * ponytail: per-chat streaming notification heads and push-opened chats
     * without a screen fit as future internal Entry fields when a consumer
     * needs them.
     */
    val tapTarget: StateFlow<ChatPresence?> = _tapTarget.asStateFlow()

    /** Most recently focused screen-open chat, or null when no chat screen is open. */
    val onScreenChat: StateFlow<ChatPresence?> = _onScreenChat.asStateFlow()

    /**
     * Server ids holding at least one tracked entry whose manager is connected.
     * Combines each entry manager's live [AcpConnectionState] flow, so warm
     * presence updates the moment a manager connects or disconnects on its own —
     * no explicit [refresh] required.
     */
    val warmServers: StateFlow<Set<String>> =
        connectedManagerValues(filter = { true }, select = { it.serverId })
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _connectedChatIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedChatIds: StateFlow<Set<String>> = _connectedChatIds.asStateFlow()

    private val _trackedChatIds = MutableStateFlow<Set<String>>(emptySet())
    val trackedChatIds: StateFlow<Set<String>> = _trackedChatIds.asStateFlow()

    private val streamingObservers = HashMap<String, Job>()

    private val _streamingChatIds = MutableStateFlow<Set<String>>(emptySet())
    val streamingChatIds: StateFlow<Set<String>> = _streamingChatIds.asStateFlow()

    init {
        scope.launch {
            connectedManagerValues(filter = { true }, select = { it.chatId }).collect { connected ->
                _connectedChatIds.value = connected
                val disconnected =
                    entries.values
                        .filter {
                            it.manager != null && it.manager.connectionState.value !is AcpConnectionState.Connected
                        }.map { it.chatId }
                        .toSet()
                _streamingChatIds.update { it - disconnected }
            }
        }
    }

    /** Network availability, so a feature can re-run work that failed while the device was offline. */
    val networkAvailable: Flow<Boolean> get() = connectivityObserver.isConnected

    /** True when any tracked manager is currently connected. */
    val anyConnected: StateFlow<Boolean> =
        revision
            .flatMapLatest {
                val managers = allManagers()
                if (managers.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(managers.map { manager -> manager.connectionState }) { states ->
                        states.any { it is AcpConnectionState.Connected }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True when any tracked manager is connected, mid-connect, or waiting out a
     * reconnect backoff. The backoff counts: it reads Disconnected/Failed, and
     * treating it as idle stopped the foreground service on every background
     * blip — the cached process was then frozen and never reconnected.
     */
    val anyActive: StateFlow<Boolean> =
        revision
            .flatMapLatest {
                val managers = allManagers()
                if (managers.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(
                        managers.map { manager ->
                            combine(manager.connectionState, manager.reconnectPending) { state, reconnecting ->
                                reconnecting ||
                                    state is AcpConnectionState.Connected ||
                                    state is AcpConnectionState.Connecting
                            }
                        },
                    ) { active -> active.any { it } }
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Pings every connected transport so one whose peer vanished while backgrounded fails now. */
    fun probeConnections() {
        allManagers().forEach { it.probeConnection() }
    }

    /**
     * Hangs up every transport and cancels its reconnect, so [anyActive] goes idle and
     * nothing restarts the foreground service. Entries stay, so an open chat can reconnect.
     */
    fun disconnectAll() {
        allManagers().forEach { it.disconnect() }
    }

    /**
     * Creates an app-scoped chat manager, tracked as pending until [register]
     * promotes it into a hot entry. A spawn that fails before attach stays
     * tracked (visible in the aggregates) until [abandon] releases it.
     */
    override fun acquireChatManager(): AcpConnection =
        AcpConnection(connectivityObserver, resumeAttachToken, scope).also { manager ->
            pendingManagers.addIfAbsent(manager)
            revision.value += 1
        }

    /**
     * Releases an [acquireChatManager] manager that never registered (failed
     * spawn, cleared screen). No-op for managers already promoted into entries.
     */
    override fun abandon(manager: AcpConnection) {
        if (pendingManagers.remove(manager)) {
            revision.value += 1
            manager.release()
        }
    }

    /**
     * Creates a browser (listing) manager owned by [ownerScope]: untracked and
     * torn down when the scope completes.
     */
    fun createBrowserManager(ownerScope: CoroutineScope): AcpConnection =
        AcpConnection(connectivityObserver, resumeAttachToken, ownerScope).also { manager ->
            browserManagers.addIfAbsent(manager)
            revision.value += 1
            ownerScope.coroutineContext[Job]?.invokeOnCompletion {
                if (browserManagers.remove(manager)) {
                    revision.value += 1
                }
                hangUpBrowserTransport(manager, release = true)
            }
        }

    /**
     * Number of tracked entries plus pending and browser managers — test
     * visibility for lifecycle assertions.
     */
    fun trackedCount(): Int = entries.size + pendingManagers.size + browserManagers.size

    /**
     * Registers (or re-attaches the transport of) a chat connection. Returns the
     * stable chat id.
     *
     * At capacity, pooled idle entries are evicted first, least-recently-focused
     * first. When every entry is streaming or on screen the over-cap is
     * tolerated only up to the gateway's per-device session cap; past that point
     * new spawns fail and each extra entry leaks a transport, so the
     * least-recently-focused entry is evicted anyway (its turn is abandoned with
     * it).
     *
     * When the key already exists only transport facts are merged — recency is
     * never bumped, so reconnects/re-attaches cannot steal the screen head.
     * A fresh entry records the registration time as its recency.
     *
     * [manager] is the chat's connection, promoted from pending into the hot
     * entry. Eviction and explicit close tear it down here; callers never do.
     */
    override fun register(
        serverId: String,
        sessionId: String,
        registration: ChatRegistration,
    ): String {
        val chatId = chatIdFor(serverId, sessionId)
        if (registration.manager != null) {
            pendingManagers.remove(registration.manager)
        }
        val existing = entries[chatId]
        if (existing != null) {
            entries[chatId] =
                existing.copy(
                    gatewaySessionId = registration.gatewaySessionId,
                    gatewaySourceId = registration.gatewaySourceId,
                    agentId = registration.agentId,
                    isConnected = registration.isConnected,
                    isStreaming = registration.isStreaming,
                    manager = registration.manager,
                )
        } else {
            makeRoomForEntry()
            entries[chatId] =
                Entry(
                    chatId = chatId,
                    serverId = serverId,
                    sessionId = sessionId,
                    gatewaySessionId = registration.gatewaySessionId,
                    gatewaySourceId = registration.gatewaySourceId,
                    agentId = registration.agentId,
                    isConnected = registration.isConnected,
                    isStreaming = registration.isStreaming,
                    manager = registration.manager,
                    lastFocusedMs = limits.clock(),
                    openScreens = 0,
                    cwd = "",
                )
        }
        observeStreaming(chatId, registration)
        republish()
        return chatId
    }

    /** The turn outlives its screen, so the hub observes its streaming state independently. */
    private fun observeStreaming(
        chatId: String,
        registration: ChatRegistration,
    ) {
        streamingObservers.remove(chatId)?.cancel()
        val snapshot = registration.snapshot ?: return
        streamingObservers[chatId] =
            scope.launch {
                val connection = registration.manager?.connectionState ?: flowOf(AcpConnectionState.Connected)
                combine(snapshot, connection) { state, connectionState ->
                    state.isStreaming && connectionState is AcpConnectionState.Connected
                }.distinctUntilChanged().collect { streaming ->
                    _streamingChatIds.update { if (streaming) it + chatId else it - chatId }
                }
            }
    }

    /** Evicts entries until one more fits under the hot cap; see [register] for the over-cap policy. */
    private fun makeRoomForEntry() {
        while (entries.size >= limits.maxHotConnections) {
            val victim =
                entries.values
                    .filter { !it.isStreaming() && !it.screenOpen }
                    .minByOrNull { it.lastFocusedMs }
                    // Every entry is streaming or on screen. Tolerate the over-cap while the pool
                    // still fits the gateway's per-device session lease; past that point new spawns
                    // fail and each extra entry leaks a transport, so evict the least-recently-focused
                    // one (its turn is abandoned with it).
                    ?: entries.values
                        .takeIf { entries.size >= limits.maxGatewaySessionsPerDevice }
                        ?.minByOrNull { it.lastFocusedMs }
                    ?: break
            removeAndEvict(victim.chatId)
        }
    }

    /** The live transport for a tracked chat, or null when untracked/cold. */
    override fun managerFor(chatId: String): AcpConnection? = entries[chatId]?.manager

    /** Last rendered snapshot for a chat; survives evict/screen-close so reopen paints instantly. Dropped only on close. */
    override fun snapshotFor(chatId: String): ChatSessionSnapshot? = snapshots[chatId]

    /** Stashes the latest rendered snapshot; eldest-evicted past the snapshot cap. Safe before any entry exists. */
    override fun storeSnapshot(
        chatId: String,
        snapshot: ChatSessionSnapshot,
    ) {
        snapshots.remove(chatId)
        while (snapshots.size >= limits.maxSnapshotCacheSize) {
            val evicted = snapshots.keys.first()
            snapshots.remove(evicted)
            _streamingChatIds.update { it - evicted }
        }
        snapshots[chatId] = snapshot
        val manager = entries[chatId]?.manager
        val streaming =
            snapshot.isStreaming && (manager == null || manager.connectionState.value is AcpConnectionState.Connected)
        _streamingChatIds.update { if (streaming) it + chatId else it - chatId }
    }

    /**
     * Records that the user opened [sessionId]'s chat screen, making it the
     * most recently focused screen-open entry. Returns false — and stores
     * nothing — for the [NEW_SESSION_ARG] create-on-arrival route, which is a
     * navigation placeholder, not a chat identity.
     *
     * ponytail: no event log (ChatActivityLog) yet — add one when a consumer
     * needs the full open/close timeline.
     */
    fun chatScreenOpened(
        serverId: String,
        sessionId: String,
        cwd: String,
    ): Boolean {
        if (sessionId == NEW_SESSION_ARG) return false
        val chatId = chatIdFor(serverId, sessionId)
        val existing = entries[chatId]
        if (existing != null) {
            entries[chatId] =
                existing.copy(
                    openScreens = existing.openScreens + 1,
                    cwd = cwd,
                    lastFocusedMs = limits.clock(),
                )
        } else {
            entries[chatId] =
                Entry(
                    chatId = chatId,
                    serverId = serverId,
                    sessionId = sessionId,
                    gatewaySessionId = null,
                    gatewaySourceId = "",
                    agentId = "",
                    isConnected = { false },
                    isStreaming = { false },
                    manager = null,
                    lastFocusedMs = limits.clock(),
                    openScreens = 1,
                    cwd = cwd,
                )
        }
        republish()
        return true
    }

    /**
     * Records that [sessionId]'s chat screen closed. A pooled (transport-
     * attached) entry survives as the tap-target fallback; an entry that only
     * ever existed because its screen was open has no transport to pool and its
     * tracking ends here.
     */
    fun chatScreenClosed(
        serverId: String,
        sessionId: String,
    ) {
        val chatId = chatIdFor(serverId, sessionId)
        entries[chatId]?.let { existing ->
            val openScreens = (existing.openScreens - 1).coerceAtLeast(0)
            if (existing.manager == null && openScreens == 0) {
                streamingObservers.remove(chatId)?.cancel()
                entries.remove(chatId)
            } else {
                entries[chatId] = existing.copy(openScreens = openScreens)
            }
            republish()
        }
    }

    /** True when an entry exists for this server/session composite key. */
    fun isTracked(
        serverId: String,
        sessionId: String,
    ): Boolean = entries.containsKey(chatIdFor(serverId, sessionId))

    /** True when the hub-tracked entry for this server/session pair reports streaming. */
    fun isStreaming(
        serverId: String,
        sessionId: String,
    ): Boolean = entries[chatIdFor(serverId, sessionId)]?.isStreaming() == true

    /**
     * Drops a browser (listing) transport the catalog owns: untracked from the
     * aggregates and released exactly once.
     */
    internal fun releaseBrowserTransport(transport: AcpConnection) {
        if (browserManagers.remove(transport)) {
            revision.value += 1
        }
        hangUpBrowserTransport(transport, release = true)
    }

    /**
     * Hangs [transport] up ([release] also frees it) and DELETEs the gateway session
     * it was bound to. A gateway v2 connect launches a dedicated agent process, so a
     * listing or probe socket merely disconnected keeps it alive until the gateway's
     * reaper, counting against the per-device session limit.
     */
    fun hangUpBrowserTransport(
        transport: AcpConnection,
        release: Boolean = false,
    ) {
        // An explicit disconnect already ran this, so skip the repeat DELETE.
        val gateway =
            (transport.currentConnectionConfig() as? AcpConnectionConfig.Gateway)
                ?.takeIf { transport.connectionState.value != AcpConnectionState.Disconnected }
        if (release) transport.release() else transport.disconnect()
        gateway ?: return
        scope.launch(ioDispatcher) {
            // Best-effort: the reaper still collects a session this misses.
            runCatching {
                gatewayRepository.closeSession(
                    gateway.scheme,
                    gateway.host,
                    gateway.gatewayCredential,
                    gateway.resume.sessionId,
                )
            }
        }
    }

    /** Any tracked chat manager for [serverId], connected or not; null when none is tracked. */
    internal fun trackedManagerFor(serverId: String): AcpConnection? =
        entries.values.firstOrNull { it.serverId == serverId }?.manager

    /**
     * Cold flow of connected session ids tracked on [serverId]. Combines each
     * entry manager's live connection state, so a manager connecting or
     * disconnecting re-emits on its own; the combination also rebuilds on every
     * entry-table change (register/evict/close) via the shared derivation.
     */
    fun connectedSessionIds(serverId: String): Flow<Set<String>> =
        connectedManagerValues(filter = { it.serverId == serverId }, select = { it.sessionId })

    /** First same-server tracked entry whose manager is connected, or null. */
    fun warmManagerFor(serverId: String): AcpConnection? =
        entries.values.firstOrNull { it.serverId == serverId && it.isConnectedNow() }?.manager

    /**
     * Recomputes screen presence ([tapTarget], [onScreenChat]) from the entry
     * table. Warm and aggregate observers follow manager connection states
     * directly, so this is only needed by callers that want an immediate
     * republish on transport changes (the chat facade calls it).
     */
    override fun refresh() {
        republish()
    }

    /**
     * Explicit close: DELETE the gateway session first (stops the agent
     * process), then tear down the local transport. Missing endpoint or
     * gateway session skips the remote call.
     */
    suspend fun close(
        chatId: String,
        endpoint: GatewayEndpoint? = null,
    ) {
        streamingObservers.remove(chatId)?.cancel()
        snapshots.remove(chatId)
        _streamingChatIds.update { it - chatId }
        val entry = entries.remove(chatId) ?: return
        try {
            val gatewaySessionId = entry.gatewaySessionId
            if (endpoint != null && gatewaySessionId != null) {
                gatewayRepository.closeSession(endpoint.scheme, endpoint.host, endpoint.credential, gatewaySessionId)
            }
        } finally {
            entry.manager?.let { it.release() }
            republish()
        }
    }

    private fun allManagers(): List<AcpConnection> =
        entries.values.mapNotNullTo(mutableListOf()) { it.manager } + pendingManagers + browserManagers

    private fun removeAndEvict(chatId: String) {
        streamingObservers.remove(chatId)?.cancel()
        _streamingChatIds.update { it - chatId }
        val entry = entries.remove(chatId)
        if (entry != null) {
            entry.manager?.let { it.release() }
            republish()
        }
    }

    /** True when the entry's live manager reports Connected; entries without a transport are never connected. */
    private fun Entry.isConnectedNow(): Boolean = manager?.connectionState?.value is AcpConnectionState.Connected

    /**
     * Live connected-entry derivation shared by [warmServers], [connectedChatIds] and
     * [connectedSessionIds]. Follows the same shape as [anyConnected]: the
     * combination is rebuilt via [flatMapLatest] whenever [revision] bumps
     * (entry-table changes), and within a stable entry set it combines each
     * manager's [AcpConnectionState] flow, so it re-emits the moment a manager
     * connects or disconnects on its own.
     */
    private fun <T> connectedManagerValues(
        filter: (Entry) -> Boolean,
        select: (Entry) -> T,
    ): Flow<Set<T>> =
        revision.flatMapLatest {
            val managerEntries =
                entries.values
                    .filter(filter)
                    .mapNotNull { entry -> entry.manager?.let { entry to it } }
            if (managerEntries.isEmpty()) {
                flowOf(emptySet())
            } else {
                combine(managerEntries.map { pair -> pair.second.connectionState }) { states ->
                    buildSet {
                        managerEntries.zip(states.toList()).forEach { pair ->
                            val entry = pair.first.first
                            if (pair.second is AcpConnectionState.Connected) {
                                add(select(entry))
                            }
                        }
                    }
                }
            }
        }

    private fun Entry.toPresence(): ChatPresence =
        ChatPresence(
            serverId = serverId,
            sessionId = sessionId,
            cwd = cwd,
            // A screen-first entry has no gateway identity until the transport
            // attaches; "" would read as a mismatch, null reads as unknown.
            gatewaySourceId = gatewaySourceId.ifEmpty { null },
        )

    /**
     * Reads one session's current title without writing the session table.
     *
     * Warm-transport only: the caller is an open chat, so an absent warm
     * transport means the chat is not connected and there is nothing to ask.
     * The agent may also be unable to list sessions, the request may fail, or
     * the session may be absent from the response — all read as null, because a
     * title the chat cannot resolve must never surface as a chat error.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    override suspend fun resolveSessionTitle(
        serverId: String,
        sessionId: String,
        cwd: String?,
    ): String? {
        val warm = warmManagerFor(serverId) ?: return null
        val caps = warm.agentCapabilities.value
        if (caps != null && caps.sessionCapabilities.list == null) return null
        return try {
            val sessions = warm.listSessions(cwd = cwd)
            sessions.firstOrNull { it.id == sessionId }?.title?.takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
    }

    /**
     * Closes [sessionId] on [serverId]: hub-tracked chats go through the
     * tracked close (REST DELETE + transport teardown); cold sessions close
     * the gateway session whose `acpSessionIds` lists [sessionId].
     *
     * Returns true when a tracked chat was closed or a gateway session was
     * deleted, false when nothing was left to close (no gateway session runs
     * it) so the caller can surface that instead of failing silently.
     */
    suspend fun closeSession(
        serverId: String,
        sessionId: String,
        endpoint: GatewayEndpoint,
    ): Boolean {
        if (isTracked(serverId, sessionId)) {
            // The gateway may already have forgotten the session (restart/reap): the
            // local transport is released either way, so that does not fail the close.
            ignoringNotFound { close(chatIdFor(serverId, sessionId), endpoint) }
            return true
        }
        val gatewaySessionId =
            gatewayRepository
                .listGatewaySessions(endpoint.scheme, endpoint.host, endpoint.credential)
                .firstOrNull { sessionId in it.acpSessionIds }
                ?.sessionId ?: return false
        // Reaped between the listing and the DELETE: nothing runs it any more.
        ignoringNotFound {
            gatewayRepository.closeSession(
                scheme = endpoint.scheme,
                host = endpoint.host,
                gatewayCredential = endpoint.credential,
                sessionId = gatewaySessionId,
            )
        }
        return true
    }

    private suspend fun ignoringNotFound(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: GatewayRequestException) {
            if (error.statusCode != GATEWAY_NOT_FOUND_STATUS) throw error
        }
    }

    /**
     * Recomputes screen presence ([tapTarget], [onScreenChat]) from the entry
     * table (MRU by [Entry.lastFocusedMs]) and bumps the revision that the
     * manager-state observers ([anyConnected], [anyActive], [warmServers],
     * [connectedSessionIds]) rebuild their combinations on.
     */
    private fun republish() {
        _trackedChatIds.value = entries.keys.toSet()
        val ordered = entries.values.sortedByDescending { it.lastFocusedMs }
        val onScreen = ordered.firstOrNull { it.screenOpen }
        _onScreenChat.value = onScreen?.toPresence()
        _tapTarget.value = (onScreen ?: ordered.firstOrNull())?.toPresence()
        revision.value += 1
    }
}

/** Builds the canonical chat key: `"<serverId>/<sessionId>"`. Single format owner for the hub contract. */
internal fun chatIdFor(
    serverId: String,
    sessionId: String,
): String = "$serverId/$sessionId"
