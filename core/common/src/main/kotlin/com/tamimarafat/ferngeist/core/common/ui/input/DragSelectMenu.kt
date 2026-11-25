package com.tamimarafat.ferngeist.core.common.ui.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback

private const val DRAG_SELECT_LOG_TAG = "DragSelect"

/**
 * Press-drag-release for a dropdown: dragging off the anchor opens the menu, dragging onto an item
 * highlights it, releasing picks it. A plain tap is left to the anchor's own click, which opens it.
 *
 * Hit-testing runs in screen space because the menu is a popup, which has its own window.
 */
@Stable
class DragSelectMenuState internal constructor(
    private val haptics: HapticFeedback,
    internal val onExpandedChange: (Boolean) -> Unit,
) {
    private val items = mutableMapOf<MutableInteractionSource, DragSelectItem>()
    private var hovered: DragSelectItem? = null
    private var hoverPress: PressInteraction.Press? = null
    internal var anchor: LayoutCoordinates? = null

    internal fun register(item: DragSelectItem) {
        android.util.Log.d(DRAG_SELECT_LOG_TAG, "register ${item.width}x${item.height}")
        items[item.interactionSource] = item
    }

    // A previous opening's items are detached; drop them before this one registers its own.
    internal fun open() {
        items.clear()
        onExpandedChange(true)
    }

    /** Moves the highlight to the item under [screenPoint], pressing it as a finger would. */
    internal fun hover(screenPoint: Offset?) {
        val next = screenPoint?.let(::itemAt)
        android.util.Log.d(
            DRAG_SELECT_LOG_TAG,
            "hover screen=$screenPoint items=${items.size} hit=${next != null} " +
                items.values.joinToString { item ->
                    if (item.coordinates.isAttached) {
                        "[attached ${item.coordinates.localToScreen(Offset.Zero)} ${item.width}x${item.height}]"
                    } else {
                        "[detached]"
                    }
                },
        )
        if (next?.first === hovered) return
        endPress(PressInteraction::Cancel)
        next?.let { (item, local) ->
            val press = PressInteraction.Press(local)
            item.interactionSource.tryEmit(press)
            hovered = item
            hoverPress = press
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    /** Picks the highlighted item; false when the finger was released over nothing. */
    internal fun commit(): Boolean {
        val item = hovered ?: return false
        endPress(PressInteraction::Release)
        item.onSelect()
        return true
    }

    private fun endPress(end: (PressInteraction.Press) -> PressInteraction) {
        val item = hovered ?: return
        hoverPress?.let { item.interactionSource.tryEmit(end(it)) }
        hovered = null
        hoverPress = null
    }

    private fun itemAt(screenPoint: Offset): Pair<DragSelectItem, Offset>? =
        items.values.firstNotNullOfOrNull { item ->
            item.coordinates
                .takeIf { it.isAttached }
                ?.screenToLocal(screenPoint)
                ?.takeIf { local -> local.x in 0f..item.width && local.y in 0f..item.height }
                ?.let { item to it }
        }
}

internal class DragSelectItem(
    val interactionSource: MutableInteractionSource,
    val coordinates: LayoutCoordinates,
    val onSelect: () -> Unit,
) {
    val width = coordinates.size.width.toFloat()
    val height = coordinates.size.height.toFloat()
}

@Composable
fun rememberDragSelectMenuState(onExpandedChange: (Boolean) -> Unit): DragSelectMenuState {
    val haptics = LocalHapticFeedback.current
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    return remember(haptics) { DragSelectMenuState(haptics) { currentOnExpandedChange(it) } }
}

/**
 * The button that opens the menu; it keeps the whole gesture, even while the finger is over the popup.
 * Nothing above it may change when the menu opens: a detached ancestor makes Compose cancel the gesture.
 */
fun Modifier.dragSelectMenuAnchor(state: DragSelectMenuState): Modifier =
    onGloballyPositioned { state.anchor = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                android.util.Log.d(DRAG_SELECT_LOG_TAG, "down at ${down.position}")
                var finished = false
                try {
                    val dragged = trackUntilRelease(down, state, viewConfiguration.touchSlop)
                    val committed = state.commit()
                    android.util.Log.d(DRAG_SELECT_LOG_TAG, "up dragged=$dragged committed=$committed")
                    // A drag released over nothing dismisses; a tap leaves the menu open to browse.
                    if (!committed && dragged) state.onExpandedChange(false)
                    finished = true
                } finally {
                    if (!finished) android.util.Log.d(DRAG_SELECT_LOG_TAG, "gesture CANCELLED")
                    state.hover(null)
                }
            }
        }

/** Follows [down] until it lifts, highlighting what it passes over; true if it left touch slop. */
private suspend fun AwaitPointerEventScope.trackUntilRelease(
    down: PointerInputChange,
    state: DragSelectMenuState,
    touchSlop: Float,
): Boolean {
    var dragged = false
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        android.util.Log.d(DRAG_SELECT_LOG_TAG, "move change=${change?.position} pressed=${change?.pressed}")
        if (change == null || !change.pressed) return dragged
        if (!dragged && (change.position - down.position).getDistance() > touchSlop) {
            dragged = true
            state.open()
        }
        if (dragged) {
            change.consume()
            state.hover(state.anchor?.takeIf { it.isAttached }?.localToScreen(change.position))
        }
    }
}

/**
 * A menu item [state] can drag-select. Pass the same [interactionSource] to the item so the
 * drag highlight is its own press indication.
 */
fun Modifier.dragSelectMenuItem(
    state: DragSelectMenuState,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onSelect: () -> Unit,
): Modifier =
    if (enabled) {
        onGloballyPositioned { state.register(DragSelectItem(interactionSource, it, onSelect)) }
    } else {
        this
    }
