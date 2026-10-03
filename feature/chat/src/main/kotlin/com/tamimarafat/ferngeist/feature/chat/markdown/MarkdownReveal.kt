package com.tamimarafat.ferngeist.feature.chat.markdown

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.adamglin.compose.markdown.core.model.BlockNode
import com.adamglin.compose.markdown.core.model.InlineNode
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * The streaming reveal: how much of a parsed run is visible at a given frame, and how the leading
 * edge fades in. Lives in the composition, not the ViewModel — see docs/adr/0001.
 *
 * Positions are counted in *rendered* characters (what the text composables draw, not markdown
 * source), walked in document order. Each leaf knows its offset into that sequence and draws the
 * slice the cursor has reached.
 */

/** How long one revealed character takes to go from invisible to solid. */
internal const val REVEAL_FADE_MS = 180L

private const val MILLIS_PER_SECOND = 1000f

/** Slowest the cursor moves, so a small backlog still finishes promptly. */
private const val REVEAL_MIN_CHARS_PER_SECOND = 60f

/**
 * Time constant the cursor closes on the target with. The rate is proportional to the backlog, so a
 * steady stream lags by about this long, a burst is spread out rather than landing in a frame, and
 * the end of a turn is never left typing for long after the model stopped.
 */
private const val REVEAL_CATCH_UP_SECONDS = 0.35f

/** Advances the cursor by one frame of [dtSeconds]. Never passes [target]. */
internal fun revealStep(
    shown: Float,
    target: Int,
    dtSeconds: Float,
): Float {
    val backlog = target - shown
    if (backlog <= 0f) return shown
    val rate = max(REVEAL_MIN_CHARS_PER_SECOND, backlog / REVEAL_CATCH_UP_SECONDS)
    return min(target.toFloat(), shown + rate * dtSeconds)
}

/**
 * How much of [text] to lay out when the cursor is [local] characters into it: rounded up to the end
 * of the current word (or line, for code). Layout then changes once per word rather than every frame,
 * and the not-yet-reached rest of the word is laid out but drawn at zero alpha, so a word never
 * reflows onto the next line halfway through appearing.
 */
internal fun revealCut(
    text: CharSequence,
    local: Float,
    byLine: Boolean,
): Int {
    if (local <= 0f) return 0
    var end = ceil(local).toInt()
    if (end >= text.length) return text.length
    while (end < text.length && !text[end - 1].isBoundary(byLine)) end++
    return end
}

private fun Char.isBoundary(byLine: Boolean): Boolean = if (byLine) this == '\n' else isWhitespace()

/**
 * Rendered length of [this] block — must agree with what [MarkdownBlocks] draws for it. One branch
 * per BlockNode subtype, mirroring MarkdownBlock; splitting it would hide that pairing.
 */
@Suppress("CyclomaticComplexMethod")
internal fun BlockNode.revealLength(): Int =
    when (this) {
        is BlockNode.Paragraph -> children.inlineLength()
        is BlockNode.Heading -> children.inlineLength()
        is BlockNode.TableCell -> children.inlineLength()
        is BlockNode.TableRow -> cells.sumOf { it.revealLength() }
        is BlockNode.TableBlock -> header.revealLength() + rows.sumOf { it.revealLength() }
        is BlockNode.FencedCodeBlock -> literal.length
        is BlockNode.RawTextBlock -> literal.length
        is BlockNode.UnsupportedBlock -> literal.length
        is BlockNode.MathBlock -> latex.length
        is BlockNode.ThematicBreak -> 1
        is BlockNode.Document -> children.sumOf { it.revealLength() }
        is BlockNode.BlockQuote -> children.sumOf { it.revealLength() }
        is BlockNode.ListBlock -> items.sumOf { it.revealLength() }
        is BlockNode.ListItem -> children.sumOf { it.revealLength() }
    }

