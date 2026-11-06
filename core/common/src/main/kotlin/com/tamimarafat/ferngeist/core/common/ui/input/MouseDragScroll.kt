package com.tamimarafat.ferngeist.core.common.ui.input

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Compose scrollables exclude mouse drags; leave touch gestures to their native scroll handling.
 * Claims the drag on the first move: Compose's mouse slop is sub-pixel anyway, and waiting for
 * horizontal slop loses a jittery first move to vertical draggable ancestors (the collapsing app bar).
 */
@Composable
fun Modifier.mouseDragScroll(state: ScrollableState): Modifier {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
    return pointerInput(state, direction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            drag(down.id) { change ->
                state.dispatchRawDelta(change.positionChange().x * direction)
                change.consume()
            }
        }
    }
}
