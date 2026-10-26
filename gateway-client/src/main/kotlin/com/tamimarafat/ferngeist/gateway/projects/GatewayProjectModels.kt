package com.tamimarafat.ferngeist.gateway.projects

import com.agentclientprotocol.model.SessionCapabilities
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface GatewayProjectLine {
    @Serializable
    @SerialName("agent")
    data class Agent(
        val agentId: String,
        val status: String,
        val error: String? = null,
        val launchAvailable: Boolean? = null,
        /** The agent's own initialize answer, passed through by the gateway; null unless the listing succeeded. */
        val sessionCapabilities: SessionCapabilities? = null,
        val chats: List<GatewayProjectChat> = emptyList(),
        val running: List<String> = emptyList(),
    ) : GatewayProjectLine

    @Serializable
    @SerialName("checkouts")
    data class Checkouts(
        val repos: List<GatewayProjectRepo> = emptyList(),
        val missingRepos: List<String> = emptyList(),
    ) : GatewayProjectLine
}

@Serializable
data class GatewayProjectChat(
    val sessionId: String,
    val title: String? = null,
    val cwd: String? = null,
    val updatedAt: String? = null,
    val repo: String,
    val worktree: String? = null,
)

@Serializable
data class GatewayProjectRepo(
    val repo: String,
    val branch: String? = null,
    val worktrees: List<GatewayProjectWorktree> = emptyList(),
)

@Serializable
data class GatewayProjectWorktree(
    val id: String,
    val path: String,
    val branch: String? = null,
    val ahead: Int? = null,
    val dirty: Boolean? = null,
)