/** Mirrors `appendInlineNodes` in MarkdownBlocks.kt; the two must count the same characters. */
private fun List<InlineNode>.inlineLength(): Int =
    sumOf { node ->
        when (node) {
            is InlineNode.CodeSpan -> node.literal.length
            is InlineNode.Emphasis -> node.children.inlineLength()
            is InlineNode.HardBreak -> 1
            is InlineNode.Link -> node.children.inlineLength()
            is InlineNode.SoftBreak -> 1
            is InlineNode.Image -> node.alt.inlineLength()
            is InlineNode.Strikethrough -> node.children.inlineLength()
            is InlineNode.Strong -> node.children.inlineLength()
            is InlineNode.Text -> node.literal.length
            is InlineNode.UnsupportedInline -> node.literal.length
            is InlineNode.MathSpan -> node.latex.length
        }
    }

/**
 * One run's reveal cursor.
 *
 * [shown] is how many rendered characters have been released. The fade is time-based per release
 * step rather than distance-based behind the cursor: a distance fade leaves the last characters
 * of every stall half-transparent until more text arrives, and lowering the cursor to fix that
 * would fade solid text back out.
 */
@Stable
internal class RunReveal(
    startHidden: Boolean,
) {
    private class Step(
        val from: Float,
        val to: Float,
        val atMs: Long,
    )

    /** False for a run composed for settled text: it starts fully shown and never animates. */
    val animates: Boolean = startHidden

    var target by mutableIntStateOf(0)

    /** A run composed for settled text starts past any target, so it never animates. */
    var shown by mutableFloatStateOf(if (startHidden) 0f else Float.MAX_VALUE)
        private set

    /** Frame time of the latest step. Read by the fade so it redraws every frame it is active. */
    private var nowMs by mutableLongStateOf(0L)

    /** Release steps still fading, oldest first. Plain list: [nowMs] carries the invalidation. */
    private val steps = ArrayDeque<Step>()

    var isFading by mutableStateOf(false)
        private set

    /** Nothing left to release or fade. Reads state, so composition and derived state track it. */
    val isSettled: Boolean
        get() = shown >= target && !isFading

    /** Characters before this index are fully opaque. */
    val opaqueBefore: Float
        get() {
            nowMs
            return steps.firstOrNull()?.from ?: shown
        }

    fun alphaAt(index: Int): Float {
        if (index >= shown) return 0f
        val step = steps.firstOrNull { index < it.to } ?: return 1f
        if (index < step.from) return 1f
        return ((nowMs - step.atMs).toFloat() / REVEAL_FADE_MS).coerceIn(0f, 1f)
    }

    fun advance(
        frameMs: Long,
        dtSeconds: Float,
    ) {
        val next = revealStep(shown, target, dtSeconds)
        if (next > shown) {
            steps.addLast(Step(shown, next, frameMs))
            shown = next
        }
        while (steps.isNotEmpty() && frameMs - steps.first().atMs >= REVEAL_FADE_MS) steps.removeFirst()
        isFading = steps.isNotEmpty()
        nowMs = frameMs
    }

    /** Shows everything with no animation, for reduced motion. */
    fun snapToTarget() {
        steps.clear()
        isFading = false
        shown = target.toFloat()
    }

    /** Drives the cursor from the frame clock until it settles, then waits for more text. */
    suspend fun run(reducedMotion: Boolean) {
        while (true) {
            snapshotFlow { shown < target }.first { it }
            if (reducedMotion) {
                snapToTarget()
                continue
            }
            var lastMs = withFrameMillis { it }
            while (!isSettled) {
                val frameMs = withFrameMillis { it }
                advance(frameMs, (frameMs - lastMs) / MILLIS_PER_SECOND)
                lastMs = frameMs
            }
        }
    }
}

/** True when the system animator scale is zero: reveal instantly. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Where one block sits in its run's reveal sequence. Null everywhere means "render it all". */
@Stable
internal data class BlockReveal(
    val run: RunReveal,
    val offset: Int,
)

