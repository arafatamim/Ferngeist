@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.diff.isDirectoryEntry

/**
 * The Changes tab: aggregate totals, then the per-file list, which owns the only scroll. A
 * comparison is [flat] with its own [emptyText]: its files are working tree against another
 * branch, so staged and unstaged mean nothing there.
 */
@Composable
internal fun ColumnScope.GitChangesBody(
    status: ChatGitStatus,
    onFileClick: (ChatChangedFile) -> Unit,
    flat: Boolean = false,
    emptyText: String = stringResource(R.string.chat_git_clean),
) {
    val additions = status.changed.sumOf { it.added }
    val deletions = status.changed.sumOf { it.removed }
    GitStatusSummaryCard(
        additions = additions,
        deletions = deletions,
        fileCount = status.changed.size,
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Per-file list (own vertical scroll container, no nesting)
    if (status.changed.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        ChangedFileList(changed = status.changed, flat = flat, onFileClick = onFileClick)
    }
}

/** The scrolling per-file list, which takes whatever height the body has left. */
@Composable
private fun ColumnScope.ChangedFileList(
    changed: List<ChatChangedFile>,
    flat: Boolean,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChangedFileGroups(changed = changed, flat = flat, onFileClick = onFileClick)
        }
        EdgeFade(scrollState = scrollState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLow)
    }
}

@Composable
private fun ChangedFileGroups(
    changed: List<ChatChangedFile>,
    flat: Boolean,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    // Staged files lead; headings only appear when there is something to tell apart.
    val (staged, unstaged) =
        if (flat) {
            emptyList<ChatChangedFile>() to changed
        } else {
            changed.partition { it.staged }
        }
    if (staged.isNotEmpty()) {
        GitFileGroup(R.string.chat_git_staged, staged, onFileClick)
        if (unstaged.isNotEmpty()) {
            GitFileGroup(R.string.chat_git_unstaged, unstaged, onFileClick)
        }
    } else {
        unstaged.forEach { ChangedFileRow(file = it, onClick = { onFileClick(it) }) }
    }
}

/**
 * The Changes tab against [target]: skeleton until its first answer, then the flat list. A failed
 * fetch shows the error card rather than any working-tree data under the target's name.
 */
@Composable
internal fun ColumnScope.GitCompareChangesBody(
    target: String,
    status: ChatGitStatus?,
    error: String?,
    onRetry: () -> Unit,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    when {
        status != null ->
            GitChangesBody(
                status = status,
                onFileClick = onFileClick,
                flat = true,
                emptyText = stringResource(R.string.chat_git_compare_identical, target),
            )
        error != null ->
            ErrorStateCard(
                headline = stringResource(R.string.chat_git_compare_error_title),
                body = error,
                icon = Icons.Rounded.CloudOff,
                medallionContainer = MaterialTheme.colorScheme.errorContainer,
                medallionContent = MaterialTheme.colorScheme.onErrorContainer,
                medallionShape = MaterialShapes.VerySunny.toShape(),
                ctaLabel = stringResource(R.string.chat_retry),
                onCta = onRetry,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        else -> GitFileListSkeleton()
    }
}

@Composable
internal fun GitStatusHeader(
    status: ChatGitStatus,
    compareRef: ChatGitRef?,
    canCompare: Boolean,
    onPickCompare: () -> Unit,
) {
    // Header: title + branch pill + ahead/behind
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.chat_git_status_title),
                style = MaterialTheme.typography.titleLarge,
            )
            GitStatusBranchMeta(status, compareRef, canCompare, onPickCompare)
        }
    }
}

