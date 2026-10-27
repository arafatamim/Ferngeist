package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

/**
 * Bottom sheet for setting the working-directory filter on sessions.
 * Suggestions (recent CWDs + CWDs from existing sessions) are filtered live as
 * the user types — tap to fill the text field, long-press to permanently remove
 * a recent entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    rootOf: (String) -> String,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val suggestions =
        remember(recentCwds, sessions, cwdDialogValue, rootOf) {
            buildCwdSuggestions(cwdDialogValue, recentCwds, sessions, rootOf)
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
                text = stringResource(R.string.sessionlist_cwd_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cwdDialogValue,
                onValueChange = onCwdDialogValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.sessionlist_cwd_placeholder)) },
            )
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
                if (onClear != null) {
                    TextButton(onClick = { animateAnd(onClear) }, modifier = Modifier.handCursor()) {
                        Text(stringResource(R.string.sessionlist_cwd_clear))
                    }
                }
                TextButton(onClick = { animateAnd(onDismiss) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(R.string.sessionlist_cwd_cancel))
                }
                Button(onClick = { animateAnd(onSave) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(R.string.sessionlist_cwd_save))
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
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(suggestions, key = { _, suggestion -> suggestion.cwd }) { index, suggestion ->
            Box(Modifier.animateItem()) {
                CwdSuggestionRow(
                    suggestion = suggestion,
                    shapes = ListItemDefaults.segmentedShapes(index, suggestions.size),
                    onCwdSelected = onCwdSelected,
                    onRemoveRecentCwd = onRemoveRecentCwd,
                )
            }
        }
    }
}

/** The same grouped rows as the project sheet that opens this one, at full touch height. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CwdSuggestionRow(
    suggestion: CwdSuggestion,
    shapes: ListItemShapes,
    onCwdSelected: (String) -> Unit,
    onRemoveRecentCwd: (String) -> Unit,
) {
    val isRecent = suggestion.source == CwdSuggestionSource.RECENT
    val removeRecent = { onRemoveRecentCwd(suggestion.cwd) }
    SegmentedListItem(
        onClick = { onCwdSelected(suggestion.cwd) },
        shapes = shapes,
        modifier =
            Modifier
                .handCursor()
                .onSecondaryClick(enabled = isRecent, onClick = removeRecent),
        leadingContent = {
            Icon(
                imageVector = if (isRecent) Icons.Filled.History else Icons.Filled.FolderOpen,
                contentDescription = null,
            )
        },
        onLongClick = removeRecent.takeIf { isRecent },
        onLongClickLabel = stringResource(R.string.sessionlist_cwd_remove_recent).takeIf { isRecent },
    ) {
        Text(
            text = suggestion.cwd,
            overflow = TextOverflow.MiddleEllipsis,
            maxLines = 1,
        )
    }
}
