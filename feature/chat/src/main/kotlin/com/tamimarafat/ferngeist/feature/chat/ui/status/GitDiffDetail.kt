@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.localizeChatError
import com.tamimarafat.ferngeist.feature.chat.ui.DiffRenderer
import com.tamimarafat.ferngeist.feature.chat.ui.FileTypeIcon
import com.tamimarafat.ferngeist.feature.chat.ui.diff.fileNameOf
import com.tamimarafat.ferngeist.feature.chat.ui.git.Bone
import com.tamimarafat.ferngeist.feature.chat.ui.git.DIFF_SKELETON_LINES
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitStatusBadge
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitTooltip
import com.tamimarafat.ferngeist.feature.chat.ui.git.SkeletonPulse
import com.tamimarafat.ferngeist.feature.chat.ui.git.boneFractions

/**
 * Detail body of the git status sheet for one changed file: back arrow with
 * content description, full path, status badge, +N/-N counts, then loading,
 * error-with-retry, binary, empty-diff, or rendered-diff content. The diff
 * owns the only vertical scroll container in detail.
 */
@Composable
internal fun GitDiffDetailContent(
    path: String,
    file: ChatChangedFile?,
    diff: List<ToolCallContent.Diff>?,
    diffPath: String?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** Diffing a commit's file: a missing [file] means its list is still loading, not that it was deleted. */
    inCommit: Boolean = false,
    /** Opens the whole file this diff came from, at the revision it was read; null hides the control. */
    onOpenFile: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GitDiffDetailHeader(file = file, onBack = onBack, onOpenFile = onOpenFile)

        Spacer(modifier = Modifier.height(16.dp))

        GitDiffDetailBody(
            path = path,
            file = file,
            diff = diff,
            diffPath = diffPath,
            isLoading = isLoading,
            error = error,
            onRetry = onRetry,
            inCommit = inCommit,
        )
    }
}

@Composable
internal fun GitDiffDetailHeader(
    file: ChatChangedFile?,
    onBack: () -> Unit,
    onOpenFile: (() -> Unit)?,
) {
    // Header: back arrow + file icon + file name + counts + open-full-file
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        GitTooltip(stringResource(R.string.chat_back_desc)) {
            IconButton(onClick = onBack, modifier = Modifier.handCursor()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.chat_back_desc),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (file != null) {
            FileTypeIcon(file.path, modifier = Modifier.size(20.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text =
                        file.oldPath?.let {
                            "${fileNameOf(
                                it,
                            )} → ${fileNameOf(file.path)}"
                        } ?: fileNameOf(file.path),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
                GitFileDiffCounts(file = file)
            }
            if (onOpenFile != null) {
                val label = stringResource(R.string.chat_file_open_full)
                GitTooltip(label) {
                    IconButton(onClick = onOpenFile, modifier = Modifier.handCursor()) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitFileDiffCounts(file: ChatChangedFile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GitStatusBadge(status = file.status)
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
                    color = localGitSemanticColors.current.added,
                )
            }
            if (file.removed > 0) {
                Text(
                    text = "-${file.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = localGitSemanticColors.current.deleted,
                )
            }
        }
    }
}

@Composable
internal fun GitDiffDetailBody(
    path: String,
    file: ChatChangedFile?,
    diff: List<ToolCallContent.Diff>?,
    diffPath: String?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    inCommit: Boolean,
) {
    // Content: loading / error / binary / empty / rendered diff.
    when {
        file == null && inCommit -> GitDiffSkeleton()
        file == null -> {
            // Requested path no longer in status (e.g. refreshed while open).
            // The header back arrow above remains available to return to the list.
            Text(
                text = stringResource(R.string.chat_git_file_deleted, path),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        isLoading -> GitDiffSkeleton()
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
                body = localizeChatError(LocalContext.current, error),
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

/** Diff lines: a gutter number and code of varied length, some indented like a real hunk. */
@Composable
internal fun GitDiffSkeleton() {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bone(height = 14.dp, width = 160.dp)
            Spacer(Modifier.height(4.dp))
            repeat(DIFF_SKELETON_LINES) { index ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Bone(height = 10.dp, width = 18.dp)
                    Box(
                        modifier =
                            Modifier.weight(1f).padding(
                                start =
                                    if (index in
                                        indentedDiffLines
                                    ) {
                                        16.dp
                                    } else {
                                        0.dp
                                    },
                            ),
                    ) {
                        Bone(
                            height = 10.dp,
                            modifier = Modifier.fillMaxWidth(boneFractions[(index + index / 2) % boneFractions.size]),
                        )
                    }
                }
            }
        }
    }
}

internal val indentedDiffLines = setOf(1, 2, 5, 8, 9)

@Preview(name = "Diff · loading", widthDp = 380, heightDp = 420)
@Composable
internal fun GitDiffLoadingPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            GitDiffDetailContent(
                path = "feature/chat/src/main/kotlin/GitGraph.kt",
                file = ChatChangedFile("feature/chat/src/main/kotlin/GitGraph.kt", "M", added = 12, removed = 3),
                diff = null,
                diffPath = null,
                isLoading = true,
                error = null,
                onBack = {},
                onRetry = {},
            )
        }
    }
}
