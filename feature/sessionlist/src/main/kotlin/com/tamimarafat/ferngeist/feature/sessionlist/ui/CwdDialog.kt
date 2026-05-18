package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.sessionlist.R

/**
 * Dialog for setting the working-directory filter on sessions.
 * Recent CWDs are shown in an MRU suggestion list — tap to fill the text
 * field, long-press to permanently remove an entry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CwdDialog(
    recentCwds: List<String>,
    cwdDialogValue: String,
    onCwdDialogValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    onRemoveRecentCwd: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessionlist_cwd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sessionlist_cwd_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = cwdDialogValue,
                    onValueChange = onCwdDialogValueChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.sessionlist_cwd_placeholder)) },
                )
                if (recentCwds.isNotEmpty()) {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(recentCwds) { cwd ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onCwdDialogValueChange(cwd) },
                                            onLongClick = { onRemoveRecentCwd(cwd) },
                                        )
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = cwd,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    softWrap = true,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (onClear != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.sessionlist_cwd_clear)) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.sessionlist_cwd_cancel)) }
                TextButton(onClick = onSave) { Text(stringResource(R.string.sessionlist_cwd_save)) }
            }
        },
    )
}