/** Composes [content] only once the cursor has reached [reveal], so unreached blocks take no space. */
@Composable
internal fun RevealGate(
    reveal: BlockReveal?,
    content: @Composable () -> Unit,
) {
    val reached by remember(reveal) { derivedStateOf { reveal == null || reveal.run.shown > reveal.offset } }
    if (reached) content()
}

/**
 * Alpha for a block that renders whole (tables, rules): fades in when it first appears mid-reveal,
 * solid when composed for settled text. Started once on mount, so a later recomposition cannot
 * cancel it halfway.
 */
@Composable
internal fun rememberAtomicRevealAlpha(reveal: BlockReveal?): Animatable<Float, *> {
    val alpha = remember { Animatable(if (reveal == null || reveal.run.isSettled) 1f else 0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(REVEAL_FADE_MS.toInt())) }
    return alpha
}

/**
 * Text that appears up to the reveal cursor, the newest characters fading in.
 *
 * Layout is the slice cut at the next word (or line) boundary — so it changes once per word — and
 * the fade is drawn, so a frame of fade costs a redraw, not a relayout.
 */
@Composable
internal fun RevealText(
    text: AnnotatedString,
    style: TextStyle,
    reveal: BlockReveal?,
    modifier: Modifier = Modifier,
    byLine: Boolean = false,
) {
    if (reveal == null) {
        BasicText(text = text, style = style, modifier = modifier)
        return
    }
    val end by remember(text, reveal, byLine) {
        derivedStateOf { revealCut(text, reveal.run.shown - reveal.offset, byLine) }
    }
    if (end <= 0) return
    val visible = remember(text, end) { if (end >= text.length) text else text.subSequence(0, end) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = visible,
        style = style,
        onTextLayout = { layout = it },
        modifier =
            modifier
                .graphicsLayer {
                    // DstOut below must erase this text only, not whatever is drawn behind it.
                    compositingStrategy =
                        if (reveal.run.isFading) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }.drawWithContent {
                    drawContent()
                    layout?.let { drawRevealFade(it, reveal) }
                },
    )
}

/**
 * Erases the not-yet-solid part of [layout]: a gradient per line for characters still fading, and
 * full erasure for the laid-out rest of a word the cursor has not reached.
 *
 * ponytail: one linear gradient per line segment approximates the per-step alpha. Exact per-glyph
 * rects only if a fast catch-up visibly bands.
 */
private fun DrawScope.drawRevealFade(
    layout: TextLayoutResult,
    reveal: BlockReveal,
) {
    val run = reveal.run
    val length = layout.layoutInput.text.length
    val from = (run.opaqueBefore.toInt() - reveal.offset).coerceAtLeast(0)
    if (from >= length) return
    val shownLocal = (ceil(run.shown).toInt() - reveal.offset).coerceIn(from, length)
    eraseRange(layout, from, shownLocal) { reveal.run.alphaAt(reveal.offset + it) }
    eraseRange(layout, shownLocal, length) { 0f }
}

private inline fun DrawScope.eraseRange(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    alphaAt: (Int) -> Float,
) {
    var index = start
    while (index < end) {
        val line = layout.getLineForOffset(index)
        val lineEnd = min(end, layout.getLineEnd(line))
        if (lineEnd <= index) {
            index++
            continue
        }
        val first = layout.getBoundingBox(index)
        val last = layout.getBoundingBox(lineEnd - 1)
        val rtl = layout.getParagraphDirection(index) == ResolvedTextDirection.Rtl
        val startX = if (rtl) first.right else first.left
        val endX = if (rtl) last.left else last.right
        val top = layout.getLineTop(line)
        val left = min(startX, endX)
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors =
                        listOf(
                            Color.Black.copy(alpha = 1f - alphaAt(index)),
                            Color.Black.copy(alpha = 1f - alphaAt(lineEnd - 1)),
                        ),
                    startX = startX,
                    endX = endX,
                ),
            topLeft = Offset(left, top),
            size = Size(max(startX, endX) - left, layout.getLineBottom(line) - top),
            blendMode = BlendMode.DstOut,
        )
        index = lineEnd
    }
}
