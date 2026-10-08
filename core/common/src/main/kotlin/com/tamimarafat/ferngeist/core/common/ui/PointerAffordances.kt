package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/**
 * Shows the hand ("link") cursor while a mouse hovers this node.
 *
 * Android gives a pointer no affordance of its own, so a clickable surface is
 * indistinguishable from a static one until you press it. [enabled] carries the caller's own
 * eligibility, so a control that is currently disabled keeps the default cursor.
 */
fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    this.then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)

/**
 * Runs [onClick] for a secondary (right) mouse-button press on this node, and swallows the press
 * so the surface underneath does not react to it as well.
 *
 * Why [PointerEventPass.Initial]: on Android, Compose's own `clickable` does **not** filter out
 * non-primary buttons — `firstDownRefersToPrimaryMouseButtonOnly()` is false in the pinned
 * foundation, so a right-click reaches `clickable` as an ordinary primary down and would fire the
 * element's normal action. Consuming on the way *down* the tree means `clickable`,
 * `combinedClickable` (long press) and any parent gesture only ever see an already-consumed press
 * and drop it. Nothing else in the chain handles that pass, so this cannot steal a left click.
 *
 * The release is consumed too, so no press or ripple state is left running behind whatever the
 * callback opened, and any event in between is consumed while the secondary button stays down so a
 * right-drag cannot pan or scroll the surface under the pointer.
 *
 * Right-click is the mouse equivalent of a long press: a
 * `combinedClickable(onLongClick = …)` should get the same lambda here, under the same condition
 * that gates the long press.
 */
@Composable
fun Modifier.onSecondaryClick(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return this.then(
        if (!enabled) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    // The pointer the secondary press started on. A mouse is always alone in
                    // `changes`, but a finger already down must not be mistaken for it, and only
                    // this pointer is consumed so the others keep their own gestures.
                    var secondaryPointer: PointerId? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.firstOrNull { it.id == secondaryPointer }
                        if (pressed == null) {
                            val down =
                                event.changes.firstOrNull {
                                    it.changedToDown() && event.buttons.isSecondaryPressed
                                } ?: continue
                            secondaryPointer = down.id
                            down.consume()
                            currentOnClick()
                        } else {
                            pressed.consume()
                            if (pressed.changedToUp() || !event.buttons.isSecondaryPressed) {
                                secondaryPointer = null
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Long press for a node that wraps something which consumes the press itself.
 *
 * A tap detector only starts on an unconsumed down, and a Material button consumes its own press
 * from inside, so a `combinedClickable` on the button's modifier never sees it and its long press
 * is dead. Watching the initial pass runs this node before the wrapped content, which does not
 * depend on that order at all; the rest of the press is then swallowed so the button's own click
 * does not also fire behind whatever the long press opened.
 *
 * [enabled] carries the caller's own eligibility, and the callback is read through the latest-value
 * holder so a lambda that changes identity on recomposition cannot restart the detector and drop a
 * press already in flight.
 */
@Composable
fun Modifier.initialPassLongPress(
    enabled: Boolean = true,
    onLongPress: () -> Unit,
): Modifier {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return this.then(
        if (!enabled) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A secondary press belongs to onSecondaryClick.
                    if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                    val releasedEarly =
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var pressed = true
                            var travelled = 0f
                            // A press that wanders past touch slop is a scroll or a drag, not a hold.
                            while (pressed && travelled <= viewConfiguration.touchSlop) {
                                val change =
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes
                                        .firstOrNull { it.id == down.id }
                                        ?: continue
                                pressed = change.pressed
                                travelled += change.positionChange().getDistance()
                            }
                        }
                    if (releasedEarly != null) return@awaitEachGesture
                    currentOnLongPress()
                    // Swallow the rest of this pointer's press, release included; other pointers
                    // (a second finger) keep their own gestures.
                    var pressed = true
                    while (pressed) {
                        val change =
                            awaitPointerEvent(PointerEventPass.Initial)
                                .changes
                                .firstOrNull { it.id == down.id }
                                ?: continue
                        pressed = change.pressed
                        change.consume()
                    }
                }
            }
        },
    )
}
