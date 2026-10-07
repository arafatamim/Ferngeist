package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SegmentBlock

/*
 * Adjacent tool calls as one activity: a summary line that folds open into compact rows joined
 * by a rail, which draws itself as calls arrive. Ported from zeron's desktop transcript; see
 * docs/design/tool-call-rail.md for what was kept and what Compose already does.
 */

private val TRUNK_X = 12.dp
private val BEND_RADIUS = 6.dp
private val BRANCH_END_X = 28.dp
private val ICON_LEFT = 32.dp
private val ICON_SIZE = 24.dp
private val TEXT_GAP = 8.dp
private val ROW_HEIGHT = 36.dp
internal val RAIL_ROW_HEIGHT get() = ROW_HEIGHT
internal val RAIL_TEXT_START get() = ICON_LEFT
private val RAIL_STROKE = 1.5.dp

private const val CONNECTOR_MS = 480
private const val ROW_REVEAL_MS = 360
private const val FIRST_ROW_DELAY_MS = 90
private const val ROW_STAGGER_MS = 65
private const val CHEVRON_OPEN_DEGREES = 90f

// Phase windows on a row's connector progress. Each branch starts before its trunk lands, so
// there is no dead frame at the bend.
private const val FIRST_TRUNK_END = 0.62f
private const val FIRST_BRANCH_START = 0.58f
private const val TRUNK_END = 0.72f
private const val BRANCH_START = 0.68f

internal enum class ToolVerb(
    @param:PluralsRes val phrase: Int,
    // "twice" has no plural category of its own, so verbs counted in times carry it separately.
    @param:StringRes val twice: Int? = null,
    // Present-continuous variant while the group runs. Null keeps the past tense (FAILED already happened).
    @param:PluralsRes val runningPhrase: Int? = null,
    @param:StringRes val runningTwice: Int? = null,
) {
    THOUGHT(
        R.plurals.chat_tool_summary_thought,
        R.string.chat_tool_summary_thought_twice,
        R.plurals.chat_tool_summary_thought_running,
        R.string.chat_tool_summary_thought_running_twice,
    ),
    EXECUTE(R.plurals.chat_tool_summary_execute, runningPhrase = R.plurals.chat_tool_summary_execute_running),
    READ(R.plurals.chat_tool_summary_read, runningPhrase = R.plurals.chat_tool_summary_read_running),
    EDIT(R.plurals.chat_tool_summary_edit, runningPhrase = R.plurals.chat_tool_summary_edit_running),
    DELETE(R.plurals.chat_tool_summary_delete, runningPhrase = R.plurals.chat_tool_summary_delete_running),
    MOVE(R.plurals.chat_tool_summary_move, runningPhrase = R.plurals.chat_tool_summary_move_running),
    SEARCH(
        R.plurals.chat_tool_summary_search,
        R.string.chat_tool_summary_search_twice,
        R.plurals.chat_tool_summary_search_running,
        R.string.chat_tool_summary_search_running_twice,
    ),
    FETCH(R.plurals.chat_tool_summary_fetch, runningPhrase = R.plurals.chat_tool_summary_fetch_running),
    OTHER(R.plurals.chat_tool_summary_other, runningPhrase = R.plurals.chat_tool_summary_other_running),
    FAILED(R.plurals.chat_tool_summary_failed),
}

/** One resolved summary part: the phrase (or twice-string) for a verb and its count. */
internal data class SummaryPart(
    @PluralsRes val phrase: Int,
    @StringRes val twice: Int?,
    val count: Int,
)

/** Picks the past-tense or present-continuous phrase per verb. Pure, so JVM tests can cover it. */
internal fun summaryParts(
    verbs: List<ToolVerb>,
    running: Boolean,
): List<SummaryPart> =
    toolVerbCounts(verbs).map { (verb, count) ->
        if (running && verb.runningPhrase != null) {
            SummaryPart(verb.runningPhrase, verb.runningTwice, count)
        } else {
            SummaryPart(verb.phrase, verb.twice, count)
        }
    }

/** Items counted by verb, in the order each verb first appears, with FAILED always last. */
internal fun toolVerbCounts(verbs: List<ToolVerb>): List<Pair<ToolVerb, Int>> =
    verbs
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedBy { it.first == ToolVerb.FAILED }

