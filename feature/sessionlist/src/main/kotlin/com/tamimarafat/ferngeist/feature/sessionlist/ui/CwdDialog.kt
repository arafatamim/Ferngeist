package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onSecondaryClick
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.CwdSuggestion
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.CwdSuggestionSource
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.buildCwdSuggestions
import kotlinx.coroutines.launch

private const val CWD_SUGGESTION_MAX_HEIGHT_DP = 200

/** The one base worth offering as a chip; leave the field empty for the repo's `HEAD`. */
private const val DEFAULT_WORKTREE_BASE = "origin/main"

/**
 * Worktree-creation fields for [CwdDialog], or null when this gateway has no worktree API
 * (an older gateway, or a non-gateway agent), which hides the switch entirely.
 *
 * The switch repurposes the directory field: it stops being a filter and becomes the repo
 * a new branch is cut from, so a successful create opens a chat instead of saving the filter.
 */
data class CwdWorktreeState(
    val enabled: Boolean,
    val branch: String,
    val base: String,
    /** Inline error under the branch field; the gateway's own message or a known status. */
    val error: String?,
    /** True while the gateway runs `git worktree add`; the confirm button stays busy. */
    val creating: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val onBranchChange: (String) -> Unit,
    val onBaseChange: (String) -> Unit,
)

/**
 * Bottom sheet for setting the working-directory filter on sessions.
 * Suggestions (recent CWDs + CWDs from existing sessions) are filtered live as
 * the user types — tap to fill the text field, long-press to permanently remove
 * a recent entry.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CwdDialog(
    recentCwds: List<String>,
    sessions: List<SessionSummary>,
    cwdDialogValue: String,
    onCwdDialogValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    onRemoveRecentCwd: (String) -> Unit,
    worktree: CwdWorktreeState? = null,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val worktreeEnabled = worktree?.enabled == true
    val creatingWorktree = worktree?.let { it.enabled && it.creating } == true
    val suggestions =
        remember(recentCwds, sessions, cwdDialogValue) {
            buildCwdSuggestions(cwdDialogValue, recentCwds, sessions)
        }

    fun animateAnd(block: () -> Unit) {
        scope.launch {
            sheetState.hide()
            block()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sessionlist_cwd_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text =
                    if (worktreeEnabled) {
                        stringResource(R.string.sessionlist_worktree_body)
                    } else {
                        stringResource(R.string.sessionlist_cwd_body)
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = cwdDialogValue,
                onValueChange = onCwdDialogValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.sessionlist_cwd_placeholder)) },
            )
            worktree?.let { form ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = form.enabled,
                        onCheckedChange = form.onEnabledChange,
                    )
                    Text(
                        text = stringResource(R.string.sessionlist_worktree_switch),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (form.enabled) {
                    OutlinedTextField(
                        value = form.branch,
                        onValueChange = form.onBranchChange,
                        singleLine = true,
                        isError = form.error != null,
                        label = { Text(stringResource(R.string.sessionlist_worktree_branch)) },
                        placeholder = { Text(stringResource(R.string.sessionlist_worktree_optional)) },
                        supportingText = form.error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.base,
                        onValueChange = form.onBaseChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.sessionlist_worktree_base)) },
                        placeholder = { Text(stringResource(R.string.sessionlist_worktree_base_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AssistChip(
                        onClick = { form.onBaseChange(DEFAULT_WORKTREE_BASE) },
                        modifier = Modifier.handCursor(),
                        label = { Text(DEFAULT_WORKTREE_BASE) },
                    )
                }
            }
            if (suggestions.isNotEmpty()) {
                CwdSuggestionList(
                    suggestions = suggestions,
                    onCwdSelected = onCwdDialogValueChange,
                    onRemoveRecentCwd = onRemoveRecentCwd,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClear != null && !worktreeEnabled) {
                    TextButton(onClick = { animateAnd(onClear) }, modifier = Modifier.handCursor()) {
                        Text(stringResource(R.string.sessionlist_cwd_clear))
                    }
                }
                TextButton(onClick = { animateAnd(onDismiss) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(R.string.sessionlist_cwd_cancel))
                }
                Button(
                    // A worktree create keeps the sheet open: it shows the busy state and any
                    // inline error, and closes once the chat opens.
                    onClick = { if (worktreeEnabled) onSave() else animateAnd(onSave) },
                    enabled = !creatingWorktree,
                    modifier = Modifier.handCursor(),
                ) {
                    if (creatingWorktree) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(
                            if (worktreeEnabled) {
                                stringResource(R.string.sessionlist_worktree_create)
                            } else {
                                stringResource(R.string.sessionlist_cwd_save)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CwdSuggestionList(
    suggestions: List<CwdSuggestion>,
    onCwdSelected: (String) -> Unit,
    onRemoveRecentCwd: (String) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = CWD_SUGGESTION_MAX_HEIGHT_DP.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(suggestions, key = { it.cwd }) { suggestion ->
            Box(Modifier.animateItem()) {
                CwdSuggestionRow(
                    suggestion = suggestion,
                    onCwdSelected = onCwdSelected,
                    onRemoveRecentCwd = onRemoveRecentCwd,
                )
            }
        }
    }
}

@Composable
private fun CwdSuggestionRow(
    suggestion: CwdSuggestion,
    onCwdSelected: (String) -> Unit,
    onRemoveRecentCwd: (String) -> Unit,
) {
    val isRecent = suggestion.source == CwdSuggestionSource.RECENT
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .handCursor()
                .onSecondaryClick(enabled = isRecent) { onRemoveRecentCwd(suggestion.cwd) }
                .combinedClickable(
                    onClick = { onCwdSelected(suggestion.cwd) },
                    onLongClick =
                        if (isRecent) {
                            { onRemoveRecentCwd(suggestion.cwd) }
                        } else {
                            null
                        },
                ).semantics {
                    contentDescription = suggestion.cwd
                }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isRecent) Icons.Filled.History else Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = suggestion.cwd,
            overflow = TextOverflow.MiddleEllipsis,
            softWrap = true,
            maxLines = 1,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
