@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
// One row, header or skeleton per shape the history draws; splitting them would spread the
// graph's geometry across files.

package com.tamimarafat.ferngeist.feature.chat.ui.git

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.BandFade
import com.tamimarafat.ferngeist.feature.chat.GitBranchScope
import com.tamimarafat.ferngeist.feature.chat.GraphEdge
import com.tamimarafat.ferngeist.feature.chat.GraphRow
import com.tamimarafat.ferngeist.feature.chat.GraphStroke
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.dayGroupSizes
import com.tamimarafat.ferngeist.feature.chat.graphReach
import com.tamimarafat.ferngeist.feature.chat.historyBandFades
import com.tamimarafat.ferngeist.feature.chat.isFlat
import com.tamimarafat.ferngeist.feature.chat.layoutGraph
import com.tamimarafat.ferngeist.feature.chat.mid
import com.tamimarafat.ferngeist.feature.chat.strokesFor
import com.tamimarafat.ferngeist.feature.chat.ui.history.AuthorAvatar
import com.tamimarafat.ferngeist.feature.chat.ui.history.MoreRefsBadge
import com.tamimarafat.ferngeist.feature.chat.ui.history.RefBadge
import com.tamimarafat.ferngeist.feature.chat.ui.history.order
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

// Fixed so graph segments in neighbouring rows meet exactly at the row boundary.
private val commitRowHeight = 48.dp
private val laneWidth = 10.dp
private val dayHeaderHeight = 32.dp
private const val MAX_LANES = 6
internal const val MAX_REF_CHARS = 12
private const val LOADING_MORE_ROWS = 3
private const val LOAD_MORE_AHEAD = 10

/** A day's count only earns its badge past this many commits; a shorter day's rows are right there. */
private const val DAY_COUNT_ABOVE = 3

/** The git sheet's Changes | History switch. Siblings, so switching crossfades rather than slides. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GitSheetTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The walked history's size; null hides the History tab's badge. */
    total: Int? = null,
) {
    val labels = listOf(stringResource(R.string.chat_git_tab_changes), stringResource(R.string.chat_git_tab_history))
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labels.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selected,
                onCheckedChange = { onSelect(index) },
                shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                // Exactly one is on, so it reads as a choice rather than a pair of switches.
                modifier = Modifier.weight(1f).handCursor().semantics { role = Role.RadioButton },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(label)
                    if (index == HISTORY_TAB && total != null) GitHistoryCountBadge(total)
                }
            }
        }
    }
}

/** The tab index of History, the second of the pair [GitSheetTabs] lays out. */
private const val HISTORY_TAB = 1

/**
 * The whole walk's size beside the History label, so the list can be sized before it is paged to
 * the end. Tinted from the tab's own content colour, which flips when the tab is the selected one.
 */
