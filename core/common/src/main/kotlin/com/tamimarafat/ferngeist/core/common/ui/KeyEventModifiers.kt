package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val SUBMIT_SHORTCUT_KEYS = setOf(Key.Enter, Key.NumPadEnter)

/**
 * Runs [onEscape] and consumes the key when Escape is pressed while this node — or one of its
 * descendants — holds focus, so a surface that spans a subtree can be dismissed from anywhere
 * inside it.
 *
 * Key events travel the focus path only, so a subtree that holds no focus never sees this: the
 * surface needs a focus target inside it (or on it) for the handler to fire — see
 * [rememberEscapeFocusAnchor] for a surface whose content is not focusable.
 *
 * Preview dispatch rather than bubble: the key has to be caught on the way down, before a focused
 * child can claim it.
 */
fun Modifier.onEscapeKey(
    enabled: Boolean = true,
    onEscape: () -> Unit,
): Modifier =
    this.then(
        if (!enabled) {
            Modifier
        } else {
            Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onEscape()
                    true
                } else {
                    false
                }
            }
        },
    )

/**
 * Runs [onSubmit] and consumes the key for Ctrl+Enter and Meta/Cmd+Enter. [enabled] carries the
 * caller's own eligibility, so the shortcut can never fire where the control it stands in for is
 * disabled, and auto-repeat is ignored so holding the key down cannot submit repeatedly.
 *
 * A composer needs this because Compose's key mapping turns Enter into "insert newline" for any
 * multi-line field and only routes it to the field's KeyboardActions when the field is single-line.
 * Plain Enter is left untouched and still inserts a newline.
 */
fun Modifier.onSubmitShortcut(
    enabled: Boolean = true,
    onSubmit: () -> Unit,
): Modifier =
    this.then(
        if (!enabled) {
            Modifier
        } else {
            Modifier.onPreviewKeyEvent { event ->
                if (event.isSubmitShortcut()) {
                    onSubmit()
                    true
                } else {
                    false
                }
            }
        },
    )

private fun KeyEvent.isSubmitShortcut(): Boolean {
    val submitKey = (isCtrlPressed || isMetaPressed) && key in SUBMIT_SHORTCUT_KEYS
    // Auto-repeat is ignored: holding the shortcut down must not submit repeatedly.
    return type == KeyEventType.KeyDown && nativeKeyEvent.repeatCount == 0 && submitKey
}

/**
 * Focus anchor for a surface that has no focusable content of its own: pair it with a
 * [focusRequester] and [focusTarget] on the surface root.
 *
 * Key events are delivered only to the active focus target and its ancestors, so an Escape handler
 * on a container never fires while the container holds no focus — text-only sheet content (or a
 * list that renders its empty state) would swallow the key before it reaches the handler. Taking
 * focus on open puts the container itself on that path.
 */
@Composable
fun rememberEscapeFocusAnchor(): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return focusRequester
}

/**
 * Dismisses a sheet the way its own drag, outside-tap and back paths do: the sheet animates to
 * hidden first and [onDismiss] runs once it is off screen. Calling [onDismiss] directly would pull
 * the sheet out of the composition mid-animation.
 *
 * A second call while that animation is still running is a no-op, so a repeated Escape cannot
 * cancel the first hide and run [onDismiss] twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun CoroutineScope.dismissSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    if (sheetState.targetValue == SheetValue.Hidden) return
    launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
}
