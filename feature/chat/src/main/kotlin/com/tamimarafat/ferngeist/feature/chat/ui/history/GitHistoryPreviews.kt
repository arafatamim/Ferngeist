package com.tamimarafat.ferngeist.feature.chat.ui.history

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.darkGitSemanticColors
import com.tamimarafat.ferngeist.core.common.ui.localGitSemanticColors
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitHistoryList
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitHistorySkeleton
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitSheetTabs

// region Previews

internal const val PREVIEW_NOW = 1_792_000_000_000L

internal fun previewCommit(
    sha: String,
    subject: String,
    hoursAgo: Double,
    parents: List<String>,
    refs: List<ChatGitRef> = emptyList(),
) = ChatGitCommit(
    sha = sha,
    parents = parents,
    subject = subject,
    authorName = "Tamim Arafat",
    authorEmail = "tamim@example.com",
    authorTimeMillis = PREVIEW_NOW - (hoursAgo * DateUtils.HOUR_IN_MILLIS).toLong(),
    refs = refs,
)

internal fun ChatGitCommit.byClaude() = copy(authorName = "Claude", authorEmail = "agent@ferngeist")

internal fun ChatGitCommit.byAda() = copy(authorName = "Ada Lovelace", authorEmail = "ada@example.com")

/** A feature branch merged back, a second open branch, and a tag — the shapes a real log has. */
internal val previewBranchyHistory =
    listOf(
        previewCommit(
            "m2",
            "Merge branch 'feat/history' into master",
            0.01,
            parents = listOf("c5", "h3"),
            refs =
                listOf(
                    ChatGitRef("master", ChatGitRef.Kind.BRANCH, isHead = true),
                    ChatGitRef("origin/master", ChatGitRef.Kind.REMOTE),
                ),
        ),
        previewCommit(
            "h3",
            "feat(chat): draw the history graph",
            0.4,
            parents = listOf("h2"),
        ).byClaude(),
        previewCommit(
            "w1",
            "wip: try a squarer avatar",
            1.5,
            parents = listOf("c4"),
            refs = listOf(ChatGitRef("try/avatar", ChatGitRef.Kind.BRANCH)),
        ).byAda(),
        previewCommit("c5", "fix(app): resize for the keyboard instead of panning", 3.0, parents = listOf("c4")),
        previewCommit(
            "h2",
            "feat(chat): lay out graph lanes",
            5.0,
            parents = listOf("h1"),
        ).byClaude(),
        previewCommit(
            "h1",
            "feat(gateway): add /git/log",
            26.0,
            parents = listOf("c4"),
        ).byClaude(),
        previewCommit(
            "c4",
            "fix(chat): time a turn by the gateway's duration",
            70.0,
            parents = listOf("c3"),
            refs = listOf(ChatGitRef("v0.9.0", ChatGitRef.Kind.TAG)),
        ),
        previewCommit("c3", "fix(chat): leave a reply closed by a reattach untimed", 200.0, parents = listOf("c2")),
        previewCommit(
            "c2",
            "fix(chat): let go of the held reply once the reader scrolls",
            400.0,
            parents = listOf("c1"),
            refs =
                listOf(
                    ChatGitRef("v0.8.0", ChatGitRef.Kind.TAG),
                    ChatGitRef("v0.8.0-rc2", ChatGitRef.Kind.TAG),
                ),
        ),
        previewCommit("c1", "fix(chat): restore a chat's scroll once", 9000.0, parents = emptyList()),
    )

internal val previewLinearHistory =
    listOf(
        previewCommit(
            "l4",
            "fix(app): resize for the keyboard instead of panning the window",
            0.2,
            parents = listOf("l3"),
            refs =
                listOf(
                    ChatGitRef("master", ChatGitRef.Kind.BRANCH, isHead = true),
                    ChatGitRef("origin/master", ChatGitRef.Kind.REMOTE),
                ),
        ),
        previewCommit("l3", "fix(chat): time a turn by the gateway's duration", 2.0, parents = listOf("l2")),
        previewCommit("l2", "fix(chat): leave a reply closed by a reattach untimed", 30.0, parents = listOf("l1")),
        previewCommit("l1", "fix(chat): restore a chat's scroll once", 900.0, parents = emptyList()),
    )

@Composable
internal fun GitHistoryPreviewFrame(commits: List<ChatGitCommit>) {
    var tab by remember { mutableIntStateOf(1) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.chat_git_status_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            GitSheetTabs(selected = tab, onSelect = { tab = it }, total = commits.size)
            Spacer(Modifier.height(8.dp))
            GitHistoryList(commits = commits, nowMillis = PREVIEW_NOW, onCommitClick = {})
        }
    }
}

@Preview(name = "History · branches", widthDp = 380, heightDp = 720)
@Composable
internal fun GitHistoryBranchyPreview() {
    MaterialTheme { GitHistoryPreviewFrame(previewBranchyHistory) }
}

@Preview(name = "History · branches · dark", widthDp = 380, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun GitHistoryBranchyDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(
            localGitSemanticColors provides darkGitSemanticColors,
        ) {
            GitHistoryPreviewFrame(previewBranchyHistory)
        }
    }
}

@Preview(name = "History · loading", widthDp = 380, heightDp = 480)
@Composable
internal fun GitHistoryLoadingPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                GitSheetTabs(selected = 1, onSelect = {})
                Spacer(Modifier.height(8.dp))
                GitHistorySkeleton()
            }
        }
    }
}

@Preview(name = "History · loading more", widthDp = 380, heightDp = 360)
@Composable
internal fun GitHistoryLoadingMorePreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            GitHistoryList(
                commits = previewLinearHistory,
                nowMillis = PREVIEW_NOW,
                onCommitClick = {},
                isLoadingMore = true,
            )
        }
    }
}

@Preview(name = "History · linear", widthDp = 380, heightDp = 360)
@Composable
internal fun GitHistoryLinearPreview() {
    MaterialTheme { GitHistoryPreviewFrame(previewLinearHistory) }
}

// endregion
