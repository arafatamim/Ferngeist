package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.ChatEmptyHero
import com.tamimarafat.ferngeist.feature.chat.ui.localActiveRails
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.BOTTOM_SPACER_KEY
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.RunwaySpacer
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.of
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.rememberRunway
import com.tamimarafat.ferngeist.feature.chat.ui.turnWorkingTree

@Composable
internal fun ChatMessageList(
    state: ChatState,
    scroll: ChatScrollHandle,
    appBarScrollConnection: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)? = null,
) {
    val listState = scroll.listState
    val allMessages = state.messages
    val itemKey: (ChatMessage) -> String = { state.listKeys[it.id] ?: it.id }
    val contentTop = listTopPadding + 8.dp
    val window = rememberMessageWindow(state, allMessages, scroll)
    val windowed = window.messages
    val rows = window.rows
    SideEffect { scroll.rows = rows }
    val promptHeights = remember { mutableStateMapOf<String, Int>() }
    val fadePx = with(LocalDensity.current) { pinnedPromptFadeDistance.roundToPx() }
    val runway = rememberRunway(scroll, windowed, itemKey, listBottomPadding)
    val workingTree = state.git.turnWorkingTree()

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(localActiveRails provides scroll.activeRails) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .nestedScroll(scroll.userScrollDetector)
                        // The app bar is driven by this list alone. Attaching the app bar's
                        // connection at the Scaffold instead would also capture the scroll of any
                        // other Scaffold descendant — notably the composer's text field, which
                        // floats over this list as a sibling — and collapse the bar instead of
                        // scrolling the text.
                        .nestedScroll(appBarScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, top = contentTop, end = 16.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leadingItems(rows, window.loadOlder)
                // Every row is a turn, so any slot reuses any other; replies keep their remembered state by id.
                items(items = rows.turns, key = { itemKey(it.first) }, contentType = { "turn" }) { turn ->
                    val key = itemKey(turn.first)
                    val isLast = turn.last.id == renderedLastMessageId
                    TurnItem(
                        turn = turn,
                        isLast = isLast,
                        markdownDocuments = state.markdownDocuments,
                        workingTree = if (isLast) workingTree else emptyList(),
                        runway = runway.of(key, scroll.following),
                        onPromptHeight = { promptHeights[key] = it },
                        onThoughtClick = onThoughtClick,
                        onToolCallClick = onToolCallClick,
                        onRetryMessage = onRetryMessage,
                    )
                }
                item(key = BOTTOM_SPACER_KEY) { RunwaySpacer(runway) }
            }
        }
        PinnedPromptOverlays(rows, promptHeights, itemKey, scroll, contentTop, fadePx)
        AnimatedVisibility(
            visible = state.isNewChat && allMessages.isEmpty() && state.pendingMessages.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ChatEmptyHero(iconUrl = state.iconUrl)
        }
    }
}

/** The rows above the transcript: the resumed-session seam, then the control that loads older history. */
private fun LazyListScope.leadingItems(
    rows: TranscriptRows,
    loadOlder: () -> Unit,
) {
    if (rows.resumedSession) {
        item(key = "__resumed_session_notice") { ResumedSessionNotice() }
    }
    if (rows.hasOlder) {
        item(key = "__load_older") {
            OutlinedButton(
                onClick = loadOlder,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.chat_load_earlier))
            }
        }
    }
}

/**
 * Perforated seam at the top of a transcript reattached without history replay
 * (`session/resume`). A rewind marker on a tear line reads as "this conversation
 * continues off-screen", so an empty transcript does not look like a fresh chat.
 */
@Composable
private fun ResumedSessionNotice() {
    val seamColor = MaterialTheme.colorScheme.outlineVariant
    // Dashed rule drawn inline; `RowScope` receiver so both sides share one weight.
    val seam: RowScope.() -> Modifier = {
        Modifier
            .weight(1f)
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = seamColor,
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = seam())
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.chat_resumed_session),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = seam())
    }
}
