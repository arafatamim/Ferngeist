package com.tamimarafat.ferngeist.feature.chat.pending

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus

/**
 * Reconciles SENDING pending bubbles with their reducer echo in [messages], returning the bubbles
 * still pending and the echo id to client id map of the bubbles that were delivered.
 * A SENDING bubble is removed when a USER message carrying its client id appears in the snapshot,
 * because the echoed message in [messages] is the canonical delivery. Matching by id, not
 * content, keeps two identical prompts apart. A FAILED bubble whose prompt [messages] prove
 * delivered goes too: its send lost only the reply (a dropped connection), so a retry would repeat it.
 */
internal fun reconcileEchoes(
    pending: List<ChatMessage>,
    messages: List<ChatMessage>,
): Pair<List<ChatMessage>, Map<String, String>> {
    val live =
        pending.filterNot { it.status == MessageDeliveryStatus.FAILED && messages.proveDelivered(it.clientId ?: it.id) }
    val pendingSending = live.filter { it.status == MessageDeliveryStatus.SENDING }
    if (pendingSending.isEmpty()) return live to emptyMap()
    val echoes = findEchoes(pendingSending, messages)
    if (echoes.isEmpty()) return live to emptyMap()
    val echoedClientIds = echoes.values.toSet()
    return live.filterNot { (it.clientId ?: it.id) in echoedClientIds } to echoes
}

/** True when the transcript holds [clientId]'s prompt as having reached the agent. */
internal fun List<ChatMessage>.proveDelivered(clientId: String): Boolean =
    any { it.role == ChatMessage.Role.USER && it.clientId == clientId && it.deliveryConfirmed }

/** Echo message id to the client id of the pending bubble it delivers. */
private fun findEchoes(
    pendingSending: List<ChatMessage>,
    messages: List<ChatMessage>,
): Map<String, String> =
    buildMap {
        for (sending in pendingSending) {
            val clientId = sending.clientId ?: sending.id
            val echoed = messages.lastOrNull { it.role == ChatMessage.Role.USER && it.clientId == clientId }
            if (echoed != null) put(echoed.id, clientId)
        }
    }
