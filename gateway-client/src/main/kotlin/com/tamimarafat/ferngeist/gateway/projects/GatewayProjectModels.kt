package com.tamimarafat.ferngeist.gateway.projects

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
        val chats: List<GatewayProjectChat> = emptyList(),
    ) : GatewayProjectLine

    @Serializable
    @SerialName("checkouts")
    data class Checkouts(
        val repos: List<GatewayProjectRepo> = emptyList(),
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
