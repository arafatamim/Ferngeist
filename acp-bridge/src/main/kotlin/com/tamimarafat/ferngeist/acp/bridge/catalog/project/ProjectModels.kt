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
    val agentStatuses: Map<String, GatewayProjectLine.Agent> = emptyMap(),
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
    val listings: Map<String, List<SessionSummary>> = emptyMap(),
    val suppressed: Map<String, Set<String>> = emptyMap(),
    val statuses: Map<String, GatewayProjectLine.Agent> = emptyMap(),
    val missingPaths: Map<String, List<String>> = emptyMap(),
)

/** Only successful authoritative responses can retire deletion suppression. */
internal fun ProjectSnapshot.merge(line: GatewayProjectLine): ProjectSnapshot =
    when (line) {
        is GatewayProjectLine.Agent -> {
            val next =
                copy(
                    statuses = statuses + (line.agentId to line.copy(chats = emptyList())),
                )
            if (line.status != "ok") {
                next
            } else {
                val hidden = suppressed[line.agentId].orEmpty().intersect(line.chats.map { it.sessionId }.toSet())
                next.copy(
                    agents = agents + (line.agentId to line.chats.filter { it.sessionId !in hidden }),
                    suppressed = suppressed + (line.agentId to hidden),
                )
            }
        }
        is GatewayProjectLine.Checkouts -> {
            val missing =
                missingPaths.filterKeys { root -> line.repos.none { it.repo == root } } +
                    line.missingRepos.associateWith { root ->
                        listOf(root) +
                            checkouts
                                .orEmpty()
                                .filter { it.repo == root }
                                .flatMap { it.worktrees.map { tree -> tree.path } }
                    }
            copy(
                checkouts =
                    (line.repos + checkouts.orEmpty().filter { old -> line.repos.none { it.repo == old.repo } })
                        .filter { it.repo !in missing },
                agents = agents.mapValues { (_, chats) -> chats.filter { it.repo !in missing } },
                missingPaths = missing,
            )
        }
    }