/** A failed call did not do its verb, so it counts as FAILED instead. */
internal fun ToolCallDisplay.summaryVerb(): ToolVerb =
    if (status == ToolCallStatus.FAILED) {
        ToolVerb.FAILED
    } else {
        when (kind) {
            ToolKind.EXECUTE -> ToolVerb.EXECUTE
            ToolKind.READ -> ToolVerb.READ
            ToolKind.EDIT -> ToolVerb.EDIT
            ToolKind.DELETE -> ToolVerb.DELETE
            ToolKind.MOVE -> ToolVerb.MOVE
            ToolKind.SEARCH -> ToolVerb.SEARCH
            ToolKind.FETCH -> ToolVerb.FETCH
            // A THINK call renders no reasoning, so counting it as a thought would describe
            // nothing visible.
            ToolKind.THINK, ToolKind.SWITCH_MODE, ToolKind.OTHER, null -> ToolVerb.OTHER
        }
    }

/** One row on the rail: a tool call, or a run of reasoning. */
private class RailRow(
    val id: String,
    val call: ToolCallDisplay?,
    val thought: SegmentBlock.Run?,
    val thinking: Boolean,
) {
    val running: Boolean
        get() = thinking || call?.status == ToolCallStatus.PENDING || call?.status == ToolCallStatus.IN_PROGRESS
}

// A failed call's diff never landed, so it doesn't count toward the group's total.
private fun RailRow.landedDiffs(): List<ToolCallContent.Diff> =
    call?.takeIf { it.status != ToolCallStatus.FAILED }?.diffs().orEmpty()

/** The diffs' total, or null when there are none to count. */
@Composable
internal fun rememberDiffStats(diffs: List<ToolCallContent.Diff>): DiffStats? =
    remember(diffs) { diffs.takeIf { it.isNotEmpty() }?.computeDiffStats() }

/** One linear 0→1 clock per row. The connector and the row content read it through different easings. */
private class RowReveal(
    val clock: Animatable<Float, AnimationVector1D>,
    val delayMs: Int,
)

/**
 * @param liveSegmentId the message's last segment while it streams, so the thought still being
 *   written shows as running.
 */
@Composable
internal fun ToolCallGroup(
    items: List<SegmentBlock>,
    isStreaming: Boolean,
    isTail: Boolean,
    liveSegmentId: String?,
    onToolCallClick: (String) -> Unit,
    onThoughtClick: (String) -> Unit,
    foldsInFlight: MutableIntState,
    modifier: Modifier = Modifier,
) {
    val rows =
        items.mapNotNull { item ->
            when (item) {
                is SegmentBlock.Single -> item.segment.toolCall?.let { RailRow(item.key, it, null, thinking = false) }
                is SegmentBlock.Run ->
                    RailRow(
                        item.key,
                        null,
                        item,
                        thinking = item.segments.last().id == liveSegmentId,
                    )
                is SegmentBlock.Group -> null
            }
        }
    if (rows.isEmpty()) return

    val running = rows.any { it.running }
    // Done once nothing runs and the turn has moved past it. Settled-but-still-tail is not done:
    // the next call usually follows, and folding in between would flap.
    val done = !running && !(isStreaming && isTail)
    // Open while the sequence runs, so the rail can be watched drawing; folds once it completes,
    // as zeron does. History starts folded. A user's later toggle is left alone.
    var expanded by rememberSaveable { mutableStateOf(!done) }
    LaunchedEffect(done) { if (done) expanded = false }
    // A pending permission blocks the agent, so it is never hidden behind the fold.
    val open = expanded || rows.any { !it.call?.permissionOptions.isNullOrEmpty() }
    // Held here, outside the fold, so collapsing does not reset a row's reveal.
    val reveals = remember { HashMap<String, RowReveal>() }

    Column(modifier = modifier.fillMaxWidth()) {
        ToolGroupHeader(
            summary = toolSummary(rows.map { it.call?.summaryVerb() ?: ToolVerb.THOUGHT }, running),
            stats = rememberDiffStats(rows.flatMap { it.landedDiffs() }),
            open = open,
            active = isStreaming && running,
            onClick = { expanded = !open },
        )
        FoldBody(open = open, foldsInFlight = foldsInFlight) {
            ToolRail(rows, reveals, animate = isStreaming) { row ->
                // Any chunk of a thought resolves to the whole run in the sheet.
                val thought = row.thought
                if (thought != null) onThoughtClick(thought.segments.last().id) else onToolCallClick(row.id)
            }
        }
    }
}

