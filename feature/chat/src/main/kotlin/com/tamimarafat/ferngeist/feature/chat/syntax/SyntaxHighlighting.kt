package com.tamimarafat.ferngeist.feature.chat.syntax

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import com.tamimarafat.ferngeist.core.common.ui.SyntaxColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Process-wide highlighter.
 *
 * The lazy tokenizer is created inside a background context: the first construction measured
 * ~68 ms on desktop JVM and would be a visible frame on a phone. Nothing here touches the
 * tokenizer from composition.
 */
internal object SyntaxHighlighting {
    private val highlighter by lazy { SyntaxHighlighter() }

    suspend fun highlight(
        code: String,
        label: String?,
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): HighlightedCode? = withContext(defaultDispatcher) { highlighter.highlight(code, label) }

    suspend fun highlightLines(
        code: String,
        label: String?,
        colors: SyntaxColors,
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<AnnotatedString>? {
        val highlighted = highlight(code, label, defaultDispatcher) ?: return null
        return withContext(defaultDispatcher) {
            buildSyntaxLines(highlighted.text, highlighted.tokens, colors)
        }
    }
}

/**
 * Tokens for [code], tokenized in the background while a fence streams and debounced so a burst
 * of chunks does not queue one parse per chunk.
 *
 * The previous result is kept across key changes on purpose: [styledCode] applies it only when
 * the new text is an append of the tokenized text, so the settled prefix keeps its colors while
 * the tail arrives.
 */
@Composable
internal fun rememberHighlightedCode(
    code: String,
    label: String?,
    streaming: Boolean,
): HighlightedCode? {
    var highlighted by remember { mutableStateOf<HighlightedCode?>(null) }
    LaunchedEffect(code, label, streaming) {
        if (label == null) {
            highlighted = null
            return@LaunchedEffect
        }
        if (streaming && code.length > SyntaxHighlighter.STREAMING_MAX_CHARS) {
            highlighted = null
            return@LaunchedEffect
        }
        if (streaming && highlighted != null) delay(SyntaxHighlighter.STREAM_DEBOUNCE_MS)
        SyntaxHighlighting.highlight(code, label)?.let { highlighted = it }
    }
    return highlighted
}

/**
 * Applies [highlighted] to [code] only when it is safe: same label, and [code] is the tokenized
 * text or an append of it. Anything else renders plain, because reusing spans from different
 * text would color the wrong characters.
 */
internal fun styledCode(
    code: String,
    highlighted: HighlightedCode?,
    label: String?,
    colors: SyntaxColors,
): AnnotatedString {
    if (highlighted == null || label == null) return AnnotatedString(code)
    if (highlighted.label != label || !code.startsWith(highlighted.text)) return AnnotatedString(code)
    return buildSyntaxAnnotatedString(code, highlighted.tokens, colors)
}
