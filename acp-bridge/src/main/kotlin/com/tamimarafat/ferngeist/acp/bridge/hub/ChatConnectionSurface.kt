package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * What a chat connection reports about itself when it registers: the gateway binding it was
 * opened with, live probes of its transport, and the manager it owns.
 */
data class ChatRegistration(
    val gatewaySessionId: String?,
    val gatewaySourceId: String,
    val agentId: String,
    val isConnected: () -> Boolean,
    val isStreaming: () -> Boolean,
    val manager: AcpConnection? = null,
    val snapshot: StateFlow<SessionSnapshot>? = null,
)

/**
 * The [ChatConnectionHub] operations a chat session depends on: attendance
 * (register/abandon/refresh) and the snapshot paint cache.
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
    fun acquireChatManager(): AcpConnection

    /** Releases an acquired manager that never registered (failed spawn, cleared screen). */
    fun abandon(manager: AcpConnection)

    /** The live transport for a tracked chat, or null when untracked/cold. */
    fun managerFor(chatId: String): AcpConnection?

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
    fun register(
        serverId: String,
        sessionId: String,
        registration: ChatRegistration,
    ): String

    /** Recomputes screen presence from the entry table; call after transport changes. */
    fun refresh()

    /**
     * Reads the agent's current title for one session over a live listing
     * transport, or null when it is not knowable.
     *
     * An open chat calls this once its first response finishes, because some
     * agents generate a title server-side but never push it over
     * `session_info_update` — it only shows up in a `session/list` response.
     *
     * Read-only: unlike the listing seam, this never writes the session table.
     * Null means "no title to apply" (agent cannot list, no live transport, the
     * request failed, or the session has no title yet) — never an error.
     */
    suspend fun resolveSessionTitle(
        serverId: String,
        sessionId: String,
        cwd: String?,
    ): String?
}