@Composable
private fun GitStatusBranchMeta(
    status: ChatGitStatus,
    compareRef: ChatGitRef?,
    canCompare: Boolean,
    onPickCompare: () -> Unit,
) {
    // Branch pill + ahead/behind (like git branch -v), or `vs <target>` while comparing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = status.branch.ifBlank { stringResource(R.string.chat_git_no_branch) },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (compareRef != null) {
            Text(
                text = stringResource(R.string.chat_git_compare_vs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GitCompareChip(name = compareRef.name, onClick = onPickCompare)
            return@Row
        }
        if (status.ahead > 0) {
            GitAheadBehindIndicator(
                icon = Icons.Filled.ArrowUpward,
                count = status.ahead,
                tint = localGitSemanticColors.current.added,
            )
        }
        if (status.behind > 0) {
            GitAheadBehindIndicator(
                icon = Icons.Filled.ArrowDownward,
                count = status.behind,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canCompare) {
            val label = stringResource(R.string.chat_git_compare_title)
            GitTooltip(label) {
                IconButton(onClick = onPickCompare, modifier = Modifier.size(32.dp).handCursor()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = label,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** The chosen target; tapping reopens the picker. Its description names the target for screen readers. */
@Composable
private fun GitCompareChip(
    name: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.chat_git_compare_chip_desc, name)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier =
            Modifier.handCursor().semantics {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun GitAheadBehindIndicator(
    icon: ImageVector,
    count: Int,
    tint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

@Composable
internal fun GitStatusSummaryCard(
    additions: Int,
    deletions: Int,
    fileCount: Int,
) {
    // Summary card: aggregate line totals + diff-blocks proportion
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (additions > 0) {
                    Text(
                        text = "+$additions",
                        style = MaterialTheme.typography.titleMedium,
                        color = localGitSemanticColors.current.added,
                    )
                }
                if (deletions > 0) {
                    Text(
                        text = "-$deletions",
                        style = MaterialTheme.typography.titleMedium,
                        color = localGitSemanticColors.current.deleted,
                    )
                }
                DiffBlocks(additions = additions, deletions = deletions)
            }
            val resources = LocalResources.current
            Text(
                text =
                    resources.getQuantityString(
                        R.plurals.chat_git_files_changed,
                        fileCount,
                        fileCount,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun GitStatusBadge(status: String) {
    // Status badge (same porcelain semantics as the list rows)
    val gitColors = localGitSemanticColors.current
    val colors = MaterialTheme.colorScheme
    val (statusColor, statusBg) =
        when (status) {
            "M" -> colors.secondary to colors.secondaryContainer.copy(alpha = 0.4f)
            "A" -> gitColors.added to gitColors.added.copy(alpha = 0.15f)
            "R" -> colors.tertiary to colors.tertiaryContainer.copy(alpha = 0.4f)
            "D" -> gitColors.deleted to gitColors.deleted.copy(alpha = 0.15f)
            "U" -> colors.error to colors.errorContainer
            else -> colors.onSurfaceVariant to colors.surfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = statusBg,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun GitFileGroup(
    @StringRes title: Int,
    files: List<ChatChangedFile>,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    files.forEach { ChangedFileRow(file = it, onClick = { onFileClick(it) }) }
}

@Composable
internal fun ChangedFileRow(
    file: ChatChangedFile,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isDirectoryEntry(file)) {
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        } else {
                            Modifier
                                .handCursor()
                                .clickable(
                                    role = Role.Button,
                                    onClick = onClick,
                                ).padding(horizontal = 12.dp, vertical = 10.dp)
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isDirectoryEntry(file)) {
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // The name and its directory share one flexible slot. Measured loose, a long name
                // takes the whole row before the counts are measured and pushes them off the end.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FileChip(file.path, filled = false, textStyle = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = file.path.replace('\\', '/').substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (isDirectoryEntry(file)) {
                Text(
                    text = stringResource(R.string.chat_git_directory),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (file.binary) {
                Text(
                    text = stringResource(R.string.chat_git_binary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChangedFileDiffCounts(file = file)
            }
        }
    }
}

@Composable
private fun ChangedFileDiffCounts(file: ChatChangedFile) {
    // Reuse the diff-blocks visual (proportion of additions vs deletions)
    // with compact +N / -M counts, matching the top-bar indicator.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (file.added > 0) {
            Text(
                text = "+${file.added}",
                style = MaterialTheme.typography.labelSmall,
                color = localGitSemanticColors.current.added,
            )
        }
        DiffBlocks(
            additions = file.added,
            deletions = file.removed,
            showEmpty = false,
        )
        if (file.removed > 0) {
            Text(
                text = "-${file.removed}",
                style = MaterialTheme.typography.labelSmall,
                color = localGitSemanticColors.current.deleted,
            )
        }
    }
}

/** Summary card and file rows, the shapes [GitStatusSummaryCard] and [ChangedFileRow] will fill. */
@Composable
internal fun GitFileListSkeleton(rows: Int = 4) {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Bone(height = 16.dp, width = 40.dp)
                    Bone(height = 16.dp, width = 32.dp)
                    Spacer(Modifier.weight(1f))
                    Bone(height = 12.dp, width = 72.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            repeat(rows) { index ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Bone(height = 22.dp, width = 22.dp, shape = RoundedCornerShape(6.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Bone(
                                height = 12.dp,
                                modifier = Modifier.fillMaxWidth(boneFractions[index % boneFractions.size]),
                            )
                        }
                        Bone(height = 10.dp, width = 44.dp)
                    }
                }
            }
        }
    }
}

internal const val DIFF_SKELETON_LINES = 12

/** Names an icon-only control on long-press and hover, like the composer's buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
