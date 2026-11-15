package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage

/** A prompt and the replies up to the next prompt. Replies before any prompt form a turn with none. */
internal class Turn(
    val prompt: ChatMessage?,
    val replies: List<ChatMessage>,
) {
    val first: ChatMessage get() = prompt ?: replies.first()

    val last: ChatMessage get() = replies.lastOrNull() ?: first

    val segments: List<AssistantSegment> get() = replies.flatMap { it.segments }
}

internal fun List<ChatMessage>.toTurns(): List<Turn> =
    buildList {
        var prompt: ChatMessage? = null
        val replies = mutableListOf<ChatMessage>()
        for (message in this@toTurns) {
            if (message.role == ChatMessage.Role.USER) {
                if (prompt != null || replies.isNotEmpty()) add(Turn(prompt, replies.toList()))
                prompt = message
                replies.clear()
            } else {
                replies.add(message)
            }
        }
        if (prompt != null || replies.isNotEmpty()) add(Turn(prompt, replies.toList()))
    }

/** The index of the message that starts the turn [index] falls in, so a window never opens inside one. */
internal fun List<ChatMessage>.turnStart(index: Int): Int {
    var start = index
    while (start > 0 && this[start].role != ChatMessage.Role.USER) start--
    return start
}
