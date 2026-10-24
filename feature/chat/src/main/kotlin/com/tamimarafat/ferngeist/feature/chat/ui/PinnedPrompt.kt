package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.launch

/**
 * How far before its exit a chip starts fading out as the next prompt comes up. The re-entry of
 * its own prompt fades across the chip's height instead; see [PinnedPromptOverlays].
 */
internal val pinnedPromptFadeDistance: Dp = 64.dp

private const val PINNED_PROMPT_ENTRANCE_MS = 150

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
 * No chip for a prompt whose answer [fits] on screen, since it would only duplicate what's
 * already visible. Decided per chip: a short answer taking over must not cut off the chip it is
 * pushing out.
 *
 * The pin survives a [fadePx] re-entry window: the chip stays while its row's bottom is at most
 * that far back on screen, fading out across it, so scrolling up hands off to the real bubble
 * instead of popping. A row whose top is back on screen has fully re-entered, whatever the window:
 * a bubble shorter than the chip would otherwise sit under a still-visible chip. Zero disables the
 * window and restores the hard unpin.
 */
internal fun pinnedPromptRows(
    userRows: List<Int>,
    firstVisibleIndex: Int,
    rowOf: (Int) -> IntRange?,
    fadePx: Int,
    fits: (Int) -> Boolean,
): List<Int> {
    val pinned =
        userRows.lastOrNull { row ->
            row < firstVisibleIndex || rowOf(row)?.let { it.last <= fadePx && it.first < 0 } == true
        } ?: return emptyList()
    val displaced = userRows.lastOrNull { it < pinned }?.takeIf { rowOf(pinned) != null }
    return listOfNotNull(displaced, pinned).filterNot(fits)
}

/**
 * Whether [prompt]'s answer � its rows up to the next prompt, or the list end at [lastIndex] �
 * is at most a viewport ([viewportEnd]) tall. [extentOf] is a row's height plus the gap after it,
 * remembered from whenever the row was last laid out: measuring only what is laid out right now
 * flipped the answer to "unknown" the moment its first row scrolled away, popping the chip in
 * mid-scroll. A row never seen counts as not fitting, so the chip errs towards showing.
 */
internal fun answerFits(
    prompt: Int,
    userRows: List<Int>,
    lastIndex: Int,
    extentOf: (Int) -> Int?,
    viewportEnd: Int,
): Boolean = answerHeight(prompt, userRows, lastIndex, extentOf, viewportEnd)?.let { it <= viewportEnd } == true

/**
 * Height of [prompt]'s answer (see [answerFits]), or null when a row of it was never seen. Stops
 * counting once past [cap], so the result is then only known to exceed it.
 */
internal fun answerHeight(
    prompt: Int,
    userRows: List<Int>,
    lastIndex: Int,
    extentOf: (Int) -> Int?,
    cap: Int,
): Int? {
    val anchor = userRows.firstOrNull { it > prompt } ?: lastIndex
    var height = 0
    for (row in prompt + 1 until anchor) {
        height += extentOf(row) ?: return null
        if (height > cap) return height
    }
    return height
}

/**
 * Alpha of a chip whose row bottom sits at [rowBottom] (content-origin px, null when the row is
 * not laid out) as it re-enters across a [fadePx] window: opaque while fully under the bar,
 * gone once past the window. Zero or negative [fadePx] restores the hard unpin.
 */
internal fun pinnedPromptFadeAlpha(
    rowBottom: Int?,
    fadePx: Int,
): Float {
    if (rowBottom == null) return 1f
    if (fadePx <= 0) return if (rowBottom <= 0) 1f else 0f
    return (1f - rowBottom / fadePx.toFloat()).coerceIn(0f, 1f)
}

/**
 * Alpha of a chip as its answer ([answerPx] tall) scrolls out: opaque until the next prompt's row
 * ([nextRowTop], content-origin px, null when not laid out) comes within [fadePx] of the exit, gone
 * at the exit. The exit is where 90% of the answer has passed, but never closer than [chipHeight],
 * so the chip is gone before the next prompt reaches it instead of being shoved up by it.
 */
