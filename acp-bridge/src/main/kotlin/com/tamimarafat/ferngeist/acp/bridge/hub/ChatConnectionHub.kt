package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.gateway.GatewaySessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Gateway endpoint triple needed for session-scoped REST calls (list/close). */
data class GatewayEndpoint(
    val scheme: String,
    val host: String,
    val credential: String,
)

/** Public snapshot of one tracked chat connection. Head of [ChatConnectionHub.liveChats] = most recently focused. */
data class LiveChatState(
    val chatId: String,
    val serverId: String,
    val sessionId: String,
    val gatewaySessionId: String?,
    val gatewaySourceId: String,
    val agentId: String,
    val connected: Boolean,
    val streaming: Boolean,
)

/**
 * Tracks every open chat's connection and enforces the hot-cap policy:
 * at most [maxHotConnections] hot transports (LRU by focus, never evicting a
 * streaming chat), and gateway capacity ensured before any new-session spawn.
 *
 * Eviction removes the entry from tracking after running its `onEvict`
 * callback; the gateway keeps the resilient session alive, so the chat can
 * re-register on return.
 */
class ChatConnectionHub(
    private val gatewayRepository: GatewaySessionRepository?,
    private val maxHotConnections: Int = 3,
    private val maxGatewaySessionsPerDevice: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
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
        val onEvict: suspend () -> Unit,
        var lastFocusedMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val _liveChats = MutableStateFlow<List<LiveChatState>>(emptyList())

    /** Focus-recency-sorted snapshots; head is the most recently focused chat. */
    val liveChats: StateFlow<List<LiveChatState>> = _liveChats.asStateFlow()

    /**
     * Registers (or refreshes focus of) a chat connection. Evicts LRU idle
     * entries while at capacity. Returns the stable chat id.
     */
    suspend fun register(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
        gatewaySourceId: String,
        agentId: String,
        isConnected: () -> Boolean,
        isStreaming: () -> Boolean,
        onEvict: suspend () -> Unit,
    ): String {
        val chatId = "$serverId/$sessionId"
        entries[chatId]?.let { existing ->
            existing.lastFocusedMs = clock()
            publish()
            return chatId
        }
        while (entries.size >= maxHotConnections) {
            val victim =
                entries.values
                    .filter { !it.isStreaming() }
                    .minByOrNull { it.lastFocusedMs }
                    ?: break // everything streaming: allow the over-cap registration
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
                onEvict = onEvict,
                lastFocusedMs = clock(),
            )
        publish()
        return chatId
    }

    /** Marks a chat focused: bumps LRU recency so it survives the next eviction round. */
    fun focus(chatId: String) {
        entries[chatId]?.let { entry ->
            entry.lastFocusedMs = clock()
            publish()
        }
    }

    /** Drops tracking without closing anything (owner cleared, back stack popped). */
    fun unregister(chatId: String) {
        if (entries.remove(chatId) != null) publish()
    }

    /**
     * Explicit close: DELETE the gateway session first (stops the agent
     * process), then run the local evict callback. Missing endpoint or
     * gateway session skips the remote call.
     */
    suspend fun close(
        chatId: String,
        endpoint: GatewayEndpoint? = null,
    ) {
        val entry = entries.remove(chatId) ?: return
        val gatewaySessionId = entry.gatewaySessionId
        if (endpoint != null && gatewaySessionId != null && gatewayRepository != null) {
            gatewayRepository.closeSession(endpoint.scheme, endpoint.host, endpoint.credential, gatewaySessionId)
        }
        entry.onEvict()
        publish()
    }

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

    private suspend fun removeAndEvict(chatId: String) {
        val entry = entries.remove(chatId)
        if (entry != null) {
            entry.onEvict()
            publish()
        }
    }

    private fun publish() {
        _liveChats.value =
            entries.values
                .sortedByDescending { it.lastFocusedMs }
                .map { entry ->
                    LiveChatState(
                        chatId = entry.chatId,
                        serverId = entry.serverId,
                        sessionId = entry.sessionId,
                        gatewaySessionId = entry.gatewaySessionId,
                        gatewaySourceId = entry.gatewaySourceId,
                        agentId = entry.agentId,
                        connected = entry.isConnected(),
                        streaming = entry.isStreaming(),
                    )
                }
    }

    private companion object {
        const val STATUS_ACTIVE = "active"
    }
}
