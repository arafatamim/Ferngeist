package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo
import kotlinx.serialization.Serializable

/** What the home and project screens read: the projects every snapshot yields, once hydrated. */
data class ProjectCatalogState(
    val projects: List<ProjectSummary> = emptyList(),
    val isReady: Boolean = false,
)

/** One repo on one gateway, with the chats bound agents reported in it. */
data class ProjectSummary(
    val gateway: GatewaySource,
    val repo: String,
    val chats: List<ProjectChat>,
    val checkout: GatewayProjectRepo?,
    val updatedAt: Long?,
)

data class ProjectChat(
    val summary: SessionSummary,
    val target: LaunchableTarget.GatewayAgent,
    val worktree: String?,
)

/** The last `/v1/projects` result for one gateway: each agent's last `ok` chats and the last checkouts. */
@Serializable
internal data class ProjectSnapshot(
    val agents: Map<String, List<GatewayProjectChat>> = emptyMap(),
    val checkouts: List<GatewayProjectRepo>? = null,
)

/** Only an `ok` agent replaces its part; a failed or unauthenticated agent keeps what it last reported. */
internal fun ProjectSnapshot.merge(line: GatewayProjectLine): ProjectSnapshot =
    when (line) {
        is GatewayProjectLine.Agent ->
            if (line.status == AGENT_STATUS_OK) copy(agents = agents + (line.agentId to line.chats)) else this
        is GatewayProjectLine.Checkouts -> copy(checkouts = line.repos)
    }

private const val AGENT_STATUS_OK = "ok"
