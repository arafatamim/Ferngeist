package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the runway under the last sent prompt goes; see [ChatScrollHandle]. */
internal class Runway(
    val promptKey: Any?,
    lastKey: Any?,
    val bottomPx: Int,
    val hold: RunwayHold,
) {
    /** The row after the prompt that holds the reserve; null while the prompt is still the last row. */
    val replyKey: Any? = lastKey.takeIf { promptKey != null && it != promptKey }
}

@Composable
internal fun rememberRunway(
    scroll: ChatScrollHandle,
    windowed: List<ChatMessage>,
    itemKey: (ChatMessage) -> String,
    bottomPadding: Dp,
): Runway {
    val hold = remember { RunwayHold() }
    val prompt = scroll.runwayPrompt(windowed)
    val runway =
        Runway(
            promptKey = prompt?.let(itemKey),
            lastKey = windowed.lastOrNull()?.let(itemKey),
            bottomPx = easedBottomPx(with(LocalDensity.current) { bottomPadding.roundToPx() }),
            hold = hold,
        )
    SideEffect {
        scroll.runwayPromptKey = runway.promptKey
        scroll.onBottomPadding(runway.bottomPx)
    }
    // Read lazily: an unconditional layoutInfo read would recompose the list on every scroll frame.
    hold.track(runway.replyKey) { scroll.listState.layoutInfo }
    return runway
}

/** A bottom padding change bigger than this in one frame is a step to ease, not the keyboard moving. */
private val bottomStep = 48.dp

/**
 * [targetPx], with a step eased in: the composer reports its resting height the moment it expands
 * or collapses, and landing that in one frame jumps the transcript (or, shrinking, the list clamps
 * it down at once). Smaller per-frame changes, the keyboard's inset, pass straight through so the
 * list keeps pace with it. Eased with the composer's own spec, so both move together.
 */
@Composable
private fun easedBottomPx(targetPx: Int): Int {
    val stepPx = with(LocalDensity.current) { bottomStep.toPx() }
    val shown = remember { Animatable(targetPx.toFloat()) }
    // Plain holder: survives the effect being restarted, which cancels the animation mid-way.
    val easing = remember { booleanArrayOf(false) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(targetPx) {
        if (easing[0] || abs(targetPx - shown.value) > stepPx) {
            easing[0] = true
            shown.animateTo(targetPx.toFloat(), spec)
            easing[0] = false
        } else {
            shown.snapTo(targetPx.toFloat())
        }
    }
    return if (easing[0] || abs(targetPx - shown.value) > stepPx) shown.value.roundToInt() else targetPx
}

/**
 * The previous reply, held at its runway height while the next prompt glides up past it: collapsing
 * it on send pops the new prompt into its place. Let go once the row leaves composition, or once the
 * reader scrolls: the row can stay composed just above the prompt, and scrolling up would show the
 * held height as a gap. Letting go at the glide's end instead drops the reply into view on its own.
 */
internal class RunwayHold {
    // ponytail: plain fields written in composition; a repeated composition derives the same values.
    var key: Any? = null
    var px = 0
    private var lastReplyKey: Any? = null

    fun track(
        replyKey: Any?,
        info: () -> LazyListLayoutInfo,
    ) {
        val last = lastReplyKey
        lastReplyKey = replyKey
        if (last == null || last == replyKey) return
        val row = info().visibleItemsInfo.firstOrNull { it.key == last } ?: return
        key = last
        px = row.size
    }
}

@Composable
internal fun Modifier.runwayRow(
    scroll: ChatScrollHandle,
    runway: Runway,
    key: Any,
): Modifier =
    when (key) {
        runway.replyKey -> runwayReply(scroll, runway)
        runway.hold.key -> if (scroll.following) heldReply(runway.hold, key) else this
        else -> this
    }

@Composable
private fun Modifier.heldReply(
    hold: RunwayHold,
    key: Any,
): Modifier {
    DisposableEffect(hold, key) { onDispose { if (hold.key == key) hold.key = null } }
    return heightIn(min = with(LocalDensity.current) { hold.px.toDp() }).wrapContentHeight(Alignment.Top)
}

/** Reserves the rest of the viewport under the prompt in the reply's own row. */
@Composable
private fun Modifier.runwayReply(
    scroll: ChatScrollHandle,
    runway: Runway,
): Modifier {
    val px = scroll.rememberRunwayPx(runway.promptKey, runway.replyKey, runway.bottomPx)
    // Without the wrap, the reply is measured against the min and Compose centres it in the reserve.
    return heightIn(min = with(LocalDensity.current) { px.toDp() }).wrapContentHeight(Alignment.Top)
}

/** The bottom spacer, which holds the reserve until the reply has a row of its own. */
@Composable
internal fun RunwaySpacer(
    scroll: ChatScrollHandle,
    runway: Runway,
) {
    val reserve = if (runway.replyKey == null) scroll.rememberRunwayPx(runway.promptKey, BOTTOM_SPACER_KEY, 0) else 0
    val heightPx = maxOf(runway.bottomPx, reserve)
    Spacer(modifier = Modifier.height(with(LocalDensity.current) { heightPx.toDp() }))
}

internal const val BOTTOM_SPACER_KEY = "__chat_bottom_spacer"