@Composable
private fun GitHistoryCountBadge(total: Int) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = LocalContentColor.current.copy(alpha = 0.15f),
        contentColor = LocalContentColor.current,
    ) {
        Text(
            text = total.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Asks for the next page as the list nears its end; a failed page waits for its retry instead. */
@Composable
private fun LoadMoreWatcher(
    listState: LazyListState,
    moreError: String?,
    onLoadMore: () -> Unit,
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, moreError) {
        // A failed page waits for its retry button rather than re-asking on every scroll.
        if (moreError != null) return@LaunchedEffect
        // Keyed on the item count, so a page that lands still near the end asks for the next.
        snapshotFlow {
            val info = listState.layoutInfo
            val nearEnd = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_AHEAD
            if (nearEnd) info.totalItemsCount else -1
        }.distinctUntilChanged()
            .filter { it >= 0 }
            .collect { currentOnLoadMore() }
    }
}

/**
 * Commit history with a graph column. Rows never animate in: the list is scrolled
 * constantly, and motion there would read as lag.
 */
@Composable
internal fun GitHistoryList(
    commits: List<ChatGitCommit>,
    nowMillis: Long,
    onCommitClick: (ChatGitCommit) -> Unit,
    modifier: Modifier = Modifier,
    isLoadingMore: Boolean = false,
    /** Asked for the next page once the end is within [LOAD_MORE_AHEAD] rows. */
    onLoadMore: () -> Unit = {},
    /** A later page failed; the end of the list offers [onLoadMore] as a retry. */
    moreError: String? = null,
    /** Which rows are this branch's own work: the lane colour says so, and nothing else does. */
    scope: GitBranchScope = GitBranchScope.none,
) {
    val listState = rememberLazyListState()
    LoadMoreWatcher(listState, moreError, onLoadMore)
    val rows = remember(commits) { layoutGraph(commits) }
    val days = remember(commits) { commits.map { localDay(it.commitTimeMillis) } }
    val daySizes = remember(days) { dayGroupSizes(days) }
    // One mark per band: a band is a commit row, or the day header above one, which takes the same
    // mark as the row it sits over.
    val fades = remember(days, scope) { historyBandFades(days, scope) }
    // A date header sits between days, so bends never cross it.
    val strokes =
        remember(rows, days) {
            rows.indices.map { i ->
                strokesFor(
                    rows[i],
                    rows.getOrNull(i - 1)?.takeIf { days[i - 1] == days[i] },
                    rows.getOrNull(i + 1)?.takeIf { days[i + 1] == days[i] },
                )
            }
        }
    val reach = remember(strokes) { graphReach(strokes) }
    // Width follows the widest loaded row, capped, so the text column holds still.
    val laneCount =
        remember(rows) {
            val widest = rows.maxOfOrNull { row -> maxOf(row.lane, row.up.maxLane(), row.down.maxLane()) } ?: 0
            (widest + 1).coerceAtMost(MAX_LANES)
        }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            // Read here rather than in the item content: those lambdas run after the loop is done.
            var band = 0
            commits.forEachIndexed { index, commit ->
                if (index == 0 || days[index - 1] != days[index]) {
                    val fade = fades[band++]
                    item(key = "day-${commit.sha}") {
                        GitDayHeader(
                            dayMillis = commit.commitTimeMillis,
                            nowMillis = nowMillis,
                            // The lines running from the day above into this one.
                            through = rows.getOrNull(index - 1)?.down.orEmpty(),
                            laneCount = laneCount,
                            fade = fade,
                            commits = daySizes[index] ?: 0,
                        )
                    }
                }
                val fade = fades[band++]
                item(key = commit.sha) {
                    GitCommitRow(
                        commit = commit,
                        row = rows[index],
                        strokes = strokes[index],
                        reach = reach[index],
                        laneCount = laneCount,
                        nowMillis = nowMillis,
                        onClick = { onCommitClick(commit) },
                        fade = fade,
                    )
                }
            }
            if (isLoadingMore) {
                item(key = "loadingMore") {
                    SkeletonPulse {
                        Column {
                            repeat(LOADING_MORE_ROWS) {
                                GitCommitRowSkeleton(it, laneCount, first = false, last = it == LOADING_MORE_ROWS - 1)
                            }
                        }
                    }
                }
            }
            if (moreError != null) {
                item(key = "moreError") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_git_history_more_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onLoadMore, modifier = Modifier.handCursor()) {
                            Text(stringResource(R.string.chat_retry))
                        }
                    }
                }
            }
        }
        EdgeFade(listState = listState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLow)
    }
}

/** First page loading: rows the same height and layout as real ones, so nothing jumps when they land. */
@Composable
internal fun GitHistorySkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    SkeletonPulse(modifier = modifier.fillMaxWidth()) {
        Column { repeat(rows) { GitCommitRowSkeleton(it, laneCount = 1, first = it == 0, last = it == rows - 1) } }
    }
}

/** A lane-0 line with a dot, a subject bar, date and avatar. The line fades out on the last row. */
@Composable
private fun GitCommitRowSkeleton(
    index: Int,
    laneCount: Int,
    first: Boolean,
    last: Boolean,
) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier.fillMaxWidth().height(commitRowHeight).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier =
                Modifier
                    .width(laneWidth * laneCount + 6.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        val x = (6.dp + laneWidth / 2).toPx()
                        val mid = size.height / 2
                        val top = if (first) mid else 0f
                        val bottom = if (last) mid else size.height
                        drawLine(bone, Offset(x, top), Offset(x, bottom), 2.dp.toPx(), StrokeCap.Round)
                        drawCircle(bone, radius = 4.dp.toPx(), center = Offset(x, mid))
                    },
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            Bone(height = 12.dp, modifier = Modifier.fillMaxWidth(boneFractions[index % boneFractions.size]))
        }
        Spacer(Modifier.width(8.dp))
        Bone(height = 10.dp, width = 28.dp)
        Spacer(Modifier.width(8.dp))
        Bone(height = 24.dp, width = 24.dp, shape = CircleShape)
    }
}

