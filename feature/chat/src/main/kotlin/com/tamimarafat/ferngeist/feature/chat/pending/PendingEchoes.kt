package com.tamimarafat.ferngeist.feature.chat.pending

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus

/**
 * Reconciles SENDING pending bubbles with their reducer echo in [messages], returning the bubbles
 * still pending and the echo id to client id map of the bubbles that were delivered.
 * A SENDING bubble is removed when a USER message carrying its client id appears in the snapshot,
 * because the echoed message in [messages] is the canonical delivery. Matching by id, not
 * content, keeps two identical prompts apart.
 */
internal fun reconcileEchoes(
    pending: List<ChatMessage>,
    messages: List<ChatMessage>,
): Pair<List<ChatMessage>, Map<String, String>> {
    val pendingSending = pending.filter { it.status == MessageDeliveryStatus.SENDING }
    if (pendingSending.isEmpty()) return pending to emptyMap()
    val echoes = findEchoes(pendingSending, messages)
    if (echoes.isEmpty()) return pending to emptyMap()
    val echoedClientIds = echoes.values.toSet()
    return pending.filterNot { (it.clientId ?: it.id) in echoedClientIds } to echoes
}

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
