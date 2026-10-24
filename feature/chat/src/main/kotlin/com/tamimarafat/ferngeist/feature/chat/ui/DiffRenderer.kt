package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.initialPassLongPress
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.syntax.SyntaxHighlighting
import com.tamimarafat.ferngeist.feature.chat.syntax.SyntaxLanguageResolver
import com.tamimarafat.ferngeist.feature.chat.ui.diff.DiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.diff.buildDiffRows
import com.tamimarafat.ferngeist.feature.chat.ui.diff.computeDiffBlocks
import com.tamimarafat.ferngeist.feature.chat.ui.diff.computeDiffStats
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitAheadBehindIndicator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

internal const val MAX_DIFF_ROWS = 500
internal const val DIFF_BLOCK_COUNT = 5

/**
 * Bars per side when additions == deletions: an exact 2/2 split (4 bars)
 * instead of the 5-bar rounding asymmetry (round(2.5)=3 green, 2 red).
 */
internal const val DIFF_EQUAL_BLOCK_COUNT = 2
private val diffInsertColor = Color(0xFF43A047)
internal const val DIFF_CONTEXT_LINES = 5

/**
 * Renders a unified-diff view for a single file change.
 *
 * Computes the diff between [diff.oldText] and [diff.newText] using the
 * java-diff-utils patch generator, then displays changed hunks with five
 * unchanged context lines around each hunk. Unchanged regions between hunks
 * are collapsed to an omission row.
 *
 * When [scrollable] is true the rows are rendered in their own lazy vertical
 * list (the git status sheet's detail body, which owns its scrolling). When
 * false — e.g. nested inside an outer scroll container like the tool-call
 * details sheet — the rows render eagerly inside a plain column so the
 * composable never places a vertically scrollable container under unbounded
 * height. Hunk computation stays async on [computeDispatcher] in both cases.
 *
 * @param diff The tool call diff containing old and new text content.
 * @param scrollable Whether the diff owns its vertical scrolling.
 */
@Composable
internal fun DiffRenderer(
    diff: ToolCallContent.Diff,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val rowsState =
        produceState<List<LineDiffRow>?>(
            initialValue = null,
            key1 = diff.oldText,
            key2 = diff.newText,
        ) {
            value =
                withContext(computeDispatcher) {
                    buildDiffRows(diff.oldText, diff.newText)
                }
        }
    val rows = rowsState.value
    val language = remember(diff.path) { SyntaxLanguageResolver.fromPath(diff.path) }
    val colors = localSyntaxColors.current
    val rowText by
        produceState<List<AnnotatedString>?>(null, rows, language, colors) {
            value =
                rows?.let { list ->
                    SyntaxHighlighting.highlightLines(
                        code = list.joinToString("\n") { it.text },
                        label = language,
                        colors = colors,
                    )
                }
        }

    SelectionContainer {
        if (scrollable) {
            LazyColumn(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
            ) {
                item(key = "path") {
                    DiffPathHeader(diff.path)
                }
                if (rows == null) {
                    item(key = "loading") {
                        DiffLoadingRow()
                    }
                } else {
                    itemsIndexed(
                        items = rows,
                        key = { index, _ -> "row-$index" },
                    ) { index, row ->
                        DiffRowItem(row, rowText?.getOrNull(index))
                    }
                }
            }
        } else {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
            ) {
                DiffPathHeader(diff.path)
                if (rows == null) {
                    DiffLoadingRow()
                } else {
                    rows.forEachIndexed { index, row -> DiffRowItem(row, rowText?.getOrNull(index)) }
                }
            }
        }
    }
}