private fun List<GraphEdge>.maxLane(): Int = maxOfOrNull { maxOf(it.from, it.to) } ?: 0

@Composable
private fun GitCommitRow(
    commit: ChatGitCommit,
    row: GraphRow,
    strokes: List<GraphStroke>,
    reach: Float,
    laneCount: Int,
    nowMillis: Long,
    onClick: () -> Unit,
    /** Inherited history below the base: the lane recedes to the muted colour, the row does not. */
    fade: BandFade = BandFade.none,
) {
    val description = commitDescription(commit, nowMillis)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(commitRowHeight)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitGraphCell(
            row = row,
            strokes = strokes,
            reach = reach,
            laneCount = laneCount,
            isHead = commit.refs.any { it.isHead || it.kind == ChatGitRef.Kind.HEAD },
            fade = fade,
        )
        Spacer(Modifier.width(8.dp))
        // The card sits beside the graph so the lane lines run unbroken between rows.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .handCursor()
                        .clickable(role = Role.Button, onClick = onClick)
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val refs = commit.refs.sortedBy { it.order() }
                    Text(
                        text = commit.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    refs.firstOrNull()?.let { RefBadge(it) }
                    if (refs.size > 1) MoreRefsBadge(refs.size - 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = compactCommitDate(nowMillis, commit.commitTimeMillis),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 40.dp).clearAndSetSemantics {},
                )
                Spacer(Modifier.width(8.dp))
                AuthorAvatar(name = commit.authorName, email = commit.authorEmail)
            }
        }
    }
}

/**
 * Lane colours for a branch's own commits, in the order lanes are handed out. Deliberately no grey:
 * [fadedLaneColor] is the only grey the graph draws, so any colour here means the branch's own work.
 */
@Composable
private fun graphPalette(): List<Color> {
    val colors = MaterialTheme.colorScheme
    return listOf(
        colors.primary,
        colors.tertiary,
        colors.secondary,
        localGitSemanticColors.current.added,
        colors.error,
    )
}

/**
 * Inherited history's lane: the outline grey thinned out, so the gap in weight against the palette
 * above is what marks where this branch's own work stops.
 */
