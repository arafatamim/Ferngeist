@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.allChoices
import com.tamimarafat.ferngeist.acp.bridge.session.displayValueLabel
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
        modifier = Modifier
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
    listBottomPadding: Dp,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(userScrollDetector),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 0.dp),
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
    val kind: String?,
    val options: List<AcpPermissionOption>,
)

internal fun List<ChatMessage>.latestPendingPermissionRequest(): PendingPermissionRequest? {
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments.asReversed().firstNotNullOfOrNull { segment ->
            val toolCall = segment.toolCall ?: return@firstNotNullOfOrNull null
            val toolCallId = toolCall.toolCallId ?: return@firstNotNullOfOrNull null
            val permissionOptions = toolCall.permissionOptions?.takeIf { it.isNotEmpty() }
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
        message.segments.asReversed().firstOrNull { it.id == targetId }?.toolCall
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
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
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
                request.kind?.takeIf { it.isNotBlank() }?.let { kind ->
                    Text(
                        text = kind,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = toolCall.title.ifBlank { "Tool Call" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                toolCall.kind?.takeIf { it.isNotBlank() }?.let { kind ->
                    Text(
                        text = kind,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
                toolCall.status?.takeIf { it.isNotBlank() }?.let { status ->
                    Text(
                        text = status.replace('_', ' '),
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

            (toolCall.output ?: toolCall.rawOutput)?.takeIf { it.isNotBlank() }?.let { output ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            } ?: Text(
                text = "No tool output.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            modifier = Modifier
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

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = thought,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChatComposerBar(
    modifier: Modifier = Modifier,
    state: ChatState,
    toolbarConfigOptions: List<SessionConfigOption>,
    composerExpanded: Boolean,
    onComposerExpandedChange: (Boolean) -> Unit,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: SessionConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    screenWidth: Dp,
    focusRequester: FocusRequester,
    onFocusCleared: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    onSend: () -> Unit,
    onCancelStreaming: () -> Unit,
    onSetStringConfigOption: (String, String) -> Unit,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
) {
    var showModeMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }
    val animatedHeight by animateDpAsState(
        targetValue = if (composerExpanded) 142.dp else 62.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "ComposerHeight",
    )
    val collapsedMaxToolbarWidth = screenWidth * 0.92f

    Surface(
        shape = if (composerExpanded) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = modifier
            .height(animatedHeight)
            .then(
                if (composerExpanded) {
                    Modifier.fillMaxWidth(0.92f)
                } else {
                    Modifier.widthIn(max = collapsedMaxToolbarWidth)
                }
            )
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (composerExpanded) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .wrapContentWidth()
                    }
                )
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = if (composerExpanded) Alignment.Bottom else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (composerExpanded) {
                ExpandedComposerContent(
                    messageText = messageText,
                    onMessageTextChange = onMessageTextChange,
                    inputAlpha = inputAlpha,
                    focusRequester = focusRequester,
                    showStopAction = showStopAction,
                    canCancelStreaming = canCancelStreaming,
                    onClose = {
                        onComposerExpandedChange(false)
                        onFocusCleared()
                    },
                    onPrimaryAction = {
                        if (showStopAction && canCancelStreaming) {
                            onCancelStreaming()
                        } else if (!showStopAction) {
                            onSend()
                        }
                    },
                    onSend = onSend,
                )
            } else {
                CollapsedComposerActions(
                    state = state,
                    toolbarConfigOptions = toolbarConfigOptions,
                    buttonsAlpha = buttonsAlpha,
                    showModeButton = showModeButton,
                    modeOption = modeOption,
                    currentModeLabel = currentModeLabel,
                    showStopAction = showStopAction,
                    canCancelStreaming = canCancelStreaming,
                    collapsedMaxToolbarWidth = collapsedMaxToolbarWidth,
                    showModeMenu = showModeMenu,
                    onShowModeMenuChange = { showModeMenu = it },
                    modeMenuInteractionSource = modeMenuInteractionSource,
                    showOptionsMenu = showOptionsMenu,
                    onShowOptionsMenuChange = { showOptionsMenu = it },
                    optionsMenuInteractionSource = optionsMenuInteractionSource,
                    onExpandComposer = { onComposerExpandedChange(true) },
                    onCancelStreaming = onCancelStreaming,
                    onSetStringConfigOption = onSetStringConfigOption,
                    onSetBooleanConfigOption = onSetBooleanConfigOption,
                    onShowCommands = onShowCommands,
                    onShowConfigOptionPicker = onShowConfigOptionPicker,
                )
            }
        }
    }
}

@Composable
private fun ExpandedComposerContent(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    focusRequester: FocusRequester,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    onClose: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp)
            .alpha(inputAlpha),
    ) {
        val selectionColors = TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.onPrimary,
            backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
        )
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = messageText,
                onValueChange = onMessageTextChange,
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onPrimary,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (messageText.isEmpty()) {
                            Text(
                                text = "Type a message…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close composer",
                )
            }

            PrimaryComposerActionButton(
                showStopAction = showStopAction,
                canCancelStreaming = canCancelStreaming,
                enabled = if (showStopAction) canCancelStreaming else messageText.isNotBlank(),
                onClick = onPrimaryAction,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsedComposerActions(
    state: ChatState,
    toolbarConfigOptions: List<SessionConfigOption>,
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: SessionConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    collapsedMaxToolbarWidth: Dp,
    showModeMenu: Boolean,
    onShowModeMenuChange: (Boolean) -> Unit,
    modeMenuInteractionSource: MutableInteractionSource,
    showOptionsMenu: Boolean,
    onShowOptionsMenuChange: (Boolean) -> Unit,
    optionsMenuInteractionSource: MutableInteractionSource,
    onExpandComposer: () -> Unit,
    onCancelStreaming: () -> Unit,
    onSetStringConfigOption: (String, String) -> Unit,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
) {
    if (showModeButton && modeOption != null) {
        ModeMenuButton(
            modifier = Modifier.alpha(buttonsAlpha),
            currentModeLabel = currentModeLabel,
            modeOption = modeOption,
            maxWidth = collapsedMaxToolbarWidth * 0.45f,
            expanded = showModeMenu,
            onExpandedChange = onShowModeMenuChange,
            interactionSource = modeMenuInteractionSource,
            onSetConfigOption = onSetStringConfigOption,
        )
        Spacer(modifier = Modifier.width(6.dp))
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    when {
                        showStopAction && canCancelStreaming -> "Stop"
                        showStopAction && !canCancelStreaming -> "Cancel unavailable"
                        else -> "Chat"
                    }
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        PrimaryComposerActionButton(
            showStopAction = showStopAction,
            canCancelStreaming = canCancelStreaming,
            enabled = !showStopAction || canCancelStreaming,
            modifier = Modifier.size(
                IconButtonDefaults.smallContainerSize(
                    IconButtonDefaults.IconButtonWidthOption.Wide,
                )
            ),
            onClick = {
                if (showStopAction && canCancelStreaming) {
                    onCancelStreaming()
                } else if (!showStopAction) {
                    onExpandComposer()
                }
            },
            chatIcon = Icons.Default.Edit,
        )
    }

    ToolbarOptionsButton(
        commandsAdvertised = state.commandsAdvertised,
        configOptions = toolbarConfigOptions,
        expanded = showOptionsMenu,
        onExpandedChange = onShowOptionsMenuChange,
        interactionSource = optionsMenuInteractionSource,
        onSetBooleanConfigOption = onSetBooleanConfigOption,
        onShowCommands = onShowCommands,
        onShowConfigOptionPicker = onShowConfigOptionPicker,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeMenuButton(
    modifier: Modifier = Modifier,
    currentModeLabel: String,
    modeOption: SessionConfigOption.Select,
    maxWidth: Dp,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetConfigOption: (String, String) -> Unit,
) {
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text("Mode") } },
            state = rememberTooltipState(),
        ) {
            TextButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.widthIn(max = maxWidth),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = Color.Transparent
                )
            ) {
                Text(
                    text = currentModeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = interactionSource,
            ) {
                val availableModes = modeOption.allChoices()
                val modeCount = availableModes.size
                if (modeCount == 0) {
                    DropdownMenuItem(
                        text = { Text("No modes available") },
                        onClick = { onExpandedChange(false) },
                        enabled = false,
                    )
                } else {
                    availableModes.forEachIndexed { index, mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label.uppercase()) },
                            shapes = MenuDefaults.itemShape(index, modeCount),
                            checked = mode.value == modeOption.currentValue,
                            onCheckedChange = { checked ->
                                if (checked && mode.value != modeOption.currentValue) {
                                    onExpandedChange(false)
                                    onSetConfigOption(modeOption.id, mode.value)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarOptionsButton(
    commandsAdvertised: Boolean,
    configOptions: List<SessionConfigOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("Options") } },
        state = rememberTooltipState(),
    ) {
        Box {
            IconButton(onClick = { onExpandedChange(true) }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                )
            }
            DropdownMenuPopup(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(0, 1),
                    interactionSource = interactionSource,
                ) {
                    val hasConfigOptions = configOptions.isNotEmpty()
                    if (commandsAdvertised) {
                        DropdownMenuItem(
                            text = { Text("Commands") },
                            onClick = {
                                onExpandedChange(false)
                                onShowCommands()
                            },
                        )
                    }

                    configOptions.forEach { option ->
                        ConfigOptionMenuItem(
                            option = option,
                            onClick = {
                                when (option) {
                                    is SessionConfigOption.BooleanOption -> {
                                        onExpandedChange(false)
                                        onSetBooleanConfigOption(option.id, !option.currentValue)
                                    }

                                    is SessionConfigOption.Select -> {
                                        onExpandedChange(false)
                                        onShowConfigOptionPicker(option.id)
                                    }

                                    is SessionConfigOption.Unknown -> Unit
                                }
                            },
                        )
                    }

                    if (!commandsAdvertised && !hasConfigOptions) {
                        DropdownMenuItem(
                            text = { Text("No options available") },
                            onClick = { onExpandedChange(false) },
                            enabled = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigOptionMenuItem(
    option: SessionConfigOption,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(option.name)
                Text(
                    text = option.dropdownSubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onClick,
        enabled = option is SessionConfigOption.Select || option is SessionConfigOption.BooleanOption,
    )
}

private fun SessionConfigOption.dropdownSubtitle(): String {
    return when (this) {
        is SessionConfigOption.BooleanOption -> if (currentValue) "Enabled" else "Disabled"
        else -> displayValueLabel() ?: "Not selected"
    }
}

@Composable
private fun PrimaryComposerActionButton(
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    chatIcon: ImageVector = Icons.Default.ArrowUpward,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onPrimary,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (showStopAction && canCancelStreaming) Icons.Default.Stop else chatIcon,
            contentDescription = if (showStopAction && canCancelStreaming) "Stop" else "Send",
        )
    }
}
