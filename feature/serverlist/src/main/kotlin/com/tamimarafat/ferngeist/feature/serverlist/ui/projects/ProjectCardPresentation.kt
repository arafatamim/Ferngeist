package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.CardTone
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.cardTone

internal enum class ProjectCardVariant { HERO, TILE }

internal data class ProjectCardPresentation(
    val activity: TurnActivity,
    val working: Int,
    val variant: ProjectCardVariant,
    val tone: CardTone,
)

/**
 * A project's card, by the Agents page's rules: working, unread-finished, attention or error
 * chats promote it to a full-width hero, and otherwise a live chat on this device keeps its tone
 * primary. The tint itself is [cardTone]'s, shared with the agents cards.
 */
internal fun projectCardPresentation(
    project: ProjectSummary,
    turnStatuses: Map<String, TurnActivity>,
    connectedChatKeys: Set<String>,
): ProjectCardPresentation {
    val chats = project.chatActivities(turnStatuses)
    val working = chats.count { it.status == TurnStatus.WORKING }
    val unread = chats.count { it.status == TurnStatus.FINISHED }
    val status = chats.aggregateTurnActivity()
    val live = project.hasLiveChat(connectedChatKeys)
    val variant =
        when {
            working > 0 ||
                unread > 0 ||
                status.status == TurnStatus.NEEDS_ATTENTION ||
                status.status == TurnStatus.ERROR -> ProjectCardVariant.HERO
            else -> ProjectCardVariant.TILE
        }
    return ProjectCardPresentation(
        activity = status,
        working = working,
        variant = variant,
        tone =
            cardTone(
                status = status.status,
                hero = variant == ProjectCardVariant.HERO,
                live = live,
                failed = false,
            ),
    )
}
