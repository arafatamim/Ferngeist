package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The prompts to draw as chips: the pinned one — the last prompt whose row has gone entirely under
 * the app bar — preceded by the one it displaced, while that one is still being pushed off.
 * [rowOf] gives a laid-out row's top..bottom, measured from the content origin (the content-area
 * top, just under the app bar), or null when the row is not laid out.
 *
 * A prompt pins only once nothing of its bubble is left on screen, so the chip never covers the
 * real message. The displaced chip rides on the pinned prompt's row and so keeps moving up and off
 * the screen; once that row has left the laid-out items the displaced chip is long gone.
 *
 * No chip when the whole answer fits on screen: the prompt's bubble and the next turn (or the
 * list end at [lastIndex]) are both laid out within a viewport ([viewportEnd]) of each other, so
 * the chip would only duplicate what's already visible. Ends that are not laid out are not
 * measurable, so those keep pinning.
 */
internal fun pinnedPromptRows(
    userRows: List<Int>,
    firstVisibleIndex: Int,
    rowOf: (Int) -> IntRange?,
    viewportEnd: Int,
    lastIndex: Int,
): List<Int> {
    val pinned =
        userRows.lastOrNull { row ->
            row < firstVisibleIndex || rowOf(row)?.let { it.last <= 0 } == true
        } ?: return emptyList()
    val anchor = userRows.firstOrNull { it > pinned } ?: lastIndex
    val pinnedBottom = rowOf(pinned)?.last
    val anchorTop = rowOf(anchor)?.first
    if (pinnedBottom != null && anchorTop != null && anchorTop - pinnedBottom <= viewportEnd) {
        return emptyList()
    }
    val displaced = userRows.lastOrNull { it < pinned }?.takeIf { rowOf(pinned) != null }
    return listOfNotNull(displaced, pinned)
}

/**
 * Offset of a chip from the content-area top: zero while it rests there, negative once the next
 * prompt's row ([nextRowTop]) reaches it, so the row pushes the chip up ahead of itself.
 */
internal fun pinnedPromptLift(
    nextRowTop: Int?,
    chipHeight: Int,
): Int = minOf(0, nextRowTop?.let { it - chipHeight } ?: 0)

/**
 * A two-line reminder of a turn's prompt, held at the top of the transcript while its answer
 * scrolls under it. Tapping it scrolls back to the prompt.
 *
 * Drawn above the list, not as one of its rows: a lazy list paints rows in list order, so a row
 * moved over its neighbours slides behind them. The next prompt's row is read in the layout phase:
 * it changes on every scroll frame, and reading it in composition would recompose each time.
 */
@Composable
internal fun PinnedPrompt(
    text: String,
    row: Int,
    nextRow: Int?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var chipHeightPx by remember { mutableIntStateOf(0) }
    // Slides down from under the bar when it first pins. Keyed by row upstream, so a chip that is
    // being pushed off keeps its node and does not replay this.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .offset {
                    val next = nextRow?.let { n -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == n } }
                    IntOffset(0, pinnedPromptLift(next?.offset, chipHeightPx))
                }.graphicsLayer {
                    translationY = -(1f - entrance.value) * chipHeightPx
                    alpha = entrance.value
                },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The prompt's own bubble, cut to two lines.
        ElevatedCard(
            onClick = { scope.launch { listState.animateScrollToItem(row, 0) } },
            shape = UserBubbleShape,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .onSizeChanged { chipHeightPx = it.height },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
