@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.core.model.UsageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Hosts the dialog/overlay surfaces that sit above the main chat UI.
 */
@Composable
internal fun ChatScreenDialogs(
    selectedConfigPickerOption: ChatConfigOption.Select?,
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
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    usage: UsageState?,
    onDismissConnectionStatus: () -> Unit,
    showCommandsDialog: Boolean,
    commands: List<ChatCommand>,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onDismissCommands: () -> Unit,
    onCommandClick: (String) -> Unit,
) {
    if (selectedConfigPickerOption != null) {
        SelectConfigOptionSheet(
            option = selectedConfigPickerOption,
            serverId = serverId,
            recentSelectionStore = recentSelectionStore,
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
            totalTokens = usage?.totalTokens,
            contextWindowTokens = usage?.contextWindowTokens,
            costAmount = usage?.costAmount,
            costCurrency = usage?.costCurrency,
            onDismiss = onDismissConnectionStatus,
        )
    }

    if (showCommandsDialog) {
        CommandsSheet(
            commands = commands,
            serverId = serverId,
            recentSelectionStore = recentSelectionStore,
            onDismiss = onDismissCommands,
            onCommandClick = onCommandClick,
        )
    }
}

/**
 * Renders the main chat content area, switching between loading, error, and list states.
 */
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
    onStreamLayoutSettled: () -> Unit = {},
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
                onStreamLayoutSettled = onStreamLayoutSettled,
            )
        }
    }
}

