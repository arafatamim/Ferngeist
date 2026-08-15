@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.LocalGitSemanticColors
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.platform.LocalResources
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.Role

@Composable
internal fun GitStatusListContent(
    status: GatewayGitStatus,
    onFileClick: (GatewayChangedFile) -> Unit,
) {
    val additions = status.changed.sumOf { it.added }
    val deletions = status.changed.sumOf { it.removed }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GitStatusHeader(status = status)

        Spacer(modifier = Modifier.height(16.dp))

        GitStatusSummaryCard(
            additions = additions,
            deletions = deletions,
            fileCount = status.changed.size,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Per-file list (own vertical scroll container, no nesting)
        if (status.changed.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_git_clean),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                status.changed.forEach { file ->
                    ChangedFileRow(file = file, onClick = { onFileClick(file) })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun GitStatusHeader(status: GatewayGitStatus) {
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
            GitStatusBranchMeta(status)
        }
    }
}

@Composable
private fun GitStatusBranchMeta(status: GatewayGitStatus) {
    // Branch pill + ahead/behind (like git branch -v)
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
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (status.ahead > 0) {
            GitAheadBehindIndicator(
                icon = Icons.Filled.ArrowUpward,
                count = status.ahead,
                tint = LocalGitSemanticColors.current.added,
            )
        }
        if (status.behind > 0) {
            GitAheadBehindIndicator(
                icon = Icons.Filled.ArrowDownward,
                count = status.behind,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GitAheadBehindIndicator(
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
private fun GitStatusSummaryCard(
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
                        color = LocalGitSemanticColors.current.added,
                    )
                }
                if (deletions > 0) {
                    Text(
                        text = "-$deletions",
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalGitSemanticColors.current.deleted,
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

/**
 * Detail body of the git status sheet for one changed file: back arrow with
 * content description, full path, status badge, +N/-N counts, then loading,
 * error-with-retry, binary, empty-diff, or rendered-diff content. The diff
 * owns the only vertical scroll container in detail.
 */
@Composable
internal fun GitDiffDetailContent(
    path: String,
    file: GatewayChangedFile?,
    diff: List<ToolCallContent.Diff>?,
    diffPath: String?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GitDiffDetailHeader(file = file, onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        GitDiffDetailBody(
            path = path,
            file = file,
            diff = diff,
            diffPath = diffPath,
            isLoading = isLoading,
            error = error,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun GitDiffDetailHeader(
    file: GatewayChangedFile?,
    onBack: () -> Unit,
) {
    // Header: back arrow + status badge + file name + counts
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.chat_back_desc),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (file != null) {
            GitStatusBadge(status = file.status)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fileNameOf(file.path),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
                GitFileDiffCounts(file = file)
            }
        }
    }
}

@Composable
private fun GitStatusBadge(status: String) {
    // Status badge (same porcelain semantics as the list rows)
    val gitColors = LocalGitSemanticColors.current
    val statusColor =
        when (status) {
            "M" -> MaterialTheme.colorScheme.secondary
            "A" -> gitColors.added
            "R" -> MaterialTheme.colorScheme.tertiary
            "?" -> MaterialTheme.colorScheme.onSurfaceVariant
            "D" -> gitColors.deleted
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val statusBg =
        when (status) {
            "D" -> gitColors.deleted.copy(alpha = 0.15f)
            "M" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            "A" -> gitColors.added.copy(alpha = 0.15f)
            "R" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            "?" -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = statusBg,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = statusColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun GitFileDiffCounts(file: GatewayChangedFile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (file.binary) {
            Text(
                text = stringResource(R.string.chat_git_binary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (file.added > 0) {
                Text(
                    text = "+${file.added}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalGitSemanticColors.current.added,
                )
            }
            if (file.removed > 0) {
                Text(
                    text = "-${file.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalGitSemanticColors.current.deleted,
                )
            }
        }
    }
}

@Composable
private fun GitDiffDetailBody(
    path: String,
    file: GatewayChangedFile?,
    diff: List<ToolCallContent.Diff>?,
    diffPath: String?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    // Content: loading / error / binary / empty / rendered diff.
    when {
        file == null -> {
            // Requested path no longer in status (e.g. refreshed while open).
            // The header back arrow above remains available to return to the list.
            // Hardcoded like the diff error strings in ChatViewModel because this
            // file cannot add string resources.
            Text(
                text = "File is no longer in the working tree: $path",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        isLoading -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(64.dp))
            }
        }
        file.binary -> {
            Text(
                text = stringResource(R.string.chat_git_binary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        error != null -> {
            ErrorStateCard(
                headline = stringResource(R.string.chat_diff_error_title),
                body = error,
                icon = Icons.Rounded.CloudOff,
                medallionContainer = MaterialTheme.colorScheme.errorContainer,
                medallionContent = MaterialTheme.colorScheme.onErrorContainer,
                medallionShape = MaterialShapes.VerySunny.toShape(),
                ctaLabel = stringResource(R.string.chat_retry),
                onCta = onRetry,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        diff == null || diffPath != file.path || diff.isEmpty() -> {
            // No diff for this path yet (idle), a stale diff from another
            // file, or the gateway reported no changes for this path.
            Text(
                text = stringResource(R.string.chat_no_tool_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        else -> {
            // Loaded unified diff; DiffRenderer owns horizontal scrolling,
            // the outer Column provides the only vertical scroll container.
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                DiffRenderer(diff.first())
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChangedFileRow(
    file: GatewayChangedFile,
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
                        if (isDirectoryPath(file.path)) {
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        } else {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClick = onClick,
                                ).padding(horizontal = 12.dp, vertical = 10.dp)
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GitStatusBadge(status = file.status)

            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )

            if (isDirectoryPath(file.path)) {
                Text(
                    text = "Directory",
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
private fun ChangedFileDiffCounts(file: GatewayChangedFile) {
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
                color = LocalGitSemanticColors.current.added,
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
                color = LocalGitSemanticColors.current.deleted,
            )
        }
    }
}

