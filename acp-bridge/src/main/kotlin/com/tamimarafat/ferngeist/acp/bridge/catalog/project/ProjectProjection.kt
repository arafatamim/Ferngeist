package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.AcpSessionUpdateMapper
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.isInside
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo

/** Newest chat first; projects with checkouts but no chats after them. */
internal val projectOrder: Comparator<ProjectSummary> =
    compareBy<ProjectSummary> { it.chats.isEmpty() }
        .thenByDescending { it.updatedAt ?: Long.MIN_VALUE }
        .thenBy { projectName(it.repo) }

/**
 * The projects [snapshot] yields for [gateway]. Each reported agent maps to its binding on
 * this phone; chats of agents not bound here are dropped. [created] chats (by target id) the
 * snapshot does not list yet join the project their cwd sits in.
 */
internal fun projectsOf(
    gateway: GatewaySource,
    snapshot: ProjectSnapshot,
    targets: List<LaunchableTarget.GatewayAgent>,
    created: Map<String, List<SessionSummary>> = emptyMap(),
): List<ProjectSummary> {
    // Duplicate bindings share agent identity; creation order determines the stable owner.
    val bindingByAgent =
        targets
            .filter { it.gatewaySource.id == gateway.id }
            .groupBy { it.binding.agentId }
            .mapValues { (_, bound) -> bound.minWith(compareBy({ it.binding.createdAt }, { it.id })) }
    val chatsByRepo =
        snapshot.agents
            .flatMap { (agentId, chats) ->
                val target = bindingByAgent[agentId] ?: return@flatMap emptyList()
                chats.filter { it.repo !in snapshot.missingPaths }.map { chat -> chat.toProjectChat(target) }
            }.let { listed ->
                listed +
                    createdChats(
                        created,
                        targets.filter { it.gatewaySource.id == gateway.id },
                        listed,
                        snapshot.checkouts.orEmpty(),
                    )
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

/**
 * [created] chats not in [listed], placed by cwd: inside a reported worktree, else inside a
 * reported repo. A chat outside both stays out of the projects.
 */
private fun createdChats(
    created: Map<String, List<SessionSummary>>,
    targets: List<LaunchableTarget.GatewayAgent>,
    listed: List<Pair<String, ProjectChat>>,
    checkouts: List<GatewayProjectRepo>,
): List<Pair<String, ProjectChat>> {
    // A duplicate binding of the same agent lists the same chat, so agentId identifies it.
    val known = listed.map { (_, chat) -> chat.target.binding.agentId to chat.summary.id }.toSet()
    val repos = (checkouts.map { it.repo } + listed.map { it.first }).distinct()
    return created.flatMap { (serverId, chats) ->
        val target = targets.firstOrNull { it.id == serverId } ?: return@flatMap emptyList()
        chats
            .filter { (target.binding.agentId to it.id) !in known }
            .mapNotNull { summary ->
                val cwd = summary.cwd ?: return@mapNotNull null
                val worktree =
                    checkouts
                        .flatMap { repo -> repo.worktrees.map { repo.repo to it.path } }
                        .filter { (_, path) -> isInside(cwd, path) }
                        .maxByOrNull { (_, path) -> path.length }
                val repo =
                    worktree?.first ?: repos.filter { isInside(cwd, it) }.maxByOrNull { it.length }
                        ?: return@mapNotNull null
                repo to ProjectChat(summary.copy(serverId = target.id), target, worktree?.second)
            }
    }
}

internal fun GatewayProjectChat.toProjectChat(target: LaunchableTarget.GatewayAgent): Pair<String, ProjectChat> =
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
