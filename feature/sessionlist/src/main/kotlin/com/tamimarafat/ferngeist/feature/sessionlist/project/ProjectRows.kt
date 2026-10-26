package com.tamimarafat.ferngeist.feature.sessionlist.project

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/** The project's worktrees as the toolbar's selector reads them, from the snapshot's checkouts. */
internal fun ProjectSummary.worktrees(): List<GatewayWorktree> =
    checkout?.worktrees.orEmpty().map {
        // The snapshot carries no base commit or creation time; the selector shows no age for them.
        GatewayWorktree(
            id = it.id,
            repo = repo,
            path = it.path,
            branch = it.branch.orEmpty(),
            baseCommit = "",
            createdAt = "",
            ahead = it.ahead,
            dirty = it.dirty,
        )
    }

/** The project's [chats] in [scope]; a chat's `worktree` is null in the main checkout. */
internal fun chatsInScope(
    chats: List<ProjectChat>,
    scope: WorktreeScope,
    agentId: String? = null,
): List<ProjectChat> =
    when (scope) {
        WorktreeScope.All -> chats
        WorktreeScope.Main -> chats.filter { it.worktree == null }
        is WorktreeScope.Worktree -> chats.filter { chat -> chat.worktree?.let { isSameCwd(it, scope.path) } == true }
    }.filter { agentId == null || it.target.id == agentId }

/**
 * The agent a new chat in [scope] starts with: the one of the newest chat in that checkout, else
 * in the project, else the alphabetically first of [targets].
 */
internal fun defaultAgent(
    chats: List<ProjectChat>,
    scope: WorktreeScope,
    targets: List<LaunchableTarget.GatewayAgent>,
): LaunchableTarget.GatewayAgent? {
    val bound = targets.associateBy { it.id }

    fun newestIn(candidates: List<ProjectChat>) =
        candidates
            .sortedByDescending { it.summary.updatedAt ?: Long.MIN_VALUE }
            .firstNotNullOfOrNull { bound[it.target.id] }
    return newestIn(chatsInScope(chats, scope)) ?: newestIn(chats) ?: targets.minByOrNull { it.name.lowercase() }
}

/** The new-chat sheet's order: [default] first, then the other [targets] alphabetically. */
internal fun agentsInSheetOrder(
    default: LaunchableTarget.GatewayAgent?,
    targets: List<LaunchableTarget.GatewayAgent>,
): List<LaunchableTarget.GatewayAgent> =
    listOfNotNull(default) + targets.filter { it.id != default?.id }.sortedBy { it.name.lowercase() }

/** Identifies a chat across agents: two agents may report the same session id. */
internal val ProjectChat.key: String get() = "${target.id}/${summary.id}"

/** The long-press actions one project row offers; null ones are not shown. */
internal class ProjectChatActions(
    val onClose: (() -> Unit)?,
    val onRemoveWorktree: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
)