/**
 * Full-screen error placeholder shown when the session fails before any messages load.
 */
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
                Text(stringResource(R.string.chat_retry))
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
    onStreamLayoutSettled: () -> Unit = {},
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
                showStreamingIndicator = message.isStreaming && message.id == renderedLastMessageId,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
                onStreamLayoutSettled = onStreamLayoutSettled,
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
                title = toolCall.title,
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
                text = stringResource(R.string.chat_permission_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = request.title.ifBlank { stringResource(R.string.chat_permission_request) },
                    style = MaterialTheme.typography.titleMedium,
                )
                request.kind?.let { kind ->
                    Text(
                        text = toolKindLabel(kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.chat_permission_body),
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
                                text = permissionKindLabel(option.kind),
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
                Text(stringResource(R.string.chat_deny))
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
            val defaultToolCallLabel = stringResource(R.string.chat_tool_call)
            val toolCallSheetTitle = toolCall.title.ifBlank { defaultToolCallLabel }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = toolCallSheetTitle,
                    style = MaterialTheme.typography.bodyLarge,
                )
                toolCall.kind?.let { kind ->
                    Text(
                        text = toolKindLabel(kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
                toolCall.status?.let { status ->
                    Text(
                        text = toolCallStatusLabel(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!toolCall.permissionOptions.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.chat_awaiting_permission),
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
                        text = stringResource(R.string.chat_no_tool_output),
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
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            ContentBlockRenderer(
                block = ContentBlock.Text(thought),
            )
        }
    }
}

private data class PickerItem(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
)

@Composable
private fun PickerSheet(
    title: String,
    items: List<PickerItem>,
    selectedValue: String? = null,
    recentItems: List<PickerItem> = emptyList(),
    onItemClick: (value: String) -> Unit,
    onDismiss: () -> Unit,
    emptyText: String = "",
    noResultsText: String = "",
    searchPlaceholder: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val showSearch = items.size >= 10
    var query by remember { mutableStateOf("") }

    val recentValues = remember(recentItems) { recentItems.map { it.value }.toSet() }
    val remainingItems = remember(items, recentValues) {
        items.filter { it.value !in recentValues }
    }
    val showRecentSection = recentItems.isNotEmpty() && query.isBlank()
    val searchPool = remember(remainingItems, recentItems) { recentItems + remainingItems }

    val filteredOptions =
        remember(searchPool, query) {
            if (!showSearch || query.trim().isBlank()) {
                searchPool
            } else {
                val q = query.trim()
                searchPool.filter { item ->
                    item.label.contains(q, ignoreCase = true) ||
                        item.value.contains(q, ignoreCase = true) ||
                        (item.description?.contains(q, ignoreCase = true) == true)
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            if (items.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (showSearch) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_search_desc)) },
                        placeholder = { Text(searchPlaceholder) },
                        shape = RoundedCornerShape(28.dp),
                    )
                }
                if (filteredOptions.isEmpty()) {
                    Text(
                        text = noResultsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val displayItems = if (showRecentSection) remainingItems else filteredOptions
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (showRecentSection) {
                            Text(
                                text = stringResource(R.string.chat_recent),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            recentItems.forEach { item ->
                                PickerItemRow(item, selectedValue, onItemClick, sheetState, scope, onDismiss)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = stringResource(R.string.chat_all_items),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }

                        displayItems.forEach { item ->
                            PickerItemRow(item, selectedValue, onItemClick, sheetState, scope, onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerItemRow(
    item: PickerItem,
    selectedValue: String?,
    onItemClick: (String) -> Unit,
    sheetState: SheetState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onItemClick(item.value)
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
            item.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selectedValue != null && item.value == selectedValue) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.chat_selected_desc),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SelectConfigOptionSheet(
    option: ChatConfigOption.Select,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Key format: "config_option:$serverId:$optionId"
    // clearByPrefix uses "config_option:$serverId:" trailing colon to avoid cross-server matches
    val storageKey = remember(option.id, serverId) { "config_option:$serverId:${option.id}" }
    val allChoices = remember(option) { option.allChoices() }
    val enableRecents = allChoices.size >= 10
    val recentValues by recentSelectionStore.getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems = remember(recentValues, allChoices, enableRecents) {
        if (!enableRecents) emptyList()
        else
            recentValues.mapNotNull { val_ ->
                allChoices.find { it.value == val_ }?.let { choice ->
                    PickerItem(
                        id = choice.id,
                        label = choice.label,
                        value = choice.value,
                        description = choice.description,
                    )
                }
            }
    }
    PickerSheet(
        title = option.name,
        items = allChoices.map { choice ->
            PickerItem(
                id = choice.id,
                label = choice.label,
                value = choice.value,
                description = choice.description,
            )
        },
        selectedValue = option.currentValue,
        recentItems = recentItems,
        onItemClick = { value ->
            onOptionSelected(value)
            if (enableRecents) {
                scope.launch { recentSelectionStore.addSelection(storageKey, value) }
            }
        },
        onDismiss = onDismiss,
        emptyText = stringResource(R.string.chat_picker_no_values),
        noResultsText = stringResource(R.string.chat_picker_no_models),
        searchPlaceholder = stringResource(R.string.chat_search_placeholder, option.name),
    )
}

@Composable
private fun CommandsSheet(
    commands: List<ChatCommand>,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onCommandClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Trailing colon is required by clearByPrefix (startsWith check).
    // Without it, serverId "abc" would also wipe recents for serverId "abcd".
    val storageKey = "commands:$serverId:"
    val enableRecents = commands.size >= 10
    val recentNames by recentSelectionStore.getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems = remember(recentNames, commands, enableRecents) {
        if (!enableRecents) emptyList()
        else
            recentNames.mapNotNull { name ->
                commands.find { it.name == name }?.let { cmd ->
                    PickerItem(
                        id = cmd.name,
                        label = cmd.name,
                        value = cmd.name,
                        description = cmd.description,
                    )
                }
            }
    }
    PickerSheet(
        title = stringResource(R.string.chat_commands_sheet_title),
        items = commands.map { cmd ->
            PickerItem(
                id = cmd.name,
                label = cmd.name,
                value = cmd.name,
                description = cmd.description,
            )
        },
        recentItems = recentItems,
        onItemClick = { value ->
            onCommandClick(value)
            if (enableRecents) {
                scope.launch { recentSelectionStore.addSelection(storageKey, value) }
            }
        },
        onDismiss = onDismiss,
        emptyText = stringResource(R.string.chat_commands_empty),
        noResultsText = stringResource(R.string.chat_commands_no_results),
        searchPlaceholder = stringResource(R.string.chat_commands_search_hint),
    )
}
