@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.UsageState

@Composable
internal fun ChatScreenDialogs(
    selectedConfigPickerOption: SessionConfigOption.Select?,
    onConfigOptionSelected: (String, String) -> Unit,
    onDismissConfigPicker: () -> Unit,
    selectedThought: String?,
    onDismissThought: () -> Unit,
    selectedToolCall: ToolCallDisplay?,
    onDismissToolCall: () -> Unit,
    activePermissionRequest: PendingPermissionRequest?,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: AcpConnectionState,
    diagnostics: ConnectionDiagnostics,
    usage: UsageState?,
    onDismissConnectionStatus: () -> Unit,
    showCommandsDialog: Boolean,
    commands: List<String>,
    onDismissCommands: () -> Unit,
    onCommandClick: (String) -> Unit,
) {
    if (selectedConfigPickerOption != null) {
        SelectConfigOptionDialog(
            option = selectedConfigPickerOption,
            onOptionSelected = { value ->
                onConfigOptionSelected(selectedConfigPickerOption.id, value)
            },
            onDismiss = onDismissConfigPicker,
        )
    }

    selectedThought?.let { thought ->
        ThoughtDetailsSheet(
            thought = thought,
            onDismiss = onDismissThought,
        )
    }

    selectedToolCall?.let { toolCall ->
        ToolCallDetailsSheet(
            toolCall = toolCall,
            onDismiss = onDismissToolCall,
        )
    }

    activePermissionRequest?.let { request ->
        PermissionRequestSheet(
            request = request,
            onGrantPermission = onPermissionGrant,
            onDenyPermission = onPermissionDeny,
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionDiagnosticsDialog(
            connectionState = connectionState,
            diagnostics = diagnostics,
            totalTokens = usage?.totalTokens ?: diagnostics.lastTotalTokens,
            contextWindowTokens = usage?.contextWindowTokens ?: diagnostics.lastContextWindowTokens,
            costAmount = usage?.costUsd ?: diagnostics.lastCostAmount,
            costCurrency = if (usage?.costUsd != null) "USD" else diagnostics.lastCostCurrency,
            onDismiss = onDismissConnectionStatus,
        )
    }

    if (showCommandsDialog) {
        CommandsDialog(
            commands = commands,
            onDismiss = onDismissCommands,
            onCommandClick = onCommandClick,
        )
    }
}

@Composable
internal fun ChatScreenBody(
    state: ChatState,
    listState: LazyListState,
    userScrollDetector: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onRetryLoad: () -> Unit,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
) {
    when {
        state.isLoading && state.messages.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(64.dp))
            }
        }

        state.error != null && state.messages.isEmpty() -> {
            ChatLoadError(
                message = state.error,
                onRetry = onRetryLoad,
            )
        }

        else -> {
            ChatMessageList(
                state = state,
                listState = listState,
                userScrollDetector = userScrollDetector,
                renderedLastMessageId = renderedLastMessageId,
                listTopPadding = listTopPadding,
                listBottomPadding = listBottomPadding,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
            )
        }
    }
}

@Composable
private fun ChatLoadError(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(320.dp),
            )
            OutlinedButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun ChatMessageList(
    state: ChatState,
    listState: LazyListState,
    userScrollDetector: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(userScrollDetector),
        contentPadding = PaddingValues(start = 16.dp, top = listTopPadding + 8.dp, end = 16.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = state.messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                markdownStates = state.markdownStates,
                showStreamingIndicator = state.isStreaming && message.id == renderedLastMessageId,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
            )
        }
        item(key = "__chat_bottom_spacer") {
            Spacer(modifier = Modifier.height(listBottomPadding))
        }
    }
}

internal data class PendingPermissionRequest(
    val toolCallId: String,
    val requestId: String?,
    val title: String,
    val kind: ToolKind?,
    val options: List<AcpPermissionOption>,
)

internal fun List<ChatMessage>.latestPendingPermissionRequest(): PendingPermissionRequest? {
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments.asReversed().firstNotNullOfOrNull { segment ->
            val toolCall = segment.toolCall ?: return@firstNotNullOfOrNull null
            val toolCallId = toolCall.toolCallId ?: return@firstNotNullOfOrNull null
            val permissionOptions =
                toolCall.permissionOptions?.takeIf { it.isNotEmpty() }
                    ?: return@firstNotNullOfOrNull null
            PendingPermissionRequest(
                toolCallId = toolCallId,
                requestId = toolCall.permissionRequestId,
                title = toolCall.title.ifBlank { "Permission Request" },
                kind = toolCall.kind,
                options = permissionOptions,
            )
        }
    }
}

internal fun List<ChatMessage>.toolCallForSegment(segmentId: String?): ToolCallDisplay? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments
            .asReversed()
            .firstOrNull { it.id == targetId }
            ?.toolCall
    }
}

internal fun List<ChatMessage>.thoughtForSegment(segmentId: String?): String? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments
            .asReversed()
            .firstOrNull { it.id == targetId && it.kind == AssistantSegment.Kind.THOUGHT }
            ?.text
            ?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun PermissionRequestSheet(
    request: PendingPermissionRequest,
    onGrantPermission: (String, String) -> Unit,
    onDenyPermission: (String) -> Unit,
) {
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { value -> value != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Permission Required",
                style = MaterialTheme.typography.titleLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                request.kind?.let { kind ->
                    Text(
                        text = kind.name.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "The agent is waiting for your approval before it can continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                request.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onGrantPermission(request.toolCallId, option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = option.kind,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = { onDenyPermission(request.toolCallId) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Deny")
            }
        }
    }
}

@Composable
private fun ToolCallDetailsSheet(
    toolCall: ToolCallDisplay,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = toolCall.title.ifBlank { "Tool Call" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                toolCall.kind?.let { kind ->
                    Text(
                        text = kind.name.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
                toolCall.status?.let { status ->
                    Text(
                        text = status.name.lowercase().replace('_', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!toolCall.permissionOptions.isNullOrEmpty()) {
                Text(
                    text = "Awaiting permission response",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (toolCall.kind == ToolKind.EXECUTE) {
                val rawInput = toolCall.rawInput
                val displayText = rawInput?.toString()
                if (!displayText.isNullOrBlank()) {
                    ContentBlockRenderer(
                        block = ContentBlock.Text(displayText),
                    )
                }
            }

            val content = toolCall.content
            if (!content.isNullOrEmpty()) {
                content.forEach { tc ->
                    when (tc) {
                        is ToolCallContent.Content -> ContentBlockRenderer(tc.content)
                        is ToolCallContent.Diff -> {
                            DiffRenderer(tc)
                        }

                        is ToolCallContent.Terminal -> TerminalRenderer(tc)
                    }
                }
            } else {
                val rawOutput = toolCall.rawOutput
                if (rawOutput != null) {
                    ContentBlockRenderer(
                        block = ContentBlock.Text(rawOutput.toString()),
                    )
                } else {
                    Text(
                        text = "No tool output.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThoughtDetailsSheet(
    thought: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Reasoning",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            ContentBlockRenderer(
                block = ContentBlock.Text(thought),
            )
        }
    }
}
