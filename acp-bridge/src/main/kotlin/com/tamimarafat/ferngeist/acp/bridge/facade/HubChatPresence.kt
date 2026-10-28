package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.GatewayEndpoint
import com.tamimarafat.ferngeist.core.model.ChatSessionPresence
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.WorktreeRef
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hub-backed [ChatSessionPresence]: the chat feature's one window onto the process-wide
 * [ChatConnectionHub] and onto the gateway endpoints a close needs.
 *
 * Each call forwards to the hub member of the same name; the endpoint lookup a close needs stays
 * here, so the feature layer never builds a gateway endpoint itself.
 */
class HubChatPresence(
    private val hub: ChatConnectionHub,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val gatewayRepository: GatewayRepository,
    private val onChatCreated: (serverId: String, summary: SessionSummary) -> Unit = { _, _ -> },
    override val turnStatuses: StateFlow<Map<String, TurnStatus>> = hub.turnStatuses,
) : ChatSessionPresence {
    override val warmServers: StateFlow<Set<String>> = hub.warmServers

    override fun connectedSessionIds(serverId: String): Flow<Set<String>> = hub.connectedSessionIds(serverId)

    /**
     * The gateway's worktrees, or null when the target is not gateway-backed or the listing
     * fails. Their repos are what fold a chat's worktree directory into its project.
     */
    override suspend fun worktrees(serverId: String): List<WorktreeRef>? {
        val endpoint = gatewayEndpoint(serverId) ?: return null
        return try {
            gatewayRepository
                .listWorktrees(endpoint.scheme, endpoint.host, endpoint.credential)
                .map {
                    WorktreeRef(
                        repo = it.repo,
                        path = it.path,
                        branch = it.branch,
                        ahead = it.ahead,
                        behind = it.behind,
                        dirty = it.dirty,
                    )
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    override fun isStreaming(
        serverId: String,
        sessionId: String,
    ): Boolean = hub.isStreaming(serverId, sessionId)

    override fun screenOpened(
        serverId: String,
        sessionId: String,
        cwd: String,
    ) {
        hub.chatScreenOpened(serverId, sessionId, cwd)
    }

    override fun chatCreated(
        serverId: String,
        summary: SessionSummary,
    ) = onChatCreated(serverId, summary)

    override fun screenClosed(
        serverId: String,
        sessionId: String,
    ) {
        hub.chatScreenClosed(serverId, sessionId)
    }

    override suspend fun isGatewayBacked(serverId: String): Boolean = gatewayEndpoint(serverId) != null

    override suspend fun closeSession(
        serverId: String,
        sessionId: String,
    ): Boolean {
        val endpoint = gatewayEndpoint(serverId) ?: return false
        return hub.closeSession(serverId, sessionId, endpoint)
    }

    /** Gateway REST endpoint for [serverId], or null when the target is not gateway-backed. */
    private suspend fun gatewayEndpoint(serverId: String): GatewayEndpoint? =
        (launchableTargetRepository.getTarget(serverId) as? LaunchableTarget.GatewayAgent)?.let { target ->
            GatewayEndpoint(
                scheme = target.gatewaySource.scheme,
                host = target.gatewaySource.host,
                credential = target.gatewaySource.gatewayCredential,
            )
        }
}
