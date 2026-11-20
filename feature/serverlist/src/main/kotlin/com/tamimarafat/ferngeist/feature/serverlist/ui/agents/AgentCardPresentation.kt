package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity

internal enum class AgentCardVariant { DIRECT, HERO, TILE }

internal enum class AgentCardTone { PRIMARY, ATTENTION, CONNECTED, FAILED, DEFAULT }

internal data class AgentCardPresentation(
    val status: TurnStatus,
    val working: Int,
    val unreadFinished: Int,
    val variant: AgentCardVariant,
    val tone: AgentCardTone,
    val connection: ServerConnectionUiState,
) {
    val headlineStatus: TurnStatus?
        get() = status.takeUnless { it == TurnStatus.FINISHED || it == TurnStatus.UNKNOWN }
}

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
            when {
                variant == AgentCardVariant.HERO && connection.isConnected -> AgentCardTone.PRIMARY
                status == TurnStatus.NEEDS_ATTENTION -> AgentCardTone.ATTENTION
                connection.isConnected -> AgentCardTone.CONNECTED
                connection.isFailed -> AgentCardTone.FAILED
                else -> AgentCardTone.DEFAULT
            },
        connection = connection,
    )
}
