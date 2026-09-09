package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.CopyOnWriteArrayList

/** Gateway endpoint triple needed for session-scoped REST calls (list/close). */
data class GatewayEndpoint(
    val scheme: String,
    val host: String,
    val credential: String,
)

/**
 * Single owner of every ACP manager lifetime *and* chat presence in the process.
 *
 * Chat managers are created via [acquireChatManager] (tracked as pending until
 * [register] promotes them into a hot entry) and torn down only here — evict,
 * explicit close, and abandon all run the same disconnect-then-close pairing,
 * so callers can never half-release a transport. Browser (listing) managers are
 * created via [createBrowserManager] and auto-released with their owner scope.
 *
 * Presence: each hot entry is keyed by `"$serverId/$sessionId"` and records
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
) {
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
        val screenOpen: Boolean,
        val cwd: String,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val snapshots = LinkedHashMap<String, ChatSessionSnapshot>()
    private val pendingManagers = CopyOnWriteArrayList<AcpConnectionManager>()
    private val browserManagers = CopyOnWriteArrayList<AcpConnectionManager>()
    private val revision = MutableStateFlow(0L)

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

    /** True when any tracked manager is connected or mid-connect. */
    val anyActive: StateFlow<Boolean> =
        revision
            .flatMapLatest {
                val managers = allManagers()
                if (managers.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(managers.map { it.connectionState }) { states ->
                        states.any { it is AcpConnectionState.Connected || it is AcpConnectionState.Connecting }
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Creates an app-scoped chat manager, tracked as pending until [register]
     * promotes it into a hot entry. A spawn that fails before attach stays
     * tracked (visible in the aggregates) until [abandon] releases it.
     */
    fun acquireChatManager(): AcpConnectionManager =
        AcpConnectionManager(requireObserver(), gatewayRepository, scope).also { manager ->
            pendingManagers.addIfAbsent(manager)
            revision.value += 1
        }

    /**
     * Releases an [acquireChatManager] manager that never registered (failed
     * spawn, cleared screen). No-op for managers already promoted into entries.
     */
    fun abandon(manager: AcpConnectionManager) {
        if (pendingManagers.remove(manager)) {
            revision.value += 1
            teardown(manager)
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
                teardown(manager)
            }
        }

    /**
     * Number of tracked entries plus pending and browser managers — test
     * visibility for lifecycle assertions.
     */
    fun trackedCount(): Int = entries.size + pendingManagers.size + browserManagers.size

    /**
     * Registers (or re-attaches the transport of) a chat connection. Evicts
     * pooled idle entries while at capacity. Returns the stable chat id.
     *
     * When the key already exists only transport facts are merged — recency is
     * never bumped, so reconnects/re-attaches cannot steal the screen head.
     * A fresh entry records the registration time as its recency.
     *
     * [manager] is the chat's connection, promoted from pending into the hot
     * entry. Eviction and explicit close tear it down here; callers never do.
     */
    suspend fun register(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
        gatewaySourceId: String,
        agentId: String,
        isConnected: () -> Boolean,
        isStreaming: () -> Boolean,
        manager: AcpConnectionManager? = null,
    ): String {
        val chatId = "$serverId/$sessionId"
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
                    ?: break // everything streaming or on screen: allow the over-cap registration
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
                screenOpen = false,
                cwd = "",
            )
        republish()
        return chatId
    }

    /** The live transport for a tracked chat, or null when untracked/cold. */
    fun managerFor(chatId: String): AcpConnectionManager? = entries[chatId]?.manager

    /** Last rendered snapshot for a chat; survives evict/screen-close so reopen paints instantly. Dropped only on close. */
    fun snapshotFor(chatId: String): ChatSessionSnapshot? = snapshots[chatId]

    /** Stashes the latest rendered snapshot; eldest-evicted past the snapshot cap. Safe before any entry exists. */
    fun storeSnapshot(
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
        val chatId = "$serverId/$sessionId"
        val existing = entries[chatId]
        if (existing != null) {
            entries[chatId] =
                existing.copy(
                    screenOpen = true,
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
                    screenOpen = true,
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
        val chatId = "$serverId/$sessionId"
        entries[chatId]?.let { existing ->
            if (existing.manager == null) {
                entries.remove(chatId)
            } else {
                entries[chatId] = existing.copy(screenOpen = false)
            }
            republish()
        }
    }

    /** True when an entry exists for this server/session composite key. */
    fun isTracked(
        serverId: String,
        sessionId: String,
    ): Boolean = entries.containsKey("$serverId/$sessionId")

    /** True when this agent on this source already holds a live (connected) gateway session. */
    fun hasLiveGatewaySession(
        gatewaySourceId: String,
        agentId: String,
    ): Boolean =
        entries.values.any {
            it.gatewaySourceId == gatewaySourceId && it.agentId == agentId && it.isConnected()
        }

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
    fun refresh() {
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
            entry.manager?.let { teardown(it) }
            republish()
        }
    }

    /**
     * Frees a device gateway-session slot before a spawn when the device cap
     * is reached: closes the oldest active session not owned by a tracked
     * chat. Throws when every session is spoken for.
     */
    suspend fun ensureGatewayCapacity(endpoint: GatewayEndpoint) {
        val repository = gatewayRepository ?: throw IllegalStateException("No gateway repository available")
        val active =
            repository
                .listGatewaySessions(endpoint.scheme, endpoint.host, endpoint.credential)
                .filter { it.status == STATUS_ACTIVE }
        if (active.size < maxGatewaySessionsPerDevice) return
        val protected = gatewaySessionIds()
        // ponytail: createdAt is an ISO-8601 string, so lexicographic min = oldest; switch to parsed instants if the format ever varies
        val victim =
            active
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

    private fun teardown(manager: AcpConnectionManager) {
        manager.disconnect()
        manager.close()
    }

    private suspend fun removeAndEvict(chatId: String) {
        val entry = entries.remove(chatId)
        if (entry != null) {
            entry.manager?.let { teardown(it) }
            republish()
        }
    }

    /** True when the entry's live manager reports Connected; entries without a transport are never connected. */
    private fun Entry.isConnectedNow(): Boolean =
        manager?.connectionState?.value is AcpConnectionState.Connected

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

    private companion object {
        const val STATUS_ACTIVE = "active"
    }
}