@Composable
private fun DiffPathHeader(path: String) {
    Text(
        text = path,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp),
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun DiffLoadingRow() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun DiffRowItem(
    row: LineDiffRow,
    highlighted: AnnotatedString?,
) {
    val (prefix, bgColor, textColor) =
        when (row) {
            is LineDiffRow.Delete ->
                Triple(
                    "- ",
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.error,
                )
            is LineDiffRow.Insert ->
                Triple(
                    "+ ",
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    // MaterialTheme lacks this particular green; hardcode instead of adding
                    // a theme color for a single use.
                    diffInsertColor,
                )
            is LineDiffRow.Equal ->
                Triple(
                    "  ",
                    Color.Transparent,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            is LineDiffRow.Omitted ->
                Triple(
                    "  ",
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        val syntax = highlighted.takeUnless { row is LineDiffRow.Omitted }
        val text =
            remember(prefix, row.text, syntax) {
                if (syntax == null) {
                    AnnotatedString("$prefix${row.text}")
                } else {
                    buildAnnotatedString {
                        append(prefix)
                        append(syntax)
                    }
                }
            }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * Compact summary row aggregating all [ToolCallContent.Diff] entries
 * into a visual +N / diff-blocks / -N indicator.
 *
 * Filters [content] for diff items, sums their additions and deletions,
 * and renders the result as a single horizontal row. Renders nothing
 * when [content] is null, empty, or contains no [ToolCallContent.Diff] items.
 *
 * @param content The list of tool call content items to aggregate.
 * @param modifier Optional [Modifier] applied to the outer row.
 */
@Composable
internal fun DiffSummaryRow(
    content: List<ToolCallContent>?,
    modifier: Modifier = Modifier,
) {
    if (!content.isNullOrEmpty()) {
        val diffs = content.filterIsInstance<ToolCallContent.Diff>()
        if (diffs.isNotEmpty()) {
            DiffStatsRow(remember(diffs) { diffs.computeDiffStats() }, modifier)
        }
    }
}

/** `+N ▪▪▪ −M` for counts from any source: a tool call's diffs, or git. */
@Composable
internal fun DiffStatsRow(
    stats: DiffStats,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (stats.additions > 0) {
            RollingCount(
                value = stats.additions,
                prefix = "+",
                color = localGitSemanticColors.current.added,
            )
        }
        DiffBlocks(
            additions = stats.additions,
            deletions = stats.deletions,
        )
        if (stats.deletions > 0) {
            RollingCount(
                value = stats.deletions,
                prefix = "-",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Renders a row of colored blocks representing the proportion of
 * additions (green) to deletions (red) in a diff.
 *
 * The blocks are computed by [computeDiffBlocks] and capped at 5
 * total. Additions are shown as green boxes, deletions as red boxes,
 * and remaining slots as empty outlined boxes.
 *
 * @param additions The number of added lines.
 * @param deletions The number of deleted lines.
 * @param showEmpty Whether to render the empty placeholder boxes that pad the
 *   row to 5 blocks. Pass `false` when only the filled proportion should be shown.
 * @param modifier Optional [Modifier] applied to the row.
 */
@Composable
internal fun DiffBlocks(
    modifier: Modifier = Modifier,
    additions: Int,
    deletions: Int,
    showEmpty: Boolean = true,
) {
    val gitColors = localGitSemanticColors.current
    val result =
        remember(additions, deletions) {
            computeDiffBlocks(additions, deletions)
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(result.green) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(gitColors.added),
            )
        }
        repeat(result.red) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(gitColors.deleted),
            )
        }
        if (showEmpty) {
            repeat(result.empty) {
                Box(
                    modifier =
                        Modifier
                            .width(4.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .border(
                                1.dp,
                                gitColors.neutral,
                                RoundedCornerShape(1.dp),
                            ),
                )
            }
        }
    }
}

/**
 * Compact tappable indicator showing the git working-tree status (additions vs
 * deletions) using the same visual vocabulary as the edit tool-call diff summary
 * ([DiffBlocks] + "+N" / "-N" counts) and the same tonal-button style as the
 * connection status pill in the top bar — but wider, as a capsule instead of a circle.
 *
 * Renders nothing when [additions] and [deletions] are both zero (clean tree).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitStatusIndicatorButton(
    additions: Int,
    deletions: Int,
    branch: String?,
    changedFiles: Int,
    ahead: Int,
    behind: Int,
    worktree: ChatWorktree?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A clean repo keeps its pill: it is the only way into the commit history.
    val clean = changedFiles == 0
    val label =
        if (clean) {
            "$ahead ahead, $behind behind"
        } else {
            "$additions added, $deletions deleted"
        }
    val branchLabel = branch?.ifBlank { "no branch" } ?: "no branch"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            GitStatusTooltip(
                branchLabel = branchLabel,
                changedFiles = changedFiles,
                additions = additions,
                deletions = deletions,
                worktree = worktree,
            )
        },
        state = rememberTooltipState(),
    ) {
        FilledTonalButton(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier =
                modifier
                    .handCursor()
                    // The button consumes the press itself, so a tap detector above it never sees
                    // an unconsumed down — which is why the combinedClickable long press here
                    // never fired. This watches the press on the initial pass, where this node
                    // runs before the button.
                    .initialPassLongPress(onLongPress = onLongPress)
                    // Right-click is the long press's mouse equivalent.
                    .onSecondaryClick(onClick = onLongPress)
                    .semantics {
                        contentDescription = "Git status"
                        stateDescription = label
                    },
        ) {
            GitStatusButtonContent(additions, deletions, ahead, behind, clean)
        }
    }
}

/** The pill's long-press detail: what changed, the worktree, and counts only when there are lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipScope.GitStatusTooltip(
    branchLabel: String,
    changedFiles: Int,
    additions: Int,
    deletions: Int,
    worktree: ChatWorktree?,
) {
    RichTooltip(
        title = {
            Text(branchLabel)
        },
        modifier = Modifier.widthIn(max = 240.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (changedFiles > 0) {
                Text(pluralStringResource(R.plurals.chat_git_files_changed, changedFiles, changedFiles))
            } else {
                Text(stringResource(R.string.chat_git_clean))
            }
            worktree?.let { Text(worktreeSummary(it)) }
            // A clean tree has nothing to add up: the label above is the whole answer.
            if (additions > 0 || deletions > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "+$additions",
                        color = localGitSemanticColors.current.added,
                    )
                    Text(
                        text = "-$deletions",
                        color = localGitSemanticColors.current.deleted,
                    )
                }
            }
        }
    }
}

/** `Worktree · ↑2 · uncommitted`: what the gateway knows about the worktree beyond its branch. */
@Composable
private fun worktreeSummary(worktree: ChatWorktree): String =
    listOfNotNull(
        stringResource(R.string.chat_git_worktree),
        worktree.ahead?.takeIf { it > 0 }?.let { stringResource(R.string.chat_git_worktree_ahead, it) },
        if (worktree.dirty == true) stringResource(R.string.chat_git_worktree_dirty) else null,
    ).joinToString(" · ")

@Composable
private fun GitStatusButtonContent(
    additions: Int,
    deletions: Int,
    ahead: Int,
    behind: Int,
    clean: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clean) {
            CleanTreeIndicators(ahead = ahead, behind = behind)
        } else {
            if (additions > 0) {
                RollingCount(
                    value = additions,
                    prefix = "+",
                    color = localGitSemanticColors.current.added,
                )
            }
            DiffBlocks(additions = additions, deletions = deletions)
            if (deletions > 0) {
                RollingCount(
                    value = deletions,
                    prefix = "-",
                    color = localGitSemanticColors.current.deleted,
                )
            }
        }
    }
}

/** A clean tree shows how far the branch has moved from its upstream, or a commit mark when level. */
@Composable
private fun CleanTreeIndicators(
    ahead: Int,
    behind: Int,
) {
    if (ahead > 0) {
        GitAheadBehindIndicator(
            icon = Icons.Filled.ArrowUpward,
            count = ahead,
            tint = localGitSemanticColors.current.added,
        )
    }
    if (behind > 0) {
        GitAheadBehindIndicator(
            icon = Icons.Filled.ArrowDownward,
            count = behind,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (ahead == 0 && behind == 0) {
        Icon(
            imageVector = Icons.Filled.Commit,
            contentDescription = stringResource(R.string.chat_git_clean),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * A count that rolls when it changes, sliding up when it rises and down when it falls.
 *
 * The sign is rendered outside the animation: it is a symbol, not a digit, and keeping it static
 * stops the whole chip shifting sideways each time a count crosses zero.
 */
@Composable
internal fun RollingCount(
    value: Int,
    prefix: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val digits = value.absoluteValue.toString()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (value != 0) {
            Text(text = prefix, style = MaterialTheme.typography.labelSmall, color = color)
        }
        AnimatedContent(
            targetState = digits,
            transitionSpec = {
                val up =
                    targetState.length > initialState.length ||
                        (targetState.length == initialState.length && targetState > initialState)
                if (up) {
                    slideInVertically { it } + fadeIn() togetherWith
                        slideOutVertically { -it } + fadeOut()
                } else {
                    slideInVertically { -it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
                }.using(SizeTransform(clip = false))
            },
            label = "rollingCount",
        ) { text ->
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/**
 * Represents a single line in a unified diff output.
 *
 * Each subclass encodes the line type and its text content.
 *
 * @property text The line content (without the +/- prefix).
 */
internal sealed class LineDiffRow(
    val text: String,
) {
    val isChanged: Boolean
        get() = this is Delete || this is Insert

    class Delete(
        text: String,
    ) : LineDiffRow(text)

    class Insert(
        text: String,
    ) : LineDiffRow(text)

    class Equal(
        text: String,
    ) : LineDiffRow(text)

    class Omitted(
        text: String,
    ) : LineDiffRow(text)
}
