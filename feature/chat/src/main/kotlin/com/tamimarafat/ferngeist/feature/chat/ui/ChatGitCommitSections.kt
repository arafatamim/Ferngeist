@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.ClipData
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.darkGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.history.AuthorAvatar
import com.tamimarafat.ferngeist.feature.chat.ui.history.RefBadge
import com.tamimarafat.ferngeist.feature.chat.ui.history.commitWebUrl
import com.tamimarafat.ferngeist.feature.chat.ui.history.order
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHORT_SHA = 7
private const val BODY_COLLAPSED_LINES = 4
private const val SUBJECT_COLLAPSED_LINES = 3
private const val COPIED_MILLIS = 1_500L

/**
 * The page a tapped history row opens: what the commit says and who made it, then the
 * files it changed. [files] is null until the gateway answers; the header shows at once
 * because the log row already carried everything it needs.
 */
@Composable
internal fun GitCommitDetailContent(
    commit: ChatGitCommit,
    /** The repo's `origin` URL as git reports it; null when the repo has none. */
    remoteUrl: String?,
    files: List<ChatChangedFile>?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GitCommitHeader(
            commit = commit,
            webUrl = remoteUrl?.takeIf { commit.isPushed }?.let { commitWebUrl(it, commit.sha) },
            onBack = onBack,
        )
        Spacer(Modifier.height(12.dp))
        GitCommitMeta(commit = commit)
        if (commit.refs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GitCommitRefs(commit.refs)
        }
        if (commit.body.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            GitCommitBody(commit.body.trim())
        }
        Spacer(Modifier.height(16.dp))
        GitCommitFiles(
            files = files,
            isLoading = isLoading,
            error = error,
            onRetry = onRetry,
            onFileClick = onFileClick,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GitCommitHeader(
    commit: ChatGitCommit,
    webUrl: String?,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GitTooltip(stringResource(R.string.chat_back_desc)) {
            IconButton(onClick = onBack, modifier = Modifier.handCursor()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.chat_back_desc),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        ExpandableText(
            text = commit.subject,
            collapsedLines = SUBJECT_COLLAPSED_LINES,
            style = MaterialTheme.typography.titleMedium,
            // Centres a one-line subject on the 48dp back button; longer ones grow downward.
            modifier = Modifier.weight(1f).padding(top = 12.dp),
        )
        if (webUrl != null) {
            val uriHandler = LocalUriHandler.current
            val onGitHub = webUrl.startsWith("https://github.com/")
            val openLabel =
                stringResource(if (onGitHub) R.string.chat_git_open_on_github else R.string.chat_git_open_in_browser)
            GitTooltip(openLabel) {
                IconButton(onClick = { uriHandler.openUri(webUrl) }, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = openLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GitCommitMeta(commit: ChatGitCommit) {
    val context = LocalContext.current
    val date =
        DateUtils.formatDateTime(
            context,
            commit.authorTimeMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_ABBREV_MONTH,
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AuthorAvatar(name = commit.authorName, email = commit.authorEmail, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = commit.authorName.ifBlank { commit.authorEmail },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        ShaChip(commit.sha)
    }
}

/** Short sha that copies the full one. The icon turning into a check is the only confirmation. */
@Composable
private fun ShaChip(sha: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }
    val short = sha.take(SHORT_SHA)
    val description =
        if (copied) stringResource(R.string.chat_git_sha_copied) else stringResource(R.string.chat_git_copy_sha, short)
    GitTooltip(description) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier =
                Modifier
                    .handCursor()
                    .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Row(
                modifier =
                    Modifier
                        .clickable(role = Role.Button) {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sha", sha)))
                                copied = true
                            }
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = short,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
                ShaCopyIcon(copied)
            }
        }
    }
}

/** Copy glyph that cross-fades to a check, in the added colour, once the sha is on the clipboard. */
@Composable
private fun ShaCopyIcon(copied: Boolean) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = copied,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "shaCopied",
    ) { done ->
        Icon(
            imageVector = if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint =
                if (done) {
                    localGitSemanticColors.current.added
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitCommitRefs(refs: List<ChatGitRef>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        refs.sortedBy { it.order() }.forEach { RefBadge(it, truncate = false) }
    }
}

@Composable
private fun GitCommitBody(body: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ExpandableText(
            text = body,
            collapsedLines = BODY_COLLAPSED_LINES,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Text cut at [collapsedLines] that a tap opens in full; text that fits is not tappable. */
@Composable
private fun ExpandableText(
    text: String,
    collapsedLines: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val label =
        stringResource(if (expanded) R.string.chat_git_commit_body_collapse else R.string.chat_git_commit_body_expand)
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        modifier =
            modifier
                .then(
                    if (overflows) {
                        Modifier
                            .handCursor()
                            .clickable(onClickLabel = label, role = Role.Button) { expanded = !expanded }
                    } else {
                        Modifier
                    },
                ).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                .padding(contentPadding),
    )
}

@Composable
private fun ColumnScope.GitCommitFiles(
    files: List<ChatChangedFile>?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onFileClick: (ChatChangedFile) -> Unit,
) {
    when {
        error != null ->
            ErrorStateCard(
                headline = stringResource(R.string.chat_git_commit_error_title),
                body = error,
                icon = Icons.Rounded.CloudOff,
                medallionContainer = MaterialTheme.colorScheme.errorContainer,
                medallionContent = MaterialTheme.colorScheme.onErrorContainer,
                medallionShape = MaterialShapes.VerySunny.toShape(),
                ctaLabel = stringResource(R.string.chat_retry),
                onCta = onRetry,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        files == null || isLoading -> GitFileListSkeleton()
        files.isEmpty() ->
            Text(
                text = stringResource(R.string.chat_git_commit_no_files),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        else -> {
            GitStatusSummaryCard(
                additions = files.sumOf { it.added },
                deletions = files.sumOf { it.removed },
                fileCount = files.size,
            )
            Spacer(Modifier.height(12.dp))
            val scrollState = rememberScrollState()
            // Only the file list scrolls; the header stays put above it.
            Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    files.forEach { file -> ChangedFileRow(file = file, onClick = { onFileClick(file) }) }
                }
                EdgeFade(scrollState = scrollState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLow)
            }
        }
    }
}

// region Previews

private val previewCommit =
    ChatGitCommit(
        sha = "4f9c2e1a7b3d5e6f8091a2b3c4d5e6f708192a3b",
        parents = listOf("0901beff"),
        subject = "feat(chat): draw the commit history graph beside each entry",
        body =
            "Lanes come from layoutGraph, one per open branch, so a linear history stays a " +
                "single straight line.\n\nBends span a full row and spill into the neighbour, which " +
                "drops the straight half it would have drawn. Rows are a fixed 48dp so segments " +
                "meet exactly at the boundary.\n\nRefs: #212",
        authorName = "Tamim Arafat",
        authorEmail = "tamim@example.com",
        authorTimeMillis = 1_792_000_000_000L,
        isPushed = true,
        refs =
            listOf(
                ChatGitRef("master", ChatGitRef.Kind.BRANCH, isHead = true),
                ChatGitRef("origin/master", ChatGitRef.Kind.REMOTE),
                ChatGitRef("v0.9.0", ChatGitRef.Kind.TAG),
            ),
    )

private val previewFiles =
    listOf(
        ChatChangedFile("feature/chat/src/main/kotlin/com/tamimarafat/ferngeist/feature/chat/GitGraph.kt", "A", 148),
        ChatChangedFile(
            "feature/chat/src/main/kotlin/com/tamimarafat/ferngeist/feature/chat/ui/ChatGitHistorySections.kt",
            "A",
            612,
        ),
        ChatChangedFile("core/model/src/main/kotlin/com/tamimarafat/ferngeist/core/model/ChatDomainModels.kt", "M", 22),
        ChatChangedFile("feature/chat/src/main/res/values/strings.xml", "M", 14, 1),
        ChatChangedFile("feature/chat/src/main/res/drawable/graph.png", "A", binary = true),
    )

@Composable
private fun GitCommitPreviewFrame(
    commit: ChatGitCommit = previewCommit,
    files: List<ChatChangedFile>? = previewFiles,
    error: String? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        GitCommitDetailContent(
            commit = commit,
            remoteUrl = "git@github.com:arafatamim/Ferngeist.git",
            files = files,
            isLoading = false,
            error = error,
            onBack = {},
            onRetry = {},
            onFileClick = {},
        )
    }
}

@Preview(name = "Commit", widthDp = 380, heightDp = 720)
@Composable
private fun GitCommitPreview() {
    MaterialTheme { GitCommitPreviewFrame() }
}

@Preview(name = "Commit · dark", widthDp = 380, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GitCommitDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(localGitSemanticColors provides darkGitSemanticColors) { GitCommitPreviewFrame() }
    }
}

@Preview(name = "Commit · not on origin, loading", widthDp = 380, heightDp = 400)
@Composable
private fun GitCommitLoadingPreview() {
    MaterialTheme {
        GitCommitPreviewFrame(
            commit = previewCommit.copy(body = "", refs = emptyList(), isPushed = false),
            files = null,
        )
    }
}

@Preview(name = "Commit · error", widthDp = 380, heightDp = 560)
@Composable
private fun GitCommitErrorPreview() {
    MaterialTheme { GitCommitPreviewFrame(files = null, error = "Gateway returned 502") }
}

// endregion