@Composable
private fun fadedLaneColor(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

@Composable
private fun GitGraphCell(
    row: GraphRow,
    strokes: List<GraphStroke>,
    reach: Float,
    laneCount: Int,
    isHead: Boolean,
    /** How far this row's lane has receded into the muted colour, edge to edge. */
    fade: BandFade = BandFade.none,
) {
    val palette = graphPalette()
    val faded = fadedLaneColor()

    // Each lane keeps its own colour, mixed toward the muted grey by how far down the fade it is.
    fun laneColor(
        slot: Int,
        at: Float,
    ) = lerp(palette[slot % palette.size], faded, at)

    val background = MaterialTheme.colorScheme.surfaceContainerLow
    // Only as wide as this row's own lines, so its card sits right beside them.
    val lanes = reach.coerceAtMost(laneCount - 1f) + 1f
    Spacer(
        modifier =
            Modifier
                .width(laneWidth * lanes + 6.dp)
                .fillMaxHeight()
                .clearAndSetSemantics {}
                .drawBehind {
                    // Lanes past the cap share the last column so they stay visible.
                    fun x(lane: Int) = (6.dp + laneWidth * lane.coerceAtMost(laneCount - 1) + laneWidth / 2).toPx()
                    val mid = size.height / 2
                    val stroke = 2.dp.toPx()
                    val edgeStroke = Stroke(width = stroke, cap = StrokeCap.Round)

                    // S-curve: both control points sit at the vertical midpoint, so the bend leaves and
                    // lands vertically and joins the straight runs without a kink. Bends span a full
                    // row and draw past this cell's bounds; nothing clips, so they land in the neighbour.
                    strokes.forEach { s ->
                        val top = s.startY * size.height
                        val bottom = s.endY * size.height
                        val bend = (top + bottom) / 2
                        val path =
                            Path().apply {
                                moveTo(x(s.from), top)
                                cubicTo(x(s.from), bend, x(s.to), bend, x(s.to), bottom)
                            }
                        val from = laneColor(s.color, fade.top)
                        val to = laneColor(s.color, fade.bottom)
                        if (fade.isFlat) {
                            drawPath(path, color = from, style = edgeStroke)
                        } else {
                            // Over the row's height, not the path's: a bend's bounds are not the row's.
                            drawPath(
                                path = path,
                                brush = Brush.verticalGradient(listOf(from, to), startY = 0f, endY = size.height),
                                style = edgeStroke,
                            )
                        }
                    }
                    val center = Offset(x(row.lane), mid)
                    val radius = (if (row.isMerge) 5.dp else 4.dp).toPx()
                    val nodeColor = laneColor(row.color, fade.mid)
                    if (isHead) {
                        // Hollow ring: punch out the lines behind it, then stroke the ring.
                        drawCircle(color = background, radius = radius + stroke / 2, center = center)
                        drawCircle(color = nodeColor, radius = radius, center = center, style = Stroke(stroke))
                    } else {
                        drawCircle(color = nodeColor, radius = radius, center = center)
                    }
                },
    )
}

/** The day's label beside the graph, with how many commits that day holds; [through] lines carry on beside it so the graph stays whole. */
@Composable
private fun GitDayHeader(
    dayMillis: Long,
    nowMillis: Long,
    through: List<GraphEdge>,
    laneCount: Int,
    /** The rows below are inherited history, so their lines recede with them. */
    fade: BandFade = BandFade.none,
    /** Commits loaded under this header; the badge waits for [DAY_COUNT_ABOVE] of them, and later pages add to the last day's count as they land. */
    commits: Int = 0,
) {
    val palette = graphPalette()
    val faded = fadedLaneColor()
    val lanes = through.map { it.to }
    Row(
        modifier = Modifier.fillMaxWidth().height(dayHeaderHeight).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier =
                Modifier
                    .width(laneWidth * ((lanes.maxOrNull() ?: 0) + 1).coerceAtMost(laneCount) + 6.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        through.forEach { e ->
                            val x = (6.dp + laneWidth * e.to.coerceAtMost(laneCount - 1) + laneWidth / 2).toPx()
                            val start = Offset(x, 0f)
                            val end = Offset(x, size.height)
                            val width = 2.dp.toPx()
                            val lane = palette[e.color % palette.size]
                            val from = lerp(lane, faded, fade.top)
                            val to = lerp(lane, faded, fade.bottom)
                            if (fade.isFlat) {
                                drawLine(color = from, start = start, end = end, strokeWidth = width)
                            } else {
                                drawLine(
                                    brush = Brush.verticalGradient(listOf(from, to), startY = 0f, endY = size.height),
                                    start = start,
                                    end = end,
                                    strokeWidth = width,
                                )
                            }
                        }
                    },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = dayLabel(dayMillis, nowMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        if (commits > DAY_COUNT_ABOVE) {
            Spacer(Modifier.width(6.dp))
            GitDayCountBadge(commits)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The day's commit count, beside its label. */
@Composable
private fun GitDayCountBadge(commits: Int) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // Read out after the day's label, which the number alone could not explain.
    val description = pluralStringResource(R.plurals.chat_git_day_commits, commits, commits)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
    ) {
        Text(
            text = commits.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            modifier =
                Modifier
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .semantics { contentDescription = description },
        )
    }
}

@Composable
private fun commitDescription(
    commit: ChatGitCommit,
    nowMillis: Long,
): String {
    val refs =
        commit.refs.sortedBy { it.order() }.map { ref ->
            when (ref.kind) {
                ChatGitRef.Kind.BRANCH -> stringResource(R.string.chat_git_ref_branch, ref.name)
                ChatGitRef.Kind.REMOTE -> stringResource(R.string.chat_git_ref_remote, ref.name)
                ChatGitRef.Kind.TAG -> stringResource(R.string.chat_git_ref_tag, ref.name)
                ChatGitRef.Kind.HEAD -> stringResource(R.string.chat_git_ref_detached)
            }
        }
    val ago =
        DateUtils.getRelativeTimeSpanString(commit.commitTimeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    return (listOf(commit.subject) + refs + listOf(commit.authorName, ago)).joinToString(", ")
}
