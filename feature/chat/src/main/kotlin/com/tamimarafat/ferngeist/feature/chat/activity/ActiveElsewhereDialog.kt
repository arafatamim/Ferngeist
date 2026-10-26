package com.tamimarafat.ferngeist.feature.chat.activity

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun ActiveElsewhereDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_active_elsewhere_title)) },
        text = { Text(stringResource(R.string.chat_active_elsewhere_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_send_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}
