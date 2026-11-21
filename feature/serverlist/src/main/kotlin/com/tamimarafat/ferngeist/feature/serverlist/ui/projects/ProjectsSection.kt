package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.projectRecencyOrder
import com.tamimarafat.ferngeist.core.common.ui.status.isActive
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnStatus

/** Live projects lead in both views; each group keeps newest known activity first. */
internal fun projectsByActivity(
    projects: List<ProjectSummary>,
    turnStatuses: Map<String, TurnActivity>,
): List<ProjectSummary> {
    val (live, idle) = projects.partition { it.hasActiveTurn(turnStatuses) }
    return live.sortedWith(projectRecencyOrder) + idle.sortedWith(projectRecencyOrder)
}

/**
 * The home's collapsed Projects view: from activity-ordered projects, keep in-flight ones or
 * known activity strictly within 48 hours, capped at five.
 */
internal fun collapsedProjects(
    projects: List<ProjectSummary>,
    now: Long,
    turnStatuses: Map<String, TurnActivity>,
): List<ProjectSummary> =
    projects
        .filter {
            it.hasActiveTurn(turnStatuses) ||
                it.updatedAt?.let { updated -> now - updated < RECENT_PROJECT_WINDOW_MS } == true
        }.take(MAX_VISIBLE_PROJECTS)

internal fun ProjectSummary.hasLiveChat(chatKeys: Set<String>): Boolean = chats.any { it.chatKey in chatKeys }

/** One agent the card names, with the state the project's connected chats gave it. */
internal data class ConnectedAgent(
    val target: LaunchableTarget.GatewayAgent,
    val status: TurnStatus,
)

/** The agents with a connected session in this project: the only ones its card names. */
internal fun ProjectSummary.connectedAgents(
    connectedChatKeys: Set<String>,
    turnStatuses: Map<String, TurnActivity>,
): List<ConnectedAgent> =
    chats
        .filter { it.chatKey in connectedChatKeys }
        .groupBy { it.target.id }
        .map { (_, connected) ->
            ConnectedAgent(
                target = connected.first().target,
                status = connected.map { turnStatuses[it.chatKey]?.status ?: TurnStatus.UNKNOWN }.aggregateTurnStatus(),
            )
        }

/** In flight means working or awaiting attention, never a terminal outcome. */
internal fun ProjectSummary.hasActiveTurn(turnStatuses: Map<String, TurnActivity>): Boolean =
    chats.any { turnStatuses[it.chatKey]?.status?.isActive == true }

/** The chats' turn activities, matched by full chat identity; missing entries rest unknown. */
internal fun ProjectSummary.chatActivities(turnStatuses: Map<String, TurnActivity>): List<TurnActivity> =
    chats.map { turnStatuses[it.chatKey] ?: TurnActivity() }

internal val ProjectChat.chatKey: String get() = "${target.id}/${summary.id}"

private const val RECENT_PROJECT_WINDOW_MS = 48L * 60 * 60 * 1000
private const val MAX_VISIBLE_PROJECTS = 5
