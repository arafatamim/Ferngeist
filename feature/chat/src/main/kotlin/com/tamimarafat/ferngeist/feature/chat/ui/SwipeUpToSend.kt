package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Lets [content] be flicked upward: released past [SEND_THRESHOLD] it fires [onSend], short of
 * it it springs back. It only travels up, fading as it nears the threshold.
 *
 * ponytail: it claims every vertical drag, so an overflowing queue scrolls only from its gaps;
 * hand downward drags to the scroll via nestedScroll if that ever bites.
 */
@Composable
internal fun SwipeUpToSend(
    enabled: Boolean,
    onSend: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { SEND_THRESHOLD.toPx() }
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    translationY = offset.value
                    alpha = 1f - (offset.value / -threshold).coerceIn(0f, 1f) * FADE_AT_THRESHOLD
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (offset.value <= -threshold) onSend()
                            scope.launch { offset.animateTo(0f, spring()) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dragAmount).coerceAtMost(0f)) }
                    }
                },
    ) { content() }
}

private val SEND_THRESHOLD = 56.dp
private const val FADE_AT_THRESHOLD = 0.6f
