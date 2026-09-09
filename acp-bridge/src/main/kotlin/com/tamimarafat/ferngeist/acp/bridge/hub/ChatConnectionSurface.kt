package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot

/**
 * The [ChatConnectionHub] operations a chat session depends on: attendance
 * (register/abandon/refresh), the snapshot paint cache, and gateway-slot
 * capacity checks.
 *
 * Extracted behind this seam so the facade's attendance behavior is testable
 * against a recording fake without constructing a live hub, and so
 * [com.tamimarafat.ferngeist.acp.bridge.facade.AcpChatSessionFacade] and its
 * factory depend on the narrow surface they use instead of the full hub
 * (presence flows, listing seam, aggregates all stay hub-side).
 *
 * Implemented only by [ChatConnectionHub] in production; the interface exists
 * for the test seam, not for polymorphism.
 */
interface ChatConnectionSurface {
    /** Creates an app-scoped chat manager, tracked as pending until [register] promotes it. */
    fun acquireChatManager(): AcpConnectionManager

    /** Releases an acquired manager that never registered (failed spawn, cleared screen). */
    fun abandon(manager: AcpConnectionManager)

    /** The live transport for a tracked chat, or null when untracked/cold. */
    fun managerFor(chatId: String): AcpConnectionManager?

    /** Last rendered snapshot for a chat; survives eviction/screen-close so reopen paints instantly. */
    fun snapshotFor(chatId: String): ChatSessionSnapshot?

    /** Stashes the latest rendered snapshot; eldest-evicted past the cache cap. */
    fun storeSnapshot(
        chatId: String,
        snapshot: ChatSessionSnapshot,
    )

    /**
     * Tracks (or re-attaches) a chat connection, evicting pooled idle entries
     * while at capacity. Returns the stable chat id.
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
    ): String

    /** True when this agent on this source already holds a live (connected) gateway session. */
    fun hasLiveGatewaySession(
        gatewaySourceId: String,
        agentId: String,
    ): Boolean

    /** Frees a device gateway-session slot before a spawn when the device cap is reached. */
    suspend fun ensureGatewayCapacity(endpoint: GatewayEndpoint)

    /** Recomputes screen presence from the entry table; call after transport changes. */
    fun refresh()
}