/** A fully drawn rail behind [rowCount] rows of [RAIL_ROW_HEIGHT], for lists that share the tool rail's look. */
@Composable
internal fun SettledRail(
    rowCount: Int,
    content: @Composable () -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val painter = remember { RailPainter() }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind { with(painter) { draw(List(rowCount) { 1f }, railColor) } },
    ) {
        content()
    }
}

/**
 * The body under a foldable header, on the app's expressive motion scheme like every other expand.
 * While it animates it holds [foldsInFlight] up, so the bubble's own height spring stands aside.
 */
@Composable
internal fun FoldBody(
    open: Boolean,
    foldsInFlight: MutableIntState,
    content: @Composable () -> Unit,
) {
    val fold = remember { MutableTransitionState(open) }
    fold.targetState = open
    if (!fold.isIdle) {
        DisposableEffect(Unit) {
            foldsInFlight.intValue++
            onDispose { foldsInFlight.intValue-- }
        }
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visibleState = fold,
        enter =
            expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                fadeIn(motion.defaultEffectsSpec()),
        exit =
            shrinkVertically(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                fadeOut(motion.defaultEffectsSpec()),
    ) {
        content()
    }
}

@Composable
private fun toolSummary(
    verbs: List<ToolVerb>,
    running: Boolean,
): String =
    summaryParts(verbs, running)
        .map { (phrase, twice, count) ->
            twice?.takeIf { count == 2 }?.let { stringResource(it) }
                ?: pluralStringResource(phrase, count, count)
        }.joinToString(" · ")
        .replaceFirstChar { it.titlecase() }

/** Summary, diff total and a chevron that turns on the fold's spatial spring. */
@Composable
internal fun ToolGroupHeader(
    summary: String,
    stats: DiffStats?,
    open: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(
        targetValue = if (open) CHEVRON_OPEN_DEGREES else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "toolGroupChevron",
    )
    val brush = rememberShimmerTextBrush(isActive = active, baseColor = baseColor, labelPrefix = "toolGroup")
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The summary wraps freely; the diff total and chevron hold the end.
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall.copy(brush = brush),
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        stats?.let { DiffStatsRow(it, modifier = Modifier.padding(start = 8.dp)) }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.chat_tool_call_details_desc),
            tint = baseColor,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun ToolRail(
    rows: List<RailRow>,
    reveals: HashMap<String, RowReveal>,
    animate: Boolean,
    onRowClick: (RailRow) -> Unit,
) {
    // Rows first seen while the message is not streaming start drawn, so history and scroll-back
    // never animate. ponytail: scrolling back to a still-streaming message recomposes its item and
    // replays the draw; hoist the clocks out of the item if that ever matters.
    val baseDelay = if (reveals.isEmpty()) FIRST_ROW_DELAY_MS else 0
    var arrivals = 0
    val clocks =
        rows.map { row ->
            reveals.getOrPut(row.id) {
                if (animate) {
                    RowReveal(remember { Animatable(0f) }, baseDelay + ROW_STAGGER_MS * arrivals++)
                } else {
                    RowReveal(remember { Animatable(1f) }, 0)
                }
            }
        }
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val painter = remember { RailPainter() }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind { with(painter) { draw(clocks.map { it.clock.value }, railColor) } },
    ) {
        rows.forEachIndexed { index, row ->
            key(row.id) {
                val reveal = clocks[index]
                LaunchedEffect(reveal) {
                    reveal.clock.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(CONNECTOR_MS, delayMillis = reveal.delayMs, easing = LinearEasing),
                    )
                }
                RailRowFrame(reveal = reveal.clock, description = row.description(), onClick = { onRowClick(row) }) {
                    val call = row.call
                    if (call != null) ToolRowContent(call) else ThoughtRowContent(row.thinking)
                }
            }
        }
    }
}

@Composable
private fun RailRow.description(): String =
    call?.let { it.title + " " + (it.status?.name?.lowercase() ?: "unknown") }
        ?: stringResource(R.string.chat_reasoning_desc)

