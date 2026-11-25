package com.tamimarafat.ferngeist.core.common.ui.copy

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COPIED_MILLIS = 1_500L

/** Copies text to the clipboard and reports [copied] for a moment afterwards. */
@Stable
class CopyState internal constructor(
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
) {
    var copied by mutableStateOf(false)
        private set

    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
            copied = true
        }
    }

    internal suspend fun resetAfterDelay() {
        delay(COPIED_MILLIS)
        copied = false
    }
}

@Composable
fun rememberCopyState(): CopyState {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val state = remember(clipboard, scope) { CopyState(clipboard, scope) }
    LaunchedEffect(state.copied) {
        if (state.copied) state.resetAfterDelay()
    }
    return state
}

/** Copy glyph that cross-fades to a check in [doneTint] once the text is on the clipboard. */
@Composable
fun CopyGlyph(
    copied: Boolean,
    size: Dp,
    doneTint: Color,
    modifier: Modifier = Modifier,
) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = copied,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "copied",
    ) { done ->
        Icon(
            imageVector = if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = if (done) doneTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size),
        )
    }
}
