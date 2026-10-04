package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatState

private const val INITIAL_WINDOW = 50
private const val WINDOW_STEP = 50

internal class MessageWindow(
    val messages: List<ChatMessage>,
    val hasOlder: Boolean,
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
    listState: LazyListState,
    itemKey: (ChatMessage) -> String,
): MessageWindow {
    var oldestShownId by rememberSaveable(state.serverId) { mutableStateOf<String?>(null) }
    val start =
        remember(allMessages, oldestShownId) {
            allMessages.indexOfFirst { it.id == oldestShownId }.takeIf { it >= 0 }
                ?: (allMessages.size - INITIAL_WINDOW).coerceAtLeast(0)
        }
    val windowed = remember(allMessages, start) { allMessages.drop(start) }
    if (!state.isLoading && windowed.isNotEmpty()) {
        val oldest = windowed.first().id
        SideEffect { oldestShownId = oldest }
    }
    var olderAnchor by remember { mutableStateOf<Pair<Any, Int>?>(null) }
    olderAnchor?.let { (key, offset) ->
        val leadingItems = (if (state.resumedSession) 1 else 0) + (if (start > 0) 1 else 0)
        val index = windowed.indexOfFirst { itemKey(it) == key }
        SideEffect {
            if (index >= 0) listState.requestScrollToItem(leadingItems + index, -offset)
            olderAnchor = null
        }
    }
    return MessageWindow(
        messages = windowed,
        hasOlder = start > 0,
        loadOlder = {
            olderAnchor =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { (it.key as? String)?.startsWith("__") == false }
                    ?.let { it.key to it.offset }
            oldestShownId = allMessages[(start - WINDOW_STEP).coerceAtLeast(0)].id
        },
    )
}
