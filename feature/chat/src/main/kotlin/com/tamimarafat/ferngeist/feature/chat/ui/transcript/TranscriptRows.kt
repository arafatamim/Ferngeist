package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import com.tamimarafat.ferngeist.core.model.content

/**
 * The list's rows: the leading rows (the resumed-session seam, then the load-older control), then
 * one per turn, then the bottom spacer.
 */
internal class TranscriptRows(
    val resumedSession: Boolean,
    val hasOlder: Boolean,
    val turns: List<Turn>,
) {
    val leading: Int = listOf(resumedSession, hasOlder).count { it }

    val endIndex: Int get() = leading + turns.size

    val lastMessageId: String? get() = turns.lastOrNull()?.last?.id

    val promptRows: List<Int> =
        turns.indices.filter { turns[it].prompt?.content?.isNotBlank() == true }.map { leading + it }

    fun turnAt(row: Int): Turn? = turns.getOrNull(row - leading)

    fun rowOfMessage(messageId: String): Int? =
        turns
            .indexOfFirst { turn ->
                turn.prompt?.id == messageId || turn.replies.any { it.id == messageId }
            }.takeIf { it >= 0 }
            ?.let { leading + it }
}
