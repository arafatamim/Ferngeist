package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SegmentBlock
import com.tamimarafat.ferngeist.feature.chat.ui.diff.DiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.diff.computeDiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.files.diffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.filePath
import com.tamimarafat.ferngeist.feature.chat.ui.files.landed
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.RailRowFrame
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.ThoughtRowContent
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.ToolRowContent
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.description
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.joinSummary
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.summaryParts
import com.tamimarafat.ferngeist.feature.chat.ui.toolgroup.summaryVerb

/*
 * Adjacent tool calls as one activity: a summary line that folds open into compact rows joined
 * by a rail, which draws itself as calls arrive. Ported from zeron's desktop transcript; see
 * docs/design/tool-call-rail.md for what was kept and what Compose already does.
 */

private val trunkX = 12.dp
private val bendRadius = 6.dp
private val branchEndX = 28.dp
internal val iconLeft = 32.dp
internal val iconSize = 24.dp
internal val textGap = 8.dp
internal val rowHeight = 36.dp
internal val railRowHeight get() = rowHeight
internal val railTextStart get() = iconLeft
private val railStroke = 1.5.dp
private val chevronSize = 16.dp

internal const val CONNECTOR_MS = 480
internal const val ROW_REVEAL_MS = 360
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
    // Uncounted present-continuous label, for a verb that only ever has one item running at a time.
    @param:StringRes val runningLabel: Int? = null,
) {
    THOUGHT(
        R.plurals.chat_tool_summary_thought,
        R.string.chat_tool_summary_thought_twice,
        runningLabel = R.string.chat_tool_summary_thinking,
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

/** One row on the rail: a tool call, or a run of reasoning. */
internal class RailRow(
    val id: String,
    val call: ToolCallDisplay?,
    val thought: SegmentBlock.Run?,
    val thinking: Boolean,
) {
    val running: Boolean
        get() = thinking || call?.status == ToolCallStatus.PENDING || call?.status == ToolCallStatus.IN_PROGRESS
}

internal val RailRow.verb: ToolVerb get() = call?.summaryVerb() ?: ToolVerb.THOUGHT

// A file verb counts files, not calls: three edits to one file are "edited a file".
internal val RailRow.summaryKey: Any
    get() = call?.filePath()?.takeIf { verb != ToolVerb.FAILED }?.let { verb to it } ?: id

// Only a change that happened counts toward the group's total (see landed()).
internal fun RailRow.landedDiffs(): List<ToolCallContent.Diff> = call?.takeIf { it.landed() }?.diffs().orEmpty()

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
 * @param startedAtMs when the group began, timed in its header while it runs.
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
    startedAtMs: Long? = null,
) {
    val rows = railRowsOf(items, liveSegmentId)
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
    val live = !done && isStreaming
    // Open and still live: the transcript follows it down until it closes.
    TrackActiveRail(open = open, live = live)

    // While anything runs, the summary names only what is running: the present tense would
    // misdescribe the calls already finished. The full tally comes back once nothing runs.
    val summaryRows = if (running) rows.filter { it.running } else rows
    // Only while live: once the group ends, the time is clutter beside the reply.
    val elapsed = rememberElapsedLabel(startedAtMs, live = live)
    // A sole row with a diff would repeat the header's total, so only the header shows it.
    val soleDiffRow = soleDiffRowId(rows)

    Column(modifier = modifier.fillMaxWidth()) {
        ToolGroupHeader(
            summary = toolSummary(summaryRows.distinctBy { it.summaryKey }.map { it.verb }, running),
            elapsed = elapsed?.text,
            elapsedDescription = elapsed?.description,
            stats = rememberDiffStats(rows.flatMap { it.landedDiffs() }),
            open = open,
            onClick = { expanded = !open },
        )
        FoldBody(open = open, foldsInFlight = foldsInFlight) {
            ToolRail(rows, reveals, animate = isStreaming, hideStatsOf = soleDiffRow) { row ->
                // Any chunk of a thought resolves to the whole run in the sheet.
                val thought = row.thought
                if (thought != null) onThoughtClick(thought.segments.last().id) else onToolCallClick(row.id)
            }
        }
    }
}

/** One rail row per call or thought run; a group of rows has no row of its own. */
private fun railRowsOf(
    items: List<SegmentBlock>,
    liveSegmentId: String?,
): List<RailRow> =
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

private fun soleDiffRowId(rows: List<RailRow>): String? =
    rows
        .singleOrNull {
            it.call
                ?.diffs()
                .orEmpty()
                .isNotEmpty()
        }?.takeIf { it.landedDiffs().isNotEmpty() }
        ?.id

/** Open, live rails in the transcript, counted for [ChatScrollHandle]; null outside it. */
internal val localActiveRails = compositionLocalOf<MutableIntState?> { null }

/** Counts this rail into [localActiveRails] while it is [open] and [live]. */
@Composable
private fun TrackActiveRail(
    open: Boolean,
    live: Boolean,
) {
    val rails = localActiveRails.current ?: return
    if (open && live) {
        DisposableEffect(rails) {
            rails.intValue++
            onDispose { rails.intValue-- }
        }
    }
}

/** A fully drawn rail behind [rowCount] rows of [railRowHeight], for lists that share the tool rail's look. */
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
    joinSummary(
        summaryParts(verbs, running).map { part ->
            part.label?.let { stringResource(it) }
                ?: part.twice?.takeIf { part.count == 2 }?.let { stringResource(it) }
                ?: pluralStringResource(part.phrase, part.count, part.count)
        },
        stringResource(R.string.chat_tool_summary_separator),
    )

/**
 * Summary, elapsed time, diff total and a chevron that turns on the fold's spatial spring. [elapsed]
 * stands apart from the summary's transition, so a tick does not replay the slide.
 */
@Composable
internal fun ToolGroupHeader(
    summary: String,
    stats: DiffStats?,
    open: Boolean,
    onClick: () -> Unit,
    elapsed: String? = null,
    elapsedDescription: String? = null,
    separator: String? = null,
    chevronAtEnd: Boolean = false,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(
        targetValue = if (open) CHEVRON_OPEN_DEGREES else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "toolGroupChevron",
    )
    Row(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chevron = @Composable { modifier: Modifier ->
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.chat_tool_call_details_desc),
                tint = baseColor,
                modifier = modifier.size(chevronSize).graphicsLayer { rotationZ = rotation },
            )
        }
        if (!chevronAtEnd) {
            // Centred on the trunk, so the open chevron points straight down the rail.
            chevron(Modifier.padding(start = trunkX - chevronSize / 2))
            Spacer(modifier = Modifier.width(4.dp))
        }
        // The summary wraps freely; the diff total holds the end. A new summary rises
        // in as the old one rises out, so a change of state reads as the next step.
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        AnimatedContent(
            targetState = summary,
            transitionSpec = {
                (slideInVertically(spatial) { it / 2 } + fadeIn(effects)) togetherWith
                    (slideOutVertically(spatial) { -it / 2 } + fadeOut(effects)) using
                    SizeTransform(clip = false)
            },
            label = "toolGroupSummary",
            modifier = Modifier.weight(1f),
        ) { text ->
            val style = MaterialTheme.typography.bodySmall.copy(color = baseColor)
            if (separator == null) {
                Text(text = text, style = style, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                PhrasesText(text, separator, style, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (elapsed != null) {
            Text(
                text = elapsed,
                // Tabular digits, so the ticking time does not jostle the stats beside it.
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = baseColor,
                maxLines = 1,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .semantics { elapsedDescription?.let { contentDescription = it } },
            )
        }
        stats?.let { DiffStatsRow(it, modifier = Modifier.padding(start = 8.dp)) }
        if (chevronAtEnd) chevron(Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ToolRail(
    rows: List<RailRow>,
    reveals: HashMap<String, RowReveal>,
    animate: Boolean,
    hideStatsOf: String?,
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
                    if (call != null) {
                        ToolRowContent(call, showStats = row.id != hideStatsOf)
                    } else {
                        ThoughtRowContent(
                            thoughtText = row.thought?.text.orEmpty(),
                            thinking = row.thinking,
                        )
                    }
                }
            }
        }
    }
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
        val trunkX = trunkX.toPx()
        val radius = bendRadius.toPx()
        val rowHeight = rowHeight.toPx()
        if (builtFor != density) {
            // The elbow is a quad with its control point at the corner (zeron's t², 2t−t²), then the
            // straight leg. PathMeasure trims it by arc length, so the tip moves at one speed.
            branch.reset()
            branch.moveTo(0f, 0f)
            branch.quadraticTo(0f, radius, radius, radius)
            branch.lineTo(branchEndX.toPx() - trunkX, radius)
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
        drawPath(rail, color, style = Stroke(width = railStroke.toPx()))
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
