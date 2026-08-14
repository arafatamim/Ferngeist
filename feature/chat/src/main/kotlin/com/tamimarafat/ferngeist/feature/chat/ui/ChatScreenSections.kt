@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.LocalGitSemanticColors
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.gateway.GatewayChangedFile
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.mikepenz.markdown.model.State as MarkdownRenderState

private const val INITIAL_WINDOW = 50
private const val WINDOW_STEP = 50

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
    onRetryMessage: ((String) -> Unit)? = null,
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
                onRetryMessage = onRetryMessage,
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
        ErrorStateCard(
            headline = stringResource(R.string.chat_load_error_title),
            body = message,
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = stringResource(R.string.chat_retry),
            onCta = onRetry,
        )
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
    onRetryMessage: ((String) -> Unit)? = null,
) {
    val allMessages =
        remember(state.messages, state.pendingMessages) {
            state.messages + state.pendingMessages
        }
    var windowSize by rememberSaveable(state.serverId) { mutableIntStateOf(INITIAL_WINDOW) }
    val windowed =
        remember(allMessages, windowSize) {
            allMessages.takeLast(windowSize)
        }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(userScrollDetector),
        contentPadding = PaddingValues(start = 16.dp, top = listTopPadding + 8.dp, end = 16.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (windowSize < allMessages.size) {
            item(key = "__load_older") {
                OutlinedButton(
                    onClick = { windowSize += WINDOW_STEP },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                ) {
                    Text(text = "Load earlier messages")
                }
            }
        }
        items(items = windowed, key = { it.id }) { message ->
            val messageMarkdown =
                remember(message, state.markdownStates) {
                    if (message.role != ChatMessage.Role.ASSISTANT) {
                        persistentMapOf<String, MarkdownRenderState>()
                    } else {
                        buildMap {
                            message.segments.forEach { seg ->
                                state.markdownStates[seg.id]?.let { put(seg.id, it) }
                            }
                            state.markdownStates[message.id]?.let { put(message.id, it) }
                        }.toPersistentMap()
                    }
                }
            MessageBubble(
                message = message,
                markdownStates = messageMarkdown,
                showStreamingIndicator = message.isStreaming && message.id == renderedLastMessageId,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
                onStreamLayoutSettled = onStreamLayoutSettled,
                onRetryMessage = onRetryMessage,
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
                            // Nested inside the sheet's vertical scroll column,
                            // so render rows eagerly without an inner lazy list.
                            DiffRenderer(tc, scrollable = false)
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

/**
 * Modal bottom sheet showing the git working-tree status in detail: branch,
 * ahead/behind, aggregate added/deleted line totals, and a per-file list with the
 * porcelain status letter and per-file line counts — the standard layout most git
 * UIs use. Opened by long-pressing the git status pill in the chat top bar.
 *
 * Tapping a file switches the same sheet to that file's unified diff detail
 * (loading → loaded/error/binary/empty via [gitFileDiff] and friends); the back
 * arrow returns to the list without dismissing the sheet.
 */
@Composable
internal fun GitStatusSheet(
    status: GatewayGitStatus,
    gitFileDiff: List<ToolCallContent.Diff>?,
    gitFileDiffPath: String?,
    isGitFileDiffLoading: Boolean,
    gitFileDiffError: String?,
    onLoadGitDiff: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Selected file path; null shows the summary/list, non-null shows its diff detail.
    // Saved across process death so the detail survives rotation while the request runs.
    var selectedFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    // Re-request the diff when the selection is restored (e.g. process death)
    // or changes outside the row click. The ViewModel records the last requested
    // path in gitFileDiffPath on every dispatch, so matching it means the request
    // for this path is already in flight/settled and nothing is re-sent — the row
    // click's immediate dispatch is preserved and never duplicated here.
    LaunchedEffect(selectedFilePath) {
        val path = selectedFilePath ?: return@LaunchedEffect
        if (isDirectoryPath(path)) {
            selectedFilePath = null
            return@LaunchedEffect
        }
        if (path != gitFileDiffPath) {
            onLoadGitDiff(path)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnimatedContent(
            targetState = selectedFilePath,
            modifier = Modifier.fillMaxWidth(),
            label = "GitStatusSheetContent",
        ) { path ->
            if (path == null) {
                GitStatusListContent(
                    status = status,
                    onFileClick = { file ->
                        if (!isDirectoryPath(file.path)) {
                            selectedFilePath = file.path
                            onLoadGitDiff(file.path)
                        }
                    },
                )
            } else {
                val file = status.changed.firstOrNull { it.path == path }
                GitDiffDetailContent(
                    path = path,
                    file = file,
                    diff = gitFileDiff,
                    diffPath = gitFileDiffPath,
                    isLoading = isGitFileDiffLoading,
                    error = gitFileDiffError,
                    onBack = { selectedFilePath = null },
                    onRetry = { onLoadGitDiff(path) },
                )
            }
        }
    }
}

/**
 * Summary/list body of the git status sheet: branch + ahead/behind header,
 * aggregate line-total card, and the per-file list. Scrolling is contained to
 * the file list so the sheet header stays pinned.
 */
@Composable
private fun GitStatusListContent(
    status: GatewayGitStatus,
    onFileClick: (GatewayChangedFile) -> Unit,
) {
    val additions = status.changed.sumOf { it.added }
    val deletions = status.changed.sumOf { it.removed }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header: title + branch pill + ahead/behind
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.chat_git_status_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                // Branch pill + ahead/behind (like git branch -v)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = status.branch.ifBlank { stringResource(R.string.chat_git_no_branch) },
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (status.ahead > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                tint = LocalGitSemanticColors.current.added,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = status.ahead.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalGitSemanticColors.current.added,
                            )
                        }
                    }
                    if (status.behind > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = status.behind.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Summary card: aggregate line totals + diff-blocks proportion
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (additions > 0) {
                        Text(
                            text = "+$additions",
                            style = MaterialTheme.typography.titleMedium,
                            color = LocalGitSemanticColors.current.added,
                        )
                    }
                    if (deletions > 0) {
                        Text(
                            text = "-$deletions",
                            style = MaterialTheme.typography.titleMedium,
                            color = LocalGitSemanticColors.current.deleted,
                        )
                    }
                    DiffBlocks(additions = additions, deletions = deletions)
                }
                val resources = LocalResources.current
                Text(
                    text =
                        resources.getQuantityString(
                            R.plurals.chat_git_files_changed,
                            status.changed.size,
                            status.changed.size,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Per-file list (own vertical scroll container, no nesting)
        if (status.changed.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_git_clean),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                status.changed.forEach { file ->
                    ChangedFileRow(file = file, onClick = { onFileClick(file) })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Detail body of the git status sheet for one changed file: back arrow with
 * content description, full path, status badge, +N/-N counts, then loading,
 * error-with-retry, binary, empty-diff, or rendered-diff content. The diff
 * owns the only vertical scroll container in detail.
 */
@Composable
private fun GitDiffDetailContent(
    path: String,
    file: GatewayChangedFile?,
    diff: List<ToolCallContent.Diff>?,
    diffPath: String?,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header: back arrow + full path + status badge + +N/-N counts
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.chat_back_desc),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (file != null) {
                // Status badge (same porcelain semantics as the list rows)
                val gitColors = LocalGitSemanticColors.current
                val statusColor =
                    when (file.status) {
                        "M" -> MaterialTheme.colorScheme.secondary
                        "A" -> gitColors.added
                        "R" -> MaterialTheme.colorScheme.tertiary
                        "?" -> MaterialTheme.colorScheme.onSurfaceVariant
                        "D" -> gitColors.deleted
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                val statusBg =
                    when (file.status) {
                        "D" -> gitColors.deleted.copy(alpha = 0.15f)
                        "M" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        "A" -> gitColors.added.copy(alpha = 0.15f)
                        "R" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        "?" -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusBg,
                ) {
                    Text(
                        text = file.status,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = fileNameOf(file.path),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                                    color = LocalGitSemanticColors.current.added,
                                )
                            }
                            if (file.removed > 0) {
                                Text(
                                    text = "-${file.removed}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalGitSemanticColors.current.deleted,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Content: loading / error / binary / empty / rendered diff.
        when {
            file == null -> {
                // Requested path no longer in status (e.g. refreshed while open).
                // The header back arrow above remains available to return to the list.
                // Hardcoded like the diff error strings in ChatViewModel because this
                // file cannot add string resources.
                Text(
                    text = "File is no longer in the working tree: $path",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(64.dp))
                }
            }
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
                    body = error,
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
}

@Composable
private fun ChangedFileRow(
    file: GatewayChangedFile,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isDirectoryPath(file.path)) {
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        } else {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClick = onClick,
                                ).padding(horizontal = 12.dp, vertical = 10.dp)
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status badge (porcelain letter). Modified/renamed use neutral theme
            // roles, while added/deleted use the fixed git semantic colors so the
            // green=add / red=delete meaning survives any theme configuration.
            val gitColors = LocalGitSemanticColors.current
            val statusColor =
                when (file.status) {
                    "M" -> MaterialTheme.colorScheme.secondary
                    "A" -> gitColors.added
                    "R" -> MaterialTheme.colorScheme.tertiary
                    "?" -> MaterialTheme.colorScheme.onSurfaceVariant
                    "D" -> gitColors.deleted
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            val statusBg =
                when (file.status) {
                    "D" -> gitColors.deleted.copy(alpha = 0.15f)
                    "M" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    "A" -> gitColors.added.copy(alpha = 0.15f)
                    "R" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    "?" -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusBg,
            ) {
                Text(
                    text = file.status,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )

            if (isDirectoryPath(file.path)) {
                Text(
                    text = "Directory",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (file.binary) {
                Text(
                    text = stringResource(R.string.chat_git_binary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Reuse the diff-blocks visual (proportion of additions vs deletions)
                // with compact +N / -M counts, matching the top-bar indicator.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (file.added > 0) {
                        Text(
                            text = "+${file.added}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalGitSemanticColors.current.added,
                        )
                    }
                    DiffBlocks(
                        additions = file.added,
                        deletions = file.removed,
                        showEmpty = false,
                    )
                    if (file.removed > 0) {
                        Text(
                            text = "-${file.removed}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalGitSemanticColors.current.deleted,
                        )
                    }
                }
            }
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
    val remainingItems =
        remember(items, recentValues) {
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
            modifier =
                Modifier
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.chat_search_desc),
                            )
                        },
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
                        modifier =
                            Modifier
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
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    onItemClick(item.value)
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }.padding(vertical = 8.dp),
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
    val recentValues by recentSelectionStore
        .getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems =
        remember(recentValues, allChoices, enableRecents) {
            if (!enableRecents) {
                emptyList()
            } else {
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
        }
    PickerSheet(
        title = option.name,
        items =
            allChoices.map { choice ->
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
    val recentNames by recentSelectionStore
        .getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems =
        remember(recentNames, commands, enableRecents) {
            if (!enableRecents) {
                emptyList()
            } else {
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
        }
    PickerSheet(
        title = stringResource(R.string.chat_commands_sheet_title),
        items =
            commands.map { cmd ->
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