internal fun pinnedPromptExitAlpha(
    nextRowTop: Int?,
    answerPx: Int,
    chipHeight: Int,
    fadePx: Int,
): Float {
    if (nextRowTop == null) return 1f
    val exit = maxOf(answerPx / 10, chipHeight)
    return ((nextRowTop - exit) / fadePx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
}

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
    answerPx: Int,
    chipHeightPx: Int,
    onChipHeight: (Int) -> Unit,
    listState: LazyListState,
    fadePx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A short fade for the cases the scroll-driven fade below cannot cover: a fling that crosses
    // the whole window in a frame, or a chip that appears with its row not laid out. Opacity only:
    // sliding down against the bubble scrolling up read as the chip bouncing.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(PINNED_PROMPT_ENTRANCE_MS)) }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Rows read in the draw phase: the fades track every scroll frame without
                    // recomposing.
                    val items = listState.layoutInfo.visibleItemsInfo
                    val own = items.firstOrNull { it.index == row }
                    val next = nextRow?.let { n -> items.firstOrNull { it.index == n } }
                    alpha =
                        entrance.value *
                        pinnedPromptFadeAlpha(
                            own?.let { it.offset + it.size },
                            // Gone by the time its bubble's top is back, even when that bubble is
                            // shorter than the chip (whose touch target pads it to 48dp).
                            minOf(chipHeightPx, own?.size ?: chipHeightPx),
                        ) *
                        pinnedPromptExitAlpha(next?.offset, answerPx, chipHeightPx, fadePx)
                },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The prompt's own bubble, cut to two lines.
        ElevatedCard(
            onClick = onClick,
            shape = userBubbleShape,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .onSizeChanged { onChipHeight(it.height) },
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

/**
 * Chips for the pinned rows, drawn above the list. Split out of the message list so that stays
 * under detekt's complexity budget.
 */
@Composable
internal fun BoxScope.PinnedPromptOverlays(
    userRows: List<Int>,
    leadingRows: Int,
    windowed: List<ChatMessage>,
    scroll: ChatScrollHandle,
    contentTop: Dp,
    fadePx: Int,
) {
    val listState = scroll.listState
    val scope = rememberCoroutineScope()
    // Row extents by message id, kept after the row scrolls away; see [answerFits].
    val extents = remember { HashMap<String, Int>() }
    // ponytail: one height for all chips; at most one is visible once the exit fade runs.
    var chipHeightPx by remember { mutableIntStateOf(0) }
    val pinnedRows by remember(userRows, leadingRows, windowed, listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val idOf = { row: Int -> windowed.getOrNull(row - leadingRows)?.id }
            info.visibleItemsInfo.forEach { item ->
                idOf(item.index)?.let { extents[it] = item.size + info.mainAxisItemSpacing }
            }
            // The bottom spacer is the last row; its top marks the end of the final answer.
            val lastIndex = leadingRows + windowed.size
            // The first laid-out row, not firstVisibleItemIndex: the streaming follow requests the
            // last index ahead of each measure, so that value names the bottom spacer for a frame
            // and pinned the current prompt on every chunk.
            val firstLaidOut = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val extentOf = { row: Int -> idOf(row)?.let(extents::get) }
            val viewport = info.viewportEndOffset
            val laidOut = { row: Int -> info.visibleItemsInfo.firstOrNull { it.index == row } }
            pinnedPromptRows(
                userRows,
                firstLaidOut,
                { row -> laidOut(row)?.let { it.offset..it.offset + it.size } },
                // The chip's height: the chip is gone exactly when its re-entering bubble lines up
                // under it, never drawn over the real one.
                chipHeightPx,
            ) { prompt -> answerFits(prompt, userRows, lastIndex, extentOf, viewport) }
                // Capped at a viewport: a long answer exits like one that just fills the screen.
                .map { row ->
                    val height = answerHeight(row, userRows, lastIndex, extentOf, viewport) ?: viewport
                    row to minOf(height, viewport)
                }
                // Dropped once faded out, so an invisible chip cannot take taps meant for the list.
                .filter { (row, answerPx) ->
                    val next = userRows.firstOrNull { it > row }?.let(laidOut)
                    pinnedPromptExitAlpha(next?.offset, answerPx, chipHeightPx, fadePx) > 0f
                }
        }
    }
    // Keyed so the pinned chip, once displaced, carries on as the same node instead of being
    // rebuilt mid-push.
    pinnedRows.forEach { (row, answerPx) ->
        key(row) {
            PinnedPrompt(
                text = windowed[row - leadingRows].content,
                row = row,
                nextRow = userRows.firstOrNull { it > row },
                answerPx = answerPx,
                chipHeightPx = chipHeightPx,
                onChipHeight = { chipHeightPx = it },
                listState = listState,
                fadePx = fadePx,
                // Through the handle, so following is released and the follow loop does not
                // fight the jump.
                onClick = { scope.launch { scroll.jumpToItem(row) } },
                modifier =
                    Modifier
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, top = contentTop, end = 16.dp),
            )
        }
    }
}
