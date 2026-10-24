package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat

/** Newest chat first; projects with checkouts but no chats after them. */
internal val projectOrder: Comparator<ProjectSummary> =
    compareBy<ProjectSummary> { it.chats.isEmpty() }
        .thenByDescending { it.updatedAt ?: Long.MIN_VALUE }
        .thenBy { projectName(it.repo) }

/**
 * The projects [snapshot] yields for [gateway]. Each reported agent maps to its binding on
 * this phone; chats of agents not bound here are dropped.
 */
internal fun projectsOf(
    gateway: GatewaySource,
    snapshot: ProjectSnapshot,
    targets: List<LaunchableTarget.GatewayAgent>,
): List<ProjectSummary> {
    // shortcut: bindings carry no creation time, so the first in repository order (by name)
    // stands in for the oldest; add createdAt with the 17→18 migration.
    val bindingByAgent =
        targets
            .filter { it.gatewaySource.id == gateway.id }
            .groupBy { it.binding.agentId }
            .mapValues { (_, bound) -> bound.first() }
    val chatsByRepo =
        snapshot.agents
            .flatMap { (agentId, chats) ->
                val target = bindingByAgent[agentId] ?: return@flatMap emptyList()
                chats.map { chat -> chat.toProjectChat(target) }
            }.groupBy { (repo, _) -> repo }
    val checkouts = snapshot.checkouts.orEmpty().associateBy { it.repo }
    return (chatsByRepo.keys + checkouts.keys).map { repo ->
        val chats = chatsByRepo[repo].orEmpty().map { it.second }.sortedByDescending { it.summary.updatedAt ?: 0L }
        ProjectSummary(
            gateway = gateway,
            repo = repo,
            chats = chats,
            checkout = checkouts[repo],
            updatedAt = chats.firstNotNullOfOrNull { it.summary.updatedAt },
        )
    }
}

private fun GatewayProjectChat.toProjectChat(target: LaunchableTarget.GatewayAgent): Pair<String, ProjectChat> =
    repo to
        ProjectChat(
            summary =
                SessionSummary(
                    id = sessionId,
                    title = title,
                    cwd = cwd,
                    updatedAt = AcpSessionUpdateMapper.parseIsoOrMillis(updatedAt),
                    serverId = target.id,
                ),
            target = target,
            worktree = worktree,
        )
