package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** Duration of the shortest jump-button scroll; longer ones scale up to [EASE_MAX_MS]. */
internal const val EASE_MIN_MS = 650

/** Duration of a jump that travels the full [EASE_MAX_VIEWPORTS]. */
private const val EASE_MAX_MS = 1000

/**
 * Farthest a jump eases, in viewports. A farther target is first placed this far away in one
 * frame, so the ease never has to compose the whole transcript.
 */
private const val EASE_MAX_VIEWPORTS = 2.5f

/** Duration of a short settle onto the end, for a jump chasing a stream. */
private const val SETTLE_MS = 220

/** Most chase eases a jump to the bottom runs after its main ease. */
private const val CHASE_PASSES = 3

/** Within this of the end counts as at the end: following, and no jump button. */
internal val relatchDistance = 48.dp

/** Share of the remaining distance a glide covers per 60 fps frame. */
private const val GLIDE_PER_FRAME = 0.15f

internal const val PIN_EPSILON_PX = 0.5f
internal const val NANOS_PER_SECOND = 1e9f
internal const val MAX_FRAME_SECONDS = 1f / 30f
private const val FRAMES_PER_SECOND = 60f

/**
 * Eases to the top of item 0 along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToTop() {
    val anchor = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
    val estimate = -distanceToTop()
    scrollToItem(0)
    // Where the item that was at the top now sits gives the exact distance travelled; only when
    // it has scrolled out of view is the distance an estimate, and then the start jumps anyway.
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance = landed?.let { (it.offset - anchor.offset).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(-scrollBy(distance), exact = landed != null)
}

/**
 * Eases to the end of the content along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToBottom() {
    val anchor = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    scrollToItem(layoutInfo.totalItemsCount - 1)
    val travel = walkBackTo(anchor)
    glideOnto(travel, exact = layoutInfo.visibleItemsInfo.any { it.index == anchor.index })
    // A reply still streaming grows during the ease, past the distance it set out to cover. Chase
    // the new end in short eases; snapping onto it hopped by every line that streamed meanwhile.
    repeat(CHASE_PASSES) {
        val left = distanceToBottom()
        if (left < 1f) return
        animateScrollBy(left, tween(SETTLE_MS, easing = EaseOutCubic))
    }
}

/**
 * Called with the list stepped back [travel] pixels from its target: eases forward onto it.
 *
 * The step back happens in the same frame as the snap to the target, so when the distance was
 * measured [exact]ly the list visibly never moves before the ease starts — it simply glides from
 * where it was. Easing a known distance is what makes the curve a real curve: an ease that
 * re-estimates an off-screen target every frame keeps stretching as taller items come into view
 * and reads as a linear scroll with a hard stop.
 *
 * A far target cannot be measured without composing everything in between, so the start jumps to
 * [EASE_MAX_VIEWPORTS] away and eases out only — the jump reads as the fast start of the motion.
 */
private suspend fun LazyListState.glideOnto(
    travel: Float,
    exact: Boolean,
) {
    if (travel == 0f) return
    val fraction = (abs(travel) / maxEasePx()).coerceAtMost(1f)
    animateScrollBy(
        value = travel,
        animationSpec =
            tween(
                durationMillis = EASE_MIN_MS + ((EASE_MAX_MS - EASE_MIN_MS) * fraction).toInt(),
                easing = if (exact) EaseInOutCubic else EaseOutCubic,
            ),
    )
}

/**
 * From the end, steps back a viewport at a time until [anchor] is laid out again, then puts it
 * where it was, all in one frame; the pixels stepped are then exact. An average-row estimate of a
 * target one or two viewports up was off by whole rows, and the ease started with that jump. Gives
 * up [EASE_MAX_VIEWPORTS] back. Returns the pixels back from the end.
 */
private suspend fun LazyListState.walkBackTo(anchor: LazyListItemInfo): Float {
    val viewport = layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }.toFloat()
    var travelled = 0f
    while (true) {
        val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
        if (landed != null) return travelled - scrollBy((landed.offset - anchor.offset).toFloat())
        val step = min(viewport, maxEasePx() - travelled)
        val stepped = if (step > 0f) -scrollBy(-step) else 0f
        if (stepped == 0f) return travelled
        travelled += stepped
    }
}

private fun LazyListState.maxEasePx(): Float =
    layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset } * EASE_MAX_VIEWPORTS

/** Signed pixels from the current position to the top of item 0; negative scrolls up. */
private fun LazyListState.distanceToTop(): Float {
    val info = layoutInfo
    info.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { return it.offset.toFloat() }
    return -(firstVisibleItemIndex * info.averageItemSpan() + firstVisibleItemScrollOffset)
}

/** Signed pixels from the current position to the end of the content; positive scrolls down. */
internal fun LazyListState.distanceToBottom(bottomSpacerPx: Int? = null): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    val itemsBelow = info.totalItemsCount - 1 - last.index
    // The final row is the composer spacer, not another turn as tall as the visible reply.
    val below =
        if (itemsBelow > 0 && bottomSpacerPx != null) {
            (itemsBelow - 1) * info.averageItemSpan() + info.mainAxisItemSpacing + bottomSpacerPx
        } else {
            itemsBelow * info.averageItemSpan()
        }
    return below + (last.offset + last.size - info.viewportEndOffset)
}

private fun LazyListLayoutInfo.averageItemSpan(): Float {
    val items = visibleItemsInfo
    if (items.isEmpty()) return 0f
    return items.sumOf { it.size }.toFloat() / items.size + mainAxisItemSpacing
}

/**
 * To the end, but no further than the runway prompt reaching the top: a reply already past the
 * viewport stays below it. Until the prompt is in the list the old one still holds the key.
 */
internal fun glideDistance(
    listState: LazyListState,
    promptKey: Any?,
    awaitingTurn: Boolean,
): Float {
    val end = listState.distanceToBottom()
    val key = promptKey?.takeIf { !awaitingTurn } ?: return end
    val prompt = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return end
    return minOf(end, prompt.offset.toFloat())
}

/** One glide step; true when it jumped instead of easing. */
internal suspend fun LazyListState.glideFrame(
    distance: Float,
    dt: Float,
    mayJump: Boolean,
    endIndexHint: Int,
): Boolean {
    if (distance <= PIN_EPSILON_PX) return false
    try {
        // A far end would compose everything in between: land a few viewports short of it, once.
        // Its distance stays an estimate after landing, so jumping again would never arrive.
        if (mayJump && distance > this.maxEasePx()) {
            this.scrollToItem(endIndexHint)
            this.scrollBy(-this.maxEasePx())
            return true
        }
        // Frame-rate independent ease-out toward a target that may still be moving.
        this.scrollBy(distance * (1f - (1f - GLIDE_PER_FRAME).pow(dt * FRAMES_PER_SECOND)))
    } catch (e: CancellationException) {
        // A drag took the scroll; following has already stopped. Only our own cancellation ends us.
        if (!currentCoroutineContext().isActive) throw e
    }
    return false
}