@Composable
private fun RailRowFrame(
    reveal: Animatable<Float, AnimationVector1D>,
    description: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .padding(start = ICON_LEFT)
                // Content is readable before its line lands: expo over 360ms against the line's 480ms.
                .graphicsLayer {
                    alpha = EaseOutExpo.transform((reveal.value * CONNECTOR_MS / ROW_REVEAL_MS).coerceAtMost(1f))
                },
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.ToolRowContent(call: ToolCallDisplay) {
    Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
        ToolCallStatusIndicator(call)
    }
    Spacer(modifier = Modifier.width(TEXT_GAP))
    Text(
        text = call.title.ifBlank { stringResource(R.string.chat_tool_call) },
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
    )
    if (!call.permissionOptions.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.chat_awaiting_permission),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    } else {
        DiffSummaryRow(call.diffs(), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Reasoning has no status icon: the branch leads straight into the label, which shimmers while the thought streams. */
@Composable
private fun RowScope.ThoughtRowContent(thinking: Boolean) {
    val color = LocalContentColor.current
    Text(
        text = stringResource(R.string.chat_reasoning),
        style =
            MaterialTheme.typography.bodySmall.copy(
                brush = rememberShimmerTextBrush(thinking, color, "railThought"),
            ),
        maxLines = 1,
        modifier = Modifier.weight(1f),
    )
}

/**
 * Draws the rail as one stroked path. One `drawPath` is one coverage mask, so the trunk and branch
 * never blend twice where they meet: that is what zeron's ribbon union buys, for free here.
 */
private class RailPainter {
    private val branch = Path()
    private val measure = PathMeasure()
    private val piece = Path()
    private val rail = Path()
    private var builtFor = 0f

    fun DrawScope.draw(
        progress: List<Float>,
        color: Color,
    ) {
        val trunkX = TRUNK_X.toPx()
        val radius = BEND_RADIUS.toPx()
        val rowHeight = ROW_HEIGHT.toPx()
        if (builtFor != density) {
            // The elbow is a quad with its control point at the corner (zeron's t², 2t−t²), then the
            // straight leg. PathMeasure trims it by arc length, so the tip moves at one speed.
            branch.reset()
            branch.moveTo(0f, 0f)
            branch.quadraticTo(0f, radius, radius, radius)
            branch.lineTo(BRANCH_END_X.toPx() - trunkX, radius)
            measure.setPath(branch, false)
            builtFor = density
        }
        rail.reset()
        var trunkTop = 0f
        progress.forEachIndexed { index, linear ->
            val connector = EaseOutQuint.transform(linear)
            val first = index == 0
            val elbowTop = index * rowHeight + rowHeight / 2 - radius
            // The trunk runs from the previous elbow, so it never hangs past the last call.
            val trunk = window(connector, 0f, if (first) FIRST_TRUNK_END else TRUNK_END)
            if (trunk > 0f) {
                rail.moveTo(trunkX, trunkTop)
                rail.lineTo(trunkX, trunkTop + (elbowTop - trunkTop) * trunk)
            }
            val leg = window(connector, if (first) FIRST_BRANCH_START else BRANCH_START, 1f)
            if (leg > 0f) {
                piece.reset()
                measure.getSegment(0f, measure.length * leg, piece, true)
                rail.addPath(piece, Offset(trunkX, elbowTop))
            }
            trunkTop = elbowTop
        }
        drawPath(rail, color, style = Stroke(width = RAIL_STROKE.toPx()))
    }
}

private fun window(
    progress: Float,
    start: Float,
    end: Float,
): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)

@Preview(showBackground = true)
@Composable
private fun ToolCallGroupPreview() {
    val calls =
        listOf(
            ToolCallDisplay(title = "rg -n displayBlocks", kind = ToolKind.EXECUTE, status = ToolCallStatus.COMPLETED),
            ToolCallDisplay(title = "MessageRuns.kt", kind = ToolKind.READ, status = ToolCallStatus.COMPLETED),
            ToolCallDisplay(title = "MessageBubble.kt", kind = ToolKind.EDIT, status = ToolCallStatus.FAILED),
            ToolCallDisplay(title = "./gradlew test", kind = ToolKind.EXECUTE, status = ToolCallStatus.IN_PROGRESS),
        )
    MaterialTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            ToolCallGroup(
                items =
                    calls.mapIndexed { index, call ->
                        SegmentBlock.Single(
                            AssistantSegment(id = "c$index", kind = AssistantSegment.Kind.TOOL_CALL, toolCall = call),
                        )
                    },
                isStreaming = false,
                isTail = true,
                liveSegmentId = null,
                onToolCallClick = {},
                onThoughtClick = {},
                foldsInFlight = remember { mutableIntStateOf(0) },
            )
        }
    }
}
