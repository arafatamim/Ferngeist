package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.displayBlocks
import com.tamimarafat.ferngeist.feature.chat.ui.MessageBubble
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkingTreeChanges
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.TurnRunway
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

private const val TURN_ENTRANCE_MS = 180

@Composable
internal fun LazyItemScope.TurnItem(
    turn: Turn,
    isLast: Boolean,
    markdownDocuments: Map<String, MarkdownRenderedDocument>,
    workingTree: List<ChatChangedFile>,
    runway: TurnRunway,
    onPromptHeight: (Int) -> Unit,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val entrance =
        if (turn.prompt != null) {
            Modifier.animateItem(fadeInSpec = tween(TURN_ENTRANCE_MS), placementSpec = null, fadeOutSpec = null)
        } else {
            Modifier
        }
    val held =
        if (runway is TurnRunway.Held) {
            Modifier.heightIn(min = with(LocalDensity.current) { runway.px.toDp() }).wrapContentHeight(Alignment.Top)
        } else {
            Modifier
        }
    Box(modifier = modifier.then(entrance).then(held)) {
        if (runway is TurnRunway.Reserve) Spacer(modifier = runwayStrut(runway.belowPx))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            turn.prompt?.let { prompt ->
                key(prompt.id) {
                    TurnBubble(
                        prompt,
                        markdownDocuments,
                        false,
                        null,
                        onThoughtClick,
                        onToolCallClick,
                        onRetryMessage,
                        Modifier.onSizeChanged { onPromptHeight(it.height) },
                    )
                }
            }
            val segments = remember(turn) { turn.segments }
            turn.replies.forEachIndexed { index, reply ->
                key(reply.id) {
                    val closes = index == turn.replies.lastIndex
                    CompositionLocalProvider(
                        localWorkingTreeChanges provides if (closes) workingTree else emptyList(),
                    ) {
                        TurnBubble(
                            reply,
                            markdownDocuments,
                            isLast && closes && reply.isStreaming,
                            segments.takeIf { closes },
                            onThoughtClick,
                            onToolCallClick,
                            onRetryMessage,
                        )
                    }
                }
            }
        }
    }
}

/** As tall as the viewport less [belowPx]. Outer modifier trims; inner one asks the list for its height. */
private fun LazyItemScope.runwayStrut(belowPx: Int): Modifier =
    Modifier
        .layout { measurable, constraints ->
            val full = measurable.measure(constraints)
            layout(0, (full.height - belowPx).coerceAtLeast(0)) {}
        }.fillParentMaxHeight()

@Composable
private fun TurnBubble(
    message: ChatMessage,
    markdownDocuments: Map<String, MarkdownRenderedDocument>,
    showStreamingIndicator: Boolean,
    turnSegments: List<AssistantSegment>?,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Keyed by the same keys the renderer draws from (run keys, not segment ids), so the document
    // looked up here is the one [MessageBubble] will ask for.
    val messageMarkdown =
        remember(message, markdownDocuments) {
            if (message.role != ChatMessage.Role.ASSISTANT) {
                persistentMapOf<String, MarkdownRenderedDocument>()
            } else {
                buildMap {
                    message.segments.displayBlocks().forEach { block ->
                        markdownDocuments[block.key]?.let { put(block.key, it) }
                    }
                    markdownDocuments[message.id]?.let { put(message.id, it) }
                }.toPersistentMap()
            }
        }
    MessageBubble(
        message = message,
        markdownDocuments = messageMarkdown,
        showStreamingIndicator = showStreamingIndicator,
        turnSegments = turnSegments,
        onThoughtClick = onThoughtClick,
        onToolCallClick = onToolCallClick,
        onRetryMessage = onRetryMessage,
        modifier = modifier,
    )
}
