package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle

private const val INITIAL_WINDOW = 50
private const val WINDOW_STEP = 50

internal class MessageWindow(
    val messages: List<ChatMessage>,
    val rows: TranscriptRows,
    val loadOlder: () -> Unit,
)

/**
 * The transcript slice the list shows: from its oldest shown message to the end. Held by id, not
 * by size: a size window drops its top message for every new one, and the message being read
 * could slide out from under the reader. Unset (or gone) falls back to the last INITIAL_WINDOW.
 *
 * Loading older keeps the message the reader was on in place. Left to the list, the anchor is the
 * "load earlier" button itself, and the whole batch lands under it, pushing that message away.
 */
@Composable
internal fun rememberMessageWindow(
    state: ChatState,
    allMessages: List<ChatMessage>,
    scroll: ChatScrollHandle,
): MessageWindow {
    var oldestShownId by rememberSaveable(state.serverId) { mutableStateOf<String?>(null) }
    val restoreId = state.restoredScrollSnapshot?.anchorMessageId.takeUnless { scroll.restoreConsumed }
    val start =
        remember(allMessages, oldestShownId, restoreId) {
            val fallback = (allMessages.size - INITIAL_WINDOW).coerceAtLeast(0)
            val anchor = allMessages.indexOfFirst { it.id == restoreId }.takeIf { it >= 0 } ?: fallback
            val index =
                allMessages.indexOfFirst { it.id == oldestShownId }.takeIf { it >= 0 } ?: minOf(anchor, fallback)
            if (allMessages.isEmpty()) 0 else allMessages.turnStart(index)
        }
    val windowed = remember(allMessages, start) { allMessages.drop(start) }
    if (!state.isLoading && windowed.isNotEmpty()) {
        val oldest = windowed.first().id
        SideEffect { oldestShownId = oldest }
    }
    val rows =
        remember(windowed, start, state.resumedSession) {
            TranscriptRows(resumedSession = state.resumedSession, hasOlder = start > 0, turns = windowed.toTurns())
        }
    // The turn the reader is on, by its first message id, and its offset in the viewport.
    var olderAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    olderAnchor?.let { (messageId, offset) ->
        SideEffect {
            scroll.holdWindowAnchor(rows, messageId, offset)
            olderAnchor = null
        }
    }
    return MessageWindow(
        messages = windowed,
        rows = rows,
        loadOlder = {
            olderAnchor =
                scroll.listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
                    rows.turnAt(item.index)?.let { it.first.id to item.offset }
                }
            oldestShownId = allMessages[allMessages.turnStart((start - WINDOW_STEP).coerceAtLeast(0))].id
        },
    )
}
