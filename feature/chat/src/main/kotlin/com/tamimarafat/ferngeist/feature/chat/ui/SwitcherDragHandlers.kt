package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.SlideDirection
import com.tamimarafat.ferngeist.feature.chat.SwipeThresholds
import com.tamimarafat.ferngeist.feature.chat.SwitchSlide
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
import com.tamimarafat.ferngeist.feature.chat.resolveSwipeTarget
import com.tamimarafat.ferngeist.feature.chat.switcherNeighbors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

// Commit rules for the Chrome-style drag switch: past a quarter of the screen
// width — or flung fast regardless of distance — the chat flies off and the
// target session opens; anything shorter springs back.
private const val SWITCHER_COMMIT_FRACTION = 0.25f
private const val SWITCHER_FLING_VELOCITY_PX_PER_SEC = 1000f

/** Bubble drag callbacks: everything the Chrome-style switch needs, nothing the row does. */
internal data class SwitcherDragHandlers(
    val onDragStart: () -> Unit,
    val onDragDelta: (Float) -> Unit,
    val onDragStopped: (totalPx: Float, velocityPxPerSec: Float) -> Unit,
    val onDragCancel: () -> Unit,
)

/**
 * Neighbors in stable recency order plus the drag-to-commit wiring: threshold
 * from the screen width, release commits the swipe target (travel picks the
 * direction) or springs back. Lives outside [ChatComposerHost] so the row
 * stays a row — neighbor lookup and gesture math live here.
 */
@Composable
internal fun rememberSwitcherDragHandlers(
    switcherState: SwitcherUiState,
    chatOffset: Animatable<Float, AnimationVector1D>,
    coroutineScope: CoroutineScope,
    onSwitchSession: OnSwitchSession,
): SwitcherDragHandlers {
    val (previousSession, nextSession) = remember(switcherState) { switcherNeighbors(switcherState.groups) }
    // Window width in px (not Configuration.screenWidthDp: its inset behavior
    // varies by target SDK and it rounds to Dp, so it mismeasures the drag).
    val screenWidthPx = LocalWindowInfo.current.containerSize.width * 1f
    val commitThresholdPx = screenWidthPx * SWITCHER_COMMIT_FRACTION
    val haptics = LocalHapticFeedback.current
    val crossedCommit = remember { mutableStateOf(false) }
    return remember(previousSession, nextSession, screenWidthPx, chatOffset, coroutineScope, onSwitchSession) {
        SwitcherDragHandlers(
            onDragStart = {
                crossedCommit.value = false
                coroutineScope.launch { chatOffset.stop() }
            },
            onDragDelta = { totalPx ->
                coroutineScope.launch {
                    chatOffset.snapTo(totalPx.coerceIn(-screenWidthPx, screenWidthPx))
                }
                // Tick once as the drag crosses the commit point, so the release is a decision the
                // finger already felt. Latches so a long drag past the threshold buzzes once, not
                // on every frame.
                val pastCommit = abs(totalPx) >= commitThresholdPx
                if (pastCommit != crossedCommit.value) {
                    crossedCommit.value = pastCommit
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onDragStopped = { totalPx, velocityPxPerSec ->
                val target =
                    resolveSwipeTarget(
                        totalPx = totalPx,
                        velocityPxPerSec = velocityPxPerSec,
                        thresholds =
                            SwipeThresholds(
                                commitPx = commitThresholdPx,
                                flingPxPerSec = SWITCHER_FLING_VELOCITY_PX_PER_SEC,
                            ),
                        previous = previousSession,
                        next = nextSession,
                    )
                if (target != null) {
                    // No fly-off animation here: navigate immediately and let
                    // the directional exit transition continue the drag from
                    // the finger's release point — one motion, no blank gap.
                    onSwitchSession(target, switchSlide(totalPx, velocityPxPerSec, chatOffset.value, screenWidthPx))
                } else {
                    springBack(chatOffset, coroutineScope)
                }
            },
            onDragCancel = { springBack(chatOffset, coroutineScope) },
        )
    }
}

/** Where the drag let go: the direction it was travelling and how much of the screen it had crossed. */
private fun switchSlide(
    totalPx: Float,
    velocityPxPerSec: Float,
    offsetPx: Float,
    screenWidthPx: Float,
): SwitchSlide {
    val direction = if (totalPx != 0f) totalPx else velocityPxPerSec
    return SwitchSlide(
        direction = if (direction < 0) SlideDirection.LEFT else SlideDirection.RIGHT,
        travelled = (abs(offsetPx) / screenWidthPx).coerceIn(0f, 1f),
    )
}

private fun springBack(
    chatOffset: Animatable<Float, AnimationVector1D>,
    coroutineScope: CoroutineScope,
) {
    coroutineScope.launch {
        chatOffset.animateTo(
            targetValue = 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
        )
    }
}
