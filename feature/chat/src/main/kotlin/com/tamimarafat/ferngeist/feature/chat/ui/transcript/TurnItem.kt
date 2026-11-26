package com.tamimarafat.ferngeist.feature.chat.ui.transcript

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
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
import kotlin.math.max

private const val TURN_ENTRANCE_MS = 180

@Composable
internal fun LazyItemScope.TurnItem(
    turn: Turn,
    isLast: Boolean,
    awaitingHydration: Boolean,
    markdownDocuments: Map<String, MarkdownRenderedDocument>,
    workingTree: List<ChatChangedFile>,
    runway: TurnRunway,
    onPromptHeight: (Int) -> Unit,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Placement springs because the app's SharedTransitionLayout is a lookahead scope: there the list
    // places a row at its final offset in the first frame of a row above changing size.
    val entrance =
        Modifier.animateItem(
            fadeInSpec = if (turn.prompt != null) tween(TURN_ENTRANCE_MS) else null,
            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            fadeOutSpec = null,
        )
    val heldPx = (runway as? TurnRunway.Held)?.px ?: 0
    // Eased, not dropped: letting go in one frame jumped the prompt below by the whole gap. Holding
    // takes effect at once (the max below), as the row must not shrink as its runway strut goes.
    val release = remember { Animatable(0, Int.VectorConverter) }
    val releaseSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Int>()
    LaunchedEffect(heldPx) { if (heldPx > 0) release.snapTo(heldPx) else release.animateTo(0, releaseSpec) }
    val held =
        Modifier.layout { measurable, constraints ->
            // Read in layout: the ease re-measures this row instead of recomposing it.
            val minHeight = max(heldPx, release.value).coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = minHeight))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
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
            if (turn.replies.isNotEmpty()) {
                TurnReplies(
                    turn,
                    isLast,
                    awaitingHydration,
                    markdownDocuments,
                    workingTree,
                    onThoughtClick,
                    onToolCallClick,
                    onRetryMessage,
                )
            }
        }
    }
}

/**
 * Unspaced: each reply already ends on its last block's gap, so replies of one turn read as one
 * transcript, not as bubbles with a wider seam after every split.
 */
@Composable
private fun TurnReplies(
    turn: Turn,
    isLast: Boolean,
    awaitingHydration: Boolean,
    markdownDocuments: Map<String, MarkdownRenderedDocument>,
    workingTree: List<ChatChangedFile>,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
) {
    val segments = remember(turn) { turn.segments }
    Column {
        turn.replies.forEachIndexed { index, reply ->
            key(reply.id) {
                val closes = index == turn.replies.lastIndex
                // A saved paint's last turn may still be running: it reads as live, with no close.
                val held = closes && awaitingHydration
                CompositionLocalProvider(
                    localWorkingTreeChanges provides if (closes) workingTree else emptyList(),
                ) {
                    TurnBubble(
                        reply,
                        markdownDocuments,
                        (isLast && closes && reply.isStreaming) || held,
                        segments.takeIf { closes && !held },
                        onThoughtClick,
                        onToolCallClick,
                        onRetryMessage,
                    )
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
