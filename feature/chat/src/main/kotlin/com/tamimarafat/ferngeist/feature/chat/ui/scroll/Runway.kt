package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the runway under the last sent prompt goes; see [ChatScrollHandle]. */
internal class Runway(
    val turnKey: Any?,
    val bottomPx: Int,
    val hold: RunwayHold,
    val spacingPx: Int,
)

/** What a turn reserves: nothing, the viewport less [TurnRunway.Reserve.belowPx], or a fixed held height. */
internal sealed interface TurnRunway {
    data object None : TurnRunway

    data class Reserve(
        val belowPx: Int,
    ) : TurnRunway

    data class Held(
        val px: Int,
    ) : TurnRunway
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
            turnKey = prompt?.let(itemKey),
            bottomPx = easedBottomPx(with(LocalDensity.current) { bottomPadding.roundToPx() }),
            hold = hold,
            spacingPx = with(LocalDensity.current) { 12.dp.roundToPx() },
        )
    SideEffect {
        scroll.runwayPromptKey = runway.turnKey
        scroll.onBottomPadding(runway.bottomPx)
    }
    // Read lazily: an unconditional layoutInfo read would recompose the list on every scroll frame.
    hold.track(runway.turnKey) { scroll.listState.layoutInfo }
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
 * The previous turn, held at its runway height while the next prompt glides up past it: collapsing
 * it on send pops the new prompt into its place. Let go once the row leaves composition, or once the
 * reader scrolls: the row can stay composed just above the prompt, and scrolling up would show the
 * held height as a gap. Letting go at the glide's end instead drops the reply into view on its own.
 */
internal class RunwayHold {
    // ponytail: plain fields written in composition; a repeated composition derives the same values.
    var key: Any? = null
    var px = 0
    private var lastTurnKey: Any? = null

    fun track(
        turnKey: Any?,
        info: () -> LazyListLayoutInfo,
    ) {
        val last = lastTurnKey
        lastTurnKey = turnKey
        // Only a new prompt hands off the runway; ACP can split a reply across several messages.
        if (last == null || turnKey == null || turnKey == last) return
        val row = info().visibleItemsInfo.firstOrNull { it.key == last } ?: return
        key = last
        px = row.size
    }
}

@Composable
internal fun Runway.of(
    key: Any,
    following: Boolean,
): TurnRunway =
    when {
        key == turnKey -> TurnRunway.Reserve(bottomPx + spacingPx)
        key == hold.key && following -> {
            DisposableEffect(hold, key) { onDispose { if (hold.key == key) hold.key = null } }
            TurnRunway.Held(hold.px)
        }
        else -> TurnRunway.None
    }

/** The bottom spacer leaves room for the keyboard and composer, eased together with them. */
@Composable
internal fun RunwaySpacer(runway: Runway) {
    Spacer(modifier = Modifier.height(with(LocalDensity.current) { runway.bottomPx.toDp() }))
}

internal const val BOTTOM_SPACER_KEY = "__chat_bottom_spacer"
