package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.isInside

/**
 * [target]'s chats: its gateway snapshot's part when the snapshot has one, else its [stored]
 * listing, plus [created] chats neither lists yet. Newest first.
 */
internal fun chatsOf(
    target: LaunchableTarget?,
    snapshots: Map<String, ProjectSnapshot>,
    stored: List<SessionSummary>,
    created: List<SessionSummary>,
): List<SessionSummary> {
    val listed = target?.let { snapshotChats(it, snapshots) } ?: stored
    val listedIds = listed.map { it.id }.toSet()
    return visibleChats(target, snapshots, listed + created.filter { it.id !in listedIds })
        .sortedByDescending { it.updatedAt ?: 0L }
}

/** [target]'s part of its gateway snapshot, or null when it is direct or the snapshot lacks it. */
internal fun snapshotChats(
    target: LaunchableTarget,
    snapshots: Map<String, ProjectSnapshot>,
): List<SessionSummary>? {
    val fallback = snapshots["agent-${target.id}"]?.listings?.get(target.id)
    if (target !is LaunchableTarget.GatewayAgent) return fallback
    val snapshot = snapshots[target.gatewaySource.id]
    val hidden = snapshot?.suppressed?.get(target.binding.agentId).orEmpty()
    val chats =
        fallback ?: snapshot?.agents?.get(target.binding.agentId)?.map { it.toProjectChat(target).second.summary }
    return chats?.filterNot { it.id in hidden }?.let { visibleChats(target, snapshots, it) }
}

/**
 * The newest [limit] chats across [targets]: snapshot parts where they exist, [stored] rows for
 * every other target, plus [created] chats (by target id) not listed yet.
 */
internal fun recentChatsOf(
    targets: List<LaunchableTarget>,
    snapshots: Map<String, ProjectSnapshot>,
    stored: List<SessionSummary>,
    created: Map<String, List<SessionSummary>>,
    limit: Int,
): List<SessionSummary> {
    val fromSnapshots =
        targets
            .mapNotNull { target ->
                snapshotChats(target, snapshots)?.let { target.id to it }
            }.toMap()
    val storedRows =
        stored
            .filter { row ->
                row.serverId !in fromSnapshots && targets.any { it.id == row.serverId }
            }.groupBy { it.serverId }
    val serverIds = fromSnapshots.keys + storedRows.keys + created.keys
    return serverIds
        .flatMap { serverId ->
            val listed = fromSnapshots[serverId] ?: storedRows[serverId].orEmpty()
            val listedIds = listed.map { it.id }.toSet()
            visibleChats(
                targets.firstOrNull { it.id == serverId },
                snapshots,
                listed +
                    created[serverId].orEmpty().filter { it.id !in listedIds }.map {
                        it.copy(
                            serverId = serverId,
                        )
                    },
            )
        }.sortedByDescending { it.updatedAt ?: 0L }
        .take(limit)
}

/** Confirmed filesystem removals remain authoritative across stale per-agent listings. */
internal fun visibleChats(
    target: LaunchableTarget?,
    snapshots: Map<String, ProjectSnapshot>,
    chats: List<SessionSummary>,
): List<SessionSummary> {
    if (target !is LaunchableTarget.GatewayAgent) return chats
    val snapshot = snapshots[target.gatewaySource.id] ?: return chats
    val paths = snapshot.missingPaths.values.flatten()
    val hidden = snapshot.suppressed[target.binding.agentId].orEmpty()
    return chats.filterNot { chat ->
        chat.id in hidden || chat.cwd?.let { cwd -> paths.any { isInside(cwd, it) } } == true
    }
}
