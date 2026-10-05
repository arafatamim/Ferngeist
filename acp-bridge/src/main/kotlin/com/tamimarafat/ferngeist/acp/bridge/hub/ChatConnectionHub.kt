package com.tamimarafat.ferngeist.acp.bridge.hub

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthMethodInfo
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticateResult
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpAuthenticationRequiredException
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionConfig
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpInitializeResult
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.connection.buildGatewayLaunchContext
import com.tamimarafat.ferngeist.acp.bridge.connection.formatAcpErrorMessage
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/** Gateway endpoint triple needed for session-scoped REST calls (list/close). */
data class GatewayEndpoint(
    val scheme: String,
    val host: String,
    val credential: String,
)

/**
 * The gateway no longer knows the session id it is asked to delete — the
 * recorded lease pointer is stale (restart/reap) and safe to drop.
 */
private const val GATEWAY_NOT_FOUND_STATUS = 404

/** Bounds the lease-reconciliation list call so a slow gateway cannot stall a refresh. */
private const val RECONCILE_TIMEOUT_MS = 10_000L

/**
 * Single owner of every ACP manager lifetime *and* chat presence in the process.
 *
 * Chat managers are created via [acquireChatManager] (tracked as pending until
 * [register] promotes them into a hot entry) and torn down only here — evict,
 * explicit close, and abandon all call the manager's single [AcpConnectionManager.release]
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
 * Carries more functions than detekt's TooManyFunctions budget: the presence
 * surface is deliberately many small single-purpose operations so callers get
 * role-shaped entry points (screen-open, pooled tap target, aggregates), and
 * the count grew with the presence merge. Suppressed rather than merged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class ChatConnectionHub(
    private val gatewayRepository: GatewayRepository?,
    private val scope: CoroutineScope,
    private val connectivityObserver: ConnectivityObserver? = null,
    private val maxHotConnections: Int = 3,
    private val maxGatewaySessionsPerDevice: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSnapshotCacheSize: Int = 20,
    private val sessionRepository: SessionRepository? = null,
    private val launchableTargetRepository: LaunchableTargetRepository? = null,
    private val gatewaySourceRepository: GatewaySourceRepository? = null,
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
        val manager: AcpConnectionManager?,
        var lastFocusedMs: Long,
        // Open screens, counted rather than flagged: a fast A→B→A switch builds the new
        // A screen before the old one's teardown closes it, and a flag would end up closed.
        val openScreens: Int,
        val cwd: String,
    ) {
        val screenOpen: Boolean get() = openScreens > 0
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val snapshots = LinkedHashMap<String, ChatSessionSnapshot>()
    private val pendingManagers = CopyOnWriteArrayList<AcpConnectionManager>()
    private val browserManagers = CopyOnWriteArrayList<AcpConnectionManager>()
    private val revision = MutableStateFlow(0L)

    /**
     * Reusable browser (listing) transport per server, plus the transport that
     * surfaced an outstanding listing auth challenge. Kept disconnected after
     * each list; torn down by [releaseListing].
     */
    private data class ListingSession(
        val serverId: String,
        val browserTransport: AcpConnectionManager,
        var authTransport: AcpConnectionManager? = null,
        // The gateway session the browser transport last connected; see claimIdleListingSession.
        var gatewaySession: IdleListingSession? = null,
    ) {
        fun rememberGatewaySession(config: AcpConnectionConfig) {
            val gatewaySessionId = config.sessionId
            val runtimeId = config.gatewayRuntimeId
            gatewaySession =
                if (gatewaySessionId != null && runtimeId != null) {
                    IdleListingSession(gatewaySessionId, runtimeId)
                } else {
                    null
                }
        }
    }

    private val listingSessions = LinkedHashMap<String, ListingSession>()

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

    /** True when any tracked manager is currently connected. */
    val anyConnected: StateFlow<Boolean> =
        revision
            .flatMapLatest {
                val managers = allManagers()
                if (managers.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(managers.map { it.connectionState }) { states ->
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
     * Creates an app-scoped chat manager, tracked as pending until [register]
     * promotes it into a hot entry. A spawn that fails before attach stays
     * tracked (visible in the aggregates) until [abandon] releases it.
     */
    override fun acquireChatManager(): AcpConnectionManager =
        AcpConnectionManager(requireObserver(), gatewayRepository, scope).also { manager ->
            pendingManagers.addIfAbsent(manager)
            revision.value += 1
        }

    /**
     * Releases an [acquireChatManager] manager that never registered (failed
     * spawn, cleared screen). No-op for managers already promoted into entries.
     */
    override fun abandon(manager: AcpConnectionManager) {
        if (pendingManagers.remove(manager)) {
            revision.value += 1
            manager.release()
        }
    }

    /**
     * Creates a browser (listing) manager owned by [ownerScope]: untracked and
     * torn down when the scope completes.
     */
    fun createBrowserManager(ownerScope: CoroutineScope): AcpConnectionManager =
        AcpConnectionManager(requireObserver(), gatewayRepository, ownerScope).also { manager ->
            browserManagers.addIfAbsent(manager)
            revision.value += 1
            ownerScope.coroutineContext[Job]?.invokeOnCompletion {
                if (browserManagers.remove(manager)) {
                    revision.value += 1
                }
                manager.release()
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
    override suspend fun register(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
        gatewaySourceId: String,
        agentId: String,
        isConnected: () -> Boolean,
        isStreaming: () -> Boolean,
        manager: AcpConnectionManager?,
    ): String {
        val chatId = chatIdFor(serverId, sessionId)
        if (manager != null) {
            pendingManagers.remove(manager)
        }
        entries[chatId]?.let { existing ->
            entries[chatId] =
                existing.copy(
                    gatewaySessionId = gatewaySessionId,
                    gatewaySourceId = gatewaySourceId,
                    agentId = agentId,
                    isConnected = isConnected,
                    isStreaming = isStreaming,
                    manager = manager,
                )
            republish()
            return chatId
        }
        while (entries.size >= maxHotConnections) {
            val victim =
                entries.values
                    .filter { !it.isStreaming() && !it.screenOpen }
                    .minByOrNull { it.lastFocusedMs }
                    // Every entry is streaming or on screen. Tolerate the over-cap while the pool
                    // still fits the gateway's per-device session lease; past that point new spawns
                    // fail and each extra entry leaks a transport, so evict the least-recently-focused
                    // one (its turn is abandoned with it).
                    ?: entries.values
                        .takeIf { entries.size >= maxGatewaySessionsPerDevice }
                        ?.minByOrNull { it.lastFocusedMs }
                    ?: break
            removeAndEvict(victim.chatId)
        }
        entries[chatId] =
            Entry(
                chatId = chatId,
                serverId = serverId,
                sessionId = sessionId,
                gatewaySessionId = gatewaySessionId,
                gatewaySourceId = gatewaySourceId,
                agentId = agentId,
                isConnected = isConnected,
                isStreaming = isStreaming,
                manager = manager,
                lastFocusedMs = clock(),
                openScreens = 0,
                cwd = "",
            )
        republish()
        return chatId
    }

    /** The live transport for a tracked chat, or null when untracked/cold. */
    override fun managerFor(chatId: String): AcpConnectionManager? = entries[chatId]?.manager

    /** Gateway session id of the tracked chat entry, or null when untracked. */
    fun gatewaySessionIdFor(chatId: String): String? = entries[chatId]?.gatewaySessionId

    /** Last rendered snapshot for a chat; survives evict/screen-close so reopen paints instantly. Dropped only on close. */
    override fun snapshotFor(chatId: String): ChatSessionSnapshot? = snapshots[chatId]

    /** Stashes the latest rendered snapshot; eldest-evicted past the snapshot cap. Safe before any entry exists. */
    override fun storeSnapshot(
        chatId: String,
        snapshot: ChatSessionSnapshot,
    ) {
        snapshots.remove(chatId)
        while (snapshots.size >= maxSnapshotCacheSize) {
            snapshots.remove(snapshots.keys.first())
        }
        snapshots[chatId] = snapshot
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
                    lastFocusedMs = clock(),
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
                    lastFocusedMs = clock(),
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

    /** True when this agent on this source already holds a live (connected) gateway session. */
    override fun claimIdleListingSession(serverId: String): IdleListingSession? {
        val listing = listingSessions[serverId] ?: return null
        if (listing.browserTransport.isConnected) return null
        return listing.gatewaySession.also { listing.gatewaySession = null }
    }

    override fun hasLiveGatewaySession(
        gatewaySourceId: String,
        agentId: String,
    ): Boolean =
        entries.values.any {
            it.gatewaySourceId == gatewaySourceId && it.agentId == agentId && it.isConnected()
        }

    override suspend fun persistedGatewaySessionId(
        serverId: String,
        sessionId: String,
    ): String? = sessionRepository?.getSession(serverId, sessionId)?.gatewaySessionId

    /** All tracked gateway session ids — protected from capacity eviction. */
    fun gatewaySessionIds(): Set<String> = entries.values.mapNotNullTo(mutableSetOf()) { it.gatewaySessionId }

    /**
     * Cold flow of connected session ids tracked on [serverId]. Combines each
     * entry manager's live connection state, so a manager connecting or
     * disconnecting re-emits on its own; the combination also rebuilds on every
     * entry-table change (register/evict/close) via the shared derivation.
     */
    fun connectedSessionIds(serverId: String): Flow<Set<String>> =
        connectedManagerValues(filter = { it.serverId == serverId }, select = { it.sessionId })

    /** First same-server tracked entry whose manager is connected, or null. */
    fun warmManagerFor(serverId: String): AcpConnectionManager? =
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
        snapshots.remove(chatId)
        val entry = entries.remove(chatId) ?: return
        try {
            val gatewaySessionId = entry.gatewaySessionId
            if (endpoint != null && gatewaySessionId != null && gatewayRepository != null) {
                gatewayRepository.closeSession(endpoint.scheme, endpoint.host, endpoint.credential, gatewaySessionId)
            }
        } finally {
            entry.manager?.let { it.release() }
            republish()
        }
    }

    /**
     * Frees a device gateway-session slot before a spawn when the device cap
     * is reached: closes the oldest live session not owned by a tracked chat.
     * Throws when every session is spoken for.
     *
     * "Live" means the gateway would still have to count it:
     * [GatewaySessionSummary.isResumable], i.e. `active` or `disconnected`.
     * A `disconnected` session still holds its runtime lease, so it occupies a
     * slot exactly like an `active` one — counting only `active` would let the
     * helper report a free slot that the gateway does not have, and the spawn
     * would come back session-less.
     */
    override suspend fun ensureGatewayCapacity(endpoint: GatewayEndpoint) {
        val repository = gatewayRepository ?: throw IllegalStateException("No gateway repository available")
        val live =
            repository
                .listGatewaySessions(endpoint.scheme, endpoint.host, endpoint.credential)
                .filter { it.isResumable }
        if (live.size < maxGatewaySessionsPerDevice) return
        val protected = gatewaySessionIds()
        // ponytail: createdAt is an ISO-8601 string, so lexicographic min = oldest; switch to parsed instants if the format ever varies
        val victim =
            live
                .filter { it.sessionId !in protected }
                .minByOrNull { it.createdAt.orEmpty() }
                ?: throw IllegalStateException("All $maxGatewaySessionsPerDevice gateway sessions are busy")
        repository.closeSession(endpoint.scheme, endpoint.host, endpoint.credential, victim.sessionId)
    }

    private fun requireObserver(): ConnectivityObserver =
        connectivityObserver
            ?: throw IllegalStateException("ChatConnectionHub needs a ConnectivityObserver to create managers")

    private fun allManagers(): List<AcpConnectionManager> =
        entries.values.mapNotNullTo(mutableListOf()) { it.manager } + pendingManagers + browserManagers

    /** Disconnects a listing browser transport (kept for reuse, not closed). */
    private suspend fun hangUpListing(transport: AcpConnectionManager) {
        if (transport.isConnected) {
            // NonCancellable: called from CancellationException catches, where a
            // plain IO hop would re-cancel before disconnect() runs.
            withContext(NonCancellable + Dispatchers.IO) { transport.disconnect() }
        }
    }

    private suspend fun removeAndEvict(chatId: String) {
        val entry = entries.remove(chatId)
        if (entry != null) {
            entry.manager?.let { it.release() }
            republish()
        }
    }

    /** True when the entry's live manager reports Connected; entries without a transport are never connected. */
    private fun Entry.isConnectedNow(): Boolean = manager?.connectionState?.value is AcpConnectionState.Connected

    /**
     * Live connected-entry derivation shared by [warmServers] and
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

    // ===== Session-list listing seam (architecture review candidate 3) =====
    //
    // Listing surfaces (session list, and any future sibling) call these and
    // never hold an AcpConnectionManager: warm-chat borrow-else-own dispatch,
    // capability gating, gateway REST close, and the Room session-table write
    // all live here so socket-steal and warm-reuse bugs concentrate in the hub.

    /** Outcome of a session-list listing attempt. */
    sealed interface ListSessionsResult {
        /**
         * Listing succeeded through a warm chat transport ([viaWarmChat]) or
         * the hub's own browser transport; Room has been replaced.
         */
        data class Listed(
            val sessions: List<SessionSummary>,
            val agentCapabilities: AgentCapabilities?,
            val viaWarmChat: Boolean,
        ) : ListSessionsResult

        /**
         * The transport advertised no session-list capability; nothing was
         * listed or written. Screens keep the old list and show the
         * "not supported" empty state.
         */
        data class Unsupported(
            val agentCapabilities: AgentCapabilities?,
        ) : ListSessionsResult

        /** The transport raised an auth challenge; screens keep the auth UX. */
        data class AuthRequired(
            val agentName: String,
            val authMethods: List<AcpAuthMethodInfo>,
            val challengeMessage: String?,
            val gatewayRuntimeId: String?,
            val viaWarmChat: Boolean,
        ) : ListSessionsResult

        /** Listing failed for a non-auth reason (connect, launch). */
        data class Failed(
            val message: String,
        ) : ListSessionsResult
    }

    /**
     * Lists sessions for [serverId], replacing the Room session table on
     * success. Borrows a warm chat transport when one holds the gateway slot;
     * otherwise runs a transient browser transport (connect → list → hang up).
     * Auth challenges surface as [ListSessionsResult.AuthRequired] with the
     * raising transport retained for [authenticateListing] routing.
     */
    @OptIn(UnstableApi::class)
    suspend fun listSessions(
        serverId: String,
        cwd: String?,
    ): ListSessionsResult {
        val target =
            launchableTargetRepository?.getTarget(serverId)
                ?: return ListSessionsResult.Failed(
                    // Not necessarily "removed": a stored credential this device can no longer
                    // decrypt surfaces here as a missing target too, so name only what is known.
                    "This server is no longer available to the app. Add it again from the server list.",
                )
        val listing = listingSession(serverId)
        val warm = warmManagerFor(serverId)
        return if (warm != null) {
            listViaWarmListing(listing, warm, serverId, cwd)
        } else {
            listViaBrowserListing(listing, target, serverId, cwd)
        }
    }

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
     * Lists through [warm], a hub-tracked chat transport owning the gateway
     * slot. Never disconnects it — the chat owns it.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    private suspend fun listViaWarmListing(
        listing: ListingSession,
        warm: AcpConnectionManager,
        serverId: String,
        cwd: String?,
    ): ListSessionsResult {
        val caps = warm.agentCapabilities.value
        if (caps != null && caps.sessionCapabilities.list == null) {
            return ListSessionsResult.Unsupported(caps)
        }
        return try {
            val sessions = warm.listSessions(cwd = cwd)
            writeListedSessions(serverId, sessions)
            ListSessionsResult.Listed(sessions = sessions, agentCapabilities = caps, viaWarmChat = true)
        } catch (error: AcpAuthenticationRequiredException) {
            listing.authTransport = warm
            authRequiredResult(warm, viaWarmChat = true, error = error)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ListSessionsResult.Failed(formatAcpErrorMessage(error, "Failed to load sessions"))
        }
    }

    /**
     * Lists over the hub-owned browser transport, connecting on demand and
     * hanging up afterwards. On auth the socket stays up for the follow-up
     * authenticateListing call.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    private suspend fun listViaBrowserListing(
        listing: ListingSession,
        target: LaunchableTarget,
        serverId: String,
        cwd: String?,
    ): ListSessionsResult {
        val transport = listing.browserTransport
        val capabilities = transport.agentCapabilities.value
        if (capabilities != null && capabilities.sessionCapabilities.list == null) {
            return ListSessionsResult.Unsupported(capabilities)
        }
        val connected =
            if (transport.isConnected) {
                true
            } else {
                val config =
                    buildListingConfig(target).getOrElse { error ->
                        return ListSessionsResult.Failed(
                            formatAcpErrorMessage(error, "Failed to launch ${target.name}"),
                        )
                    }
                listing.rememberGatewaySession(config)
                try {
                    withContext(Dispatchers.IO) {
                        transport.connectAndInitializeWithoutReconnect(config)
                    } != null
                } catch (error: CancellationException) {
                    // Cancellation can land after the socket opened but before the
                    // connect+initialize returned, stranding a connected transport;
                    // release it before rethrowing.
                    hangUpListing(transport)
                    throw error
                }
            }
        if (!connected) {
            return ListSessionsResult.Failed(
                transport.diagnostics.value.recentErrors
                    .lastOrNull { it.source == "connect" || it.source == "connection" }
                    ?.message
                    ?: "Failed to connect to ${target.name}. Check your connection and try again.",
            )
        }
        val result =
            try {
                val sessions = transport.listSessions(cwd = cwd)
                writeListedSessions(serverId, sessions)
                ListSessionsResult.Listed(
                    sessions = sessions,
                    agentCapabilities = transport.agentCapabilities.value ?: capabilities,
                    viaWarmChat = false,
                )
            } catch (error: AcpAuthenticationRequiredException) {
                // Keep the browser transport connected: the follow-up
                // authenticateListing runs on this same socket.
                listing.authTransport = transport
                authRequiredResult(transport, viaWarmChat = false, error = error)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    // A cancelled listing still releases the browser transport: an
                    // idle connected socket holds the gateway's single-attach slot.
                    hangUpListing(transport)
                    throw error
                }
                ListSessionsResult.Failed(formatAcpErrorMessage(error, "Failed to load sessions"))
            }
        if (result !is ListSessionsResult.AuthRequired) {
            // Idle browser socket's reconnect would steal the gateway's
            // single-attach slot from a chat opened next.
            hangUpListing(transport)
        }
        return result
    }

    /**
     * Routes an auth-method selection to the transport that raised the
     * outstanding challenge for [serverId] (a warm chat transport when the
     * listing borrowed one, else the hub's browser transport).
     */
    suspend fun authenticateListing(
        serverId: String,
        methodId: String,
    ): AcpAuthenticateResult {
        val transport =
            listingSessions[serverId]?.authTransport
                ?: warmManagerFor(serverId)
                ?: return AcpAuthenticateResult.Failure(
                    "Authentication transport is no longer available. Please try again.",
                )
        val result = transport.authenticate(methodId)
        if (result is AcpAuthenticateResult.Success) {
            listingSessions[serverId]?.let {
                it.authTransport = null
            }
        }
        return result
    }

    /**
     * Disconnects the retained listing/auth transport for [serverId]. Part of
     * the auth recovery flows (manual env-var reconnect, gateway env-var
     * restart) that reconnect the raising transport with a new config.
     */
    suspend fun disconnectListingTransport(serverId: String) {
        val transport =
            listingSessions[serverId]?.authTransport
                ?: listingSessions[serverId]?.browserTransport
                ?: return
        withContext(Dispatchers.IO) {
            transport.disconnect()
        }
    }

    /**
     * Connects the retained listing/auth transport for [serverId] to [config]
     * (a manual server reconnect or a freshly handed-off gateway runtime).
     * Returns false when the connection attempt failed.
     */
    suspend fun connectListingTransport(
        serverId: String,
        config: AcpConnectionConfig,
    ): Boolean {
        val transport =
            listingSessions[serverId]?.authTransport
                ?: listingSessions[serverId]?.browserTransport
                ?: return false
        return withContext(Dispatchers.IO) {
            transport.connect(config)
        }
    }

    /** Runs the ACP initialize handshake on the retained listing transport. */
    suspend fun initializeListingTransport(serverId: String): AcpInitializeResult? {
        val transport =
            listingSessions[serverId]?.authTransport
                ?: listingSessions[serverId]?.browserTransport
                ?: return null
        return withContext(Dispatchers.IO) {
            transport.initialize()
        }
    }

    /**
     * Drops the retained browser transport for [serverId] and clears any
     * outstanding auth routing. Call from the listing screen's onCleared.
     * Never tears down a warm chat transport (the chat owns it).
     */
    fun releaseListing(serverId: String) {
        val listing = listingSessions.remove(serverId) ?: return
        // Only the browser transport is hub-owned here — a warm chat transport
        // recorded as authTransport belongs to its chat entry.
        val transport = listing.browserTransport
        if (browserManagers.remove(transport)) {
            revision.value += 1
        }
        transport.release()
    }

    /**
     * Closes [sessionId] on [serverId]: hub-tracked chats go through the
     * tracked close (REST DELETE + transport teardown); cold Room-known
     * sessions close the gateway session directly. Clears the recorded
     * gateway session id in both cases.
     *
     * Returns true when a tracked chat was closed or a known gateway session
     * was deleted, false when nothing was left to close (untracked session the
     * local store does not know, or an already-cleared gateway session id) so
     * the caller can surface that instead of failing silently.
     */
    suspend fun closeSession(
        serverId: String,
        sessionId: String,
        endpoint: GatewayEndpoint,
    ): Boolean {
        val chatId = chatIdFor(serverId, sessionId)
        val sessionRepository = sessionRepository
        if (isTracked(serverId, sessionId)) {
            try {
                close(chatId, endpoint)
            } catch (error: GatewayRequestException) {
                if (error.statusCode != GATEWAY_NOT_FOUND_STATUS) throw error
                // The gateway already forgot the session (restart/reap): the
                // local transport is released, so drop the stale pointer below
                // instead of failing the disconnect the user just asked for.
            }
            sessionRepository?.setGatewaySessionId(serverId, sessionId, null)
            return true
        }
        val gatewaySessionId =
            sessionRepository?.getSession(serverId, sessionId)?.gatewaySessionId
        val repository = gatewayRepository
        if (sessionRepository == null || repository == null || gatewaySessionId == null) {
            return false
        }
        try {
            repository.closeSession(
                scheme = endpoint.scheme,
                host = endpoint.host,
                gatewayCredential = endpoint.credential,
                sessionId = gatewaySessionId,
            )
        } catch (error: GatewayRequestException) {
            if (error.statusCode != GATEWAY_NOT_FOUND_STATUS) throw error
            // Same stale-pointer case without a tracked chat: nothing existed
            // remotely, so clearing the pointer completes the disconnect.
        }
        sessionRepository.setGatewaySessionId(serverId, sessionId, null)
        return true
    }

    /**
     * Best-effort delete: ask the agent to forget the session via ACP
     * `session/delete` (only when it advertises `sessionCapabilities.delete`),
     * then run the [closeSession] path so a live gateway process stops, then
     * drop the local row. The ACP leg swallows its own failures — an agent
     * that rejects the call must not keep the row the user just deleted — but
     * the gateway DELETE still propagates, exactly as [closeSession] does, so
     * a leaked process slot stays visible. Returns false only when nothing in
     * the app knew the session.
     */
    suspend fun deleteSession(
        serverId: String,
        sessionId: String,
        endpoint: GatewayEndpoint?,
    ): Boolean {
        val known =
            isTracked(serverId, sessionId) ||
                sessionRepository?.getSession(serverId, sessionId) != null
        deleteOnAgentBestEffort(serverId, sessionId)
        when {
            endpoint != null -> closeSession(serverId, sessionId, endpoint)
            isTracked(serverId, sessionId) -> close(chatIdFor(serverId, sessionId), endpoint = null)
        }
        sessionRepository?.deleteSession(serverId, sessionId)
        return known
    }

    /**
     * Reconciles recorded gateway leases against the gateway's live session
     * list, dropping pointers the gateway forgot while the app was away
     * (restart/reap) so the session list stops offering disconnects that can
     * only fail. Best-effort: any failure keeps the pointers — fail-safe
     * toward preserving the disconnect kill-switch over hiding a phantom
     * option. Cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reconcileGatewaySessions(
        serverId: String,
        endpoint: GatewayEndpoint,
    ) {
        val repository = gatewayRepository ?: return
        try {
            val live =
                withTimeout(RECONCILE_TIMEOUT_MS) {
                    repository
                        .listGatewaySessions(endpoint.scheme, endpoint.host, endpoint.credential)
                        .map { it.sessionId }
                        .toSet()
                }
            sessionRepository?.clearStaleGatewaySessions(serverId, live)
        } catch (error: CancellationException) {
            // A timeout degrades to "keep the pointers" so a slow gateway
            // cannot cancel the refresh this runs inside; anything else
            // cancels normally.
            if (error !is TimeoutCancellationException) throw error
        } catch (_: Throwable) {
            // Best-effort by contract: stale pointers are harmless (a tap
            // self-heals through the 404 path in closeSession).
        }
    }

    /**
     * Sends `session/delete` over any usable transport for [serverId]: the
     * warm chat transport when one is connected, else the hub's listing
     * transport, connected on demand and hung up afterwards. Quietly skips
     * when capabilities are unknown or `sessionCapabilities.delete` is not
     * advertised, and swallows rejections — this leg is best-effort by
     * contract. Cancellation always propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    @OptIn(UnstableApi::class)
    private suspend fun deleteOnAgentBestEffort(
        serverId: String,
        sessionId: String,
    ) {
        try {
            val warm = warmManagerFor(serverId)
            if (warm != null) {
                if (warm.agentCapabilities.value
                        ?.sessionCapabilities
                        ?.delete != null
                ) {
                    warm.deleteSession(sessionId)
                }
                return
            }
            val target = launchableTargetRepository?.getTarget(serverId) ?: return
            val transport = listingSession(serverId).browserTransport
            try {
                val connected =
                    transport.isConnected ||
                        run {
                            val config = buildListingConfig(target).getOrNull()
                            config != null &&
                                withContext(Dispatchers.IO) {
                                    transport.connectAndInitializeWithoutReconnect(config)
                                } != null
                        }
                if (
                    connected &&
                    transport.agentCapabilities.value
                        ?.sessionCapabilities
                        ?.delete != null
                ) {
                    transport.deleteSession(sessionId)
                }
            } finally {
                hangUpListing(transport)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Best-effort by contract: the caller still removes the local row.
        }
    }

    /** Agent capabilities observed by the last listing for [serverId]. */
    fun listingAgentCapabilities(serverId: String): AgentCapabilities? =
        listingSessions[serverId]
            ?.browserTransport
            ?.agentCapabilities
            ?.value
            ?: entries.values
                .firstOrNull { it.serverId == serverId }
                ?.manager
                ?.agentCapabilities
                ?.value

    /** Live diagnostics of the listing transport for [serverId]. */
    fun listingDiagnostics(serverId: String): ConnectionDiagnostics? =
        listingSessions[serverId]?.browserTransport?.diagnostics?.value

    private suspend fun writeListedSessions(
        serverId: String,
        sessions: List<SessionSummary>,
    ) {
        sessionRepository?.replaceSessions(serverId, sessions)
    }

    private fun authRequiredResult(
        transport: AcpConnectionManager,
        viaWarmChat: Boolean,
        error: AcpAuthenticationRequiredException,
    ): ListSessionsResult.AuthRequired =
        ListSessionsResult.AuthRequired(
            agentName = error.challenge.agentInfo.name,
            authMethods = error.challenge.authMethods,
            challengeMessage = error.challenge.message,
            gatewayRuntimeId = transport.currentConnectionConfig()?.gatewayRuntimeId,
            viaWarmChat = viaWarmChat,
        )

    private fun listingSession(serverId: String): ListingSession =
        listingSessions.getOrPut(serverId) {
            ListingSession(
                serverId = serverId,
                browserTransport = createBrowserManager(scope),
            )
        }

    /**
     * Builds the config for a listing or delete leg on [target].
     *
     * A failure keeps its cause. The launch context already carries the actionable
     * reason ("Gateway credential expired. Please pair this gateway again."), and
     * folding it into `null` replaced that with a locally authored
     * "Failed to launch X" — the user was never told to re-pair.
     */
    private suspend fun buildListingConfig(target: LaunchableTarget): Result<AcpConnectionConfig> =
        when (target) {
            is LaunchableTarget.GatewayAgent -> {
                val gatewaySourceRepository = gatewaySourceRepository
                val gatewayRepository = gatewayRepository
                if (gatewaySourceRepository == null || gatewayRepository == null) {
                    Result.failure(
                        IllegalStateException(
                            "No gateway is configured for ${target.name}. Re-add it from the server list.",
                        ),
                    )
                } else {
                    buildGatewayLaunchContext(
                        gatewayRepository = gatewayRepository,
                        gatewaySourceRepository = gatewaySourceRepository,
                        server = target,
                    ).map { it.config }
                }
            }

            is LaunchableTarget.Manual ->
                Result.success(
                    AcpConnectionConfig(
                        scheme = target.server.scheme,
                        host = target.server.host,
                        preferredAuthMethodId = target.server.preferredAuthMethodId,
                        serverDisplayName = target.name,
                    ),
                )
        }

    /**
     * Recomputes screen presence ([tapTarget], [onScreenChat]) from the entry
     * table (MRU by [Entry.lastFocusedMs]) and bumps the revision that the
     * manager-state observers ([anyConnected], [anyActive], [warmServers],
     * [connectedSessionIds]) rebuild their combinations on.
     */
    private fun republish() {
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
