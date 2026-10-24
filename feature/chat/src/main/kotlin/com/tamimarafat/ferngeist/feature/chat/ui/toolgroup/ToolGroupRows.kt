package com.tamimarafat.ferngeist.feature.chat.ui.toolgroup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.markdown.RunReveal
import com.tamimarafat.ferngeist.feature.chat.markdown.rememberReducedMotion
import com.tamimarafat.ferngeist.feature.chat.ui.CONNECTOR_MS
import com.tamimarafat.ferngeist.feature.chat.ui.DiffSummaryRow
import com.tamimarafat.ferngeist.feature.chat.ui.FileChip
import com.tamimarafat.ferngeist.feature.chat.ui.ROW_REVEAL_MS
import com.tamimarafat.ferngeist.feature.chat.ui.RailRow
import com.tamimarafat.ferngeist.feature.chat.ui.files.diffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.filePath
import com.tamimarafat.ferngeist.feature.chat.ui.files.titleWithFile
import com.tamimarafat.ferngeist.feature.chat.ui.files.titleWithoutFile
import com.tamimarafat.ferngeist.feature.chat.ui.iconLeft
import com.tamimarafat.ferngeist.feature.chat.ui.iconSize
import com.tamimarafat.ferngeist.feature.chat.ui.message.ToolCallStatusIndicator
import com.tamimarafat.ferngeist.feature.chat.ui.rowHeight
import com.tamimarafat.ferngeist.feature.chat.ui.textGap
import kotlin.math.min

@Composable
internal fun RailRow.description(): String =
    call?.let { it.titleWithFile() + " " + (it.status?.name?.lowercase() ?: "unknown") }
        ?: stringResource(R.string.chat_reasoning_desc)

@Composable
internal fun RailRowFrame(
    reveal: Animatable<Float, AnimationVector1D>,
    description: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .handCursor()
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .padding(start = iconLeft)
                // Content is readable before its line lands: expo over 360ms against the line's 480ms.
                .graphicsLayer {
                    alpha = EaseOutExpo.transform((reveal.value * CONNECTOR_MS / ROW_REVEAL_MS).coerceAtMost(1f))
                },
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowScope.ToolRowContent(
    call: ToolCallDisplay,
    showStats: Boolean,
) {
    Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
        ToolCallStatusIndicator(call)
    }
    Spacer(modifier = Modifier.width(textGap))
    val file = call.filePath()
    if (file == null) {
        Text(
            text = call.title.ifBlank { stringResource(R.string.chat_tool_call) },
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    } else {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            call.titleWithoutFile().takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            FileChip(file, modifier = Modifier.weight(1f, fill = false))
        }
    }
    if (!call.permissionOptions.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.chat_awaiting_permission),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    } else if (showStats) {
        DiffSummaryRow(call.diffs(), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Reasoning row: live single-line tail while it streams, "Show Reasoning" once settled. No shimmer. */
@Composable
internal fun RowScope.ThoughtRowContent(
    thoughtText: String,
    thinking: Boolean,
) {
    if (!thinking) {
        Text(
            text = stringResource(R.string.chat_show_reasoning),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        return
    }
    // The line is bound to a reveal cursor, not straight to the text: reasoning arrives in a few
    // large chunks a second, and swapping the whole line per chunk reads as popping. The cursor
    // is rate-capped, so the tail glides out at a readable pace instead of jumping chunk to chunk.
    val collapsed = remember(thoughtText) { collapseThought(thoughtText) }
    val reveal = remember { RunReveal(startHidden = true, maxCharsPerSecond = THOUGHT_CHARS_PER_SECOND) }
    SideEffect { reveal.target = collapsed.length }
    val reducedMotion = rememberReducedMotion()
    LaunchedEffect(reveal) { reveal.run(reducedMotion) }
    val shown = reveal.shown.toInt()
    val preview = remember(collapsed, shown) { liveThoughtPreview(collapsed, shown) }
    // Anchored to the newest character, not the start: ellipsis-at-end would show the oldest text
    // of the window and hop a character at a time. The fractional cursor slides the line between
    // whole characters, so the tail scrolls by pixels rather than stepping.
    val fontSize = MaterialTheme.typography.bodySmall.fontSize
    val fontSizePx = with(LocalDensity.current) { fontSize.toPx() }
    val charWidthPx = fontSizePx * AVG_CHAR_EM
    var overflowing by remember { mutableStateOf(false) }
    Text(
        // Until the first characters release the row says what it is, rather than sitting empty.
        text = preview.ifBlank { stringResource(R.string.chat_reasoning) },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier =
            Modifier
                .weight(1f)
                .clipToBounds()
                .liveThoughtFade { overflowing }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
                    overflowing = placeable.width > constraints.maxWidth
                    layout(constraints.maxWidth, placeable.height) {
                        val x = (constraints.maxWidth - placeable.width).coerceAtMost(0)
                        placeable.placeWithLayer(x, 0) {
                            // Never slides past the start edge: until the line overflows it is
                            // left-aligned and still, instead of sawtoothing a character per release.
                            val slide = (1f - (reveal.shown - shown).coerceIn(0f, 1f)) * charWidthPx
                            translationX = min(slide, -x.toFloat())
                        }
                    }
                },
    )
}

/** Collapses a reasoning run to one line: runs of whitespace become single spaces. */
internal fun collapseThought(thoughtText: String): String = thoughtText.replace(whitespaceRun, " ").trim()

/**
 * The newest [maxChars] of the first [shown] characters of an already-collapsed run. Taking the tail
 * of the released prefix keeps the newest tokens on screen, and running it over the collapsed string
 * lets the reveal cursor advance a frame at a time without re-scanning the whole run each frame.
 */
internal fun liveThoughtPreview(
    collapsed: String,
    shown: Int,
    maxChars: Int = THOUGHT_PREVIEW_CHARS,
): String {
    val end = shown.coerceIn(0, collapsed.length)
    return collapsed.substring((end - maxChars).coerceAtLeast(0), end)
}

internal const val THOUGHT_PREVIEW_CHARS = 200

/** Reading pace for the one-line tail: a burst of reasoning queues behind it instead of flashing past. */
internal const val THOUGHT_CHARS_PER_SECOND = 25f

/** Rough width of a body character in ems, to size the sub-character slide. */
internal const val AVG_CHAR_EM = 0.5f

internal val whitespaceRun = Regex("\\s+")

/** Fades the cut edges so tokens dissolve instead of hard-clipping. */
internal fun Modifier.liveThoughtFade(
    fadeFraction: Float = 0.25f,
    fadeStart: () -> Boolean,
): Modifier =
    // Offscreen so the mask below cuts only this text, not the bubble drawn behind it.
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val edge = fadeFraction.coerceIn(0f, 0.45f)
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        0f to if (fadeStart()) Color.Transparent else Color.Black,
                        edge to Color.Black,
                        (1f - edge) to Color.Black,
                        1f to Color.Transparent,
                    ),
                blendMode = BlendMode.DstIn,
            )
        }
