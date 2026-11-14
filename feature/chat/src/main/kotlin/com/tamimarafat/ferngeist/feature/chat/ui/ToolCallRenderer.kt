package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun TerminalRenderer(terminal: ToolCallContent.Terminal) {
    Text(
        text = stringResource(R.string.chat_terminal_output, terminal.terminalId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
