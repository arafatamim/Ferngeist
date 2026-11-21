package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.CardTone
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.cardTone

internal enum class AgentCardVariant { DIRECT, HERO, TILE }

internal data class AgentCardPresentation(
    val status: TurnStatus,
    val working: Int,
    val unreadFinished: Int,
    val variant: AgentCardVariant,
    val tone: CardTone,
    val connection: ServerConnectionUiState,
)

internal fun agentCardPresentation(
    gatewayAgent: Boolean,
    chats: Collection<TurnActivity>,
    connection: ServerConnectionUiState,
): AgentCardPresentation {
    val working = chats.count { it.status == TurnStatus.WORKING }
    val unread = chats.count { it.status == TurnStatus.FINISHED }
    val status = chats.aggregateTurnActivity().status
    val variant =
        when {
            !gatewayAgent -> AgentCardVariant.DIRECT
            working > 0 || unread > 0 || status == TurnStatus.NEEDS_ATTENTION || status == TurnStatus.ERROR ->
                AgentCardVariant.HERO
            else -> AgentCardVariant.TILE
        }
    return AgentCardPresentation(
        status = status,
        working = working,
        unreadFinished = unread,
        variant = variant,
        tone =
            cardTone(
                status = status,
                hero = variant == AgentCardVariant.HERO,
                live = connection.isConnected,
                failed = connection.isFailed,
            ),
        connection = connection,
    )
}
