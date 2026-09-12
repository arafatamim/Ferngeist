@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.launch
import com.mikepenz.markdown.model.State as MarkdownRenderState

private const val INITIAL_WINDOW = 50
private const val WINDOW_STEP = 50

/**
 * Processes picked URIs into images and file attachments, returning the combined
 * lists (capped at max) and an optional feedback message for the user.
 * Extracted from [ChatScreen] to reduce its cyclomatic complexity.
 */
internal suspend fun processPickedUris(
    uris: List<android.net.Uri>,
    context: android.content.Context,
    canSendImages: Boolean,
    supportsEmbeddedContext: Boolean,
    existingImages: List<com.tamimarafat.ferngeist.core.model.ChatImageData>,
    existingFiles: List<com.tamimarafat.ferngeist.core.model.ChatFileData>,
    resources: Resources,
): Triple<
    List<com.tamimarafat.ferngeist.core.model.ChatImageData>,
    List<com.tamimarafat.ferngeist.core.model.ChatFileData>,
    String?,
> {
    val newImages = mutableListOf<com.tamimarafat.ferngeist.core.model.ChatImageData>()
    val fileResults = mutableListOf<FileAttachmentHelper.Result>()
    var imagesDropped = 0
    var unsupportedCount = 0
    for (uri in uris) {
        val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
        when {
            isImage && canSendImages -> {
                val image = ImageAttachmentHelper.uriToChatImageData(context.contentResolver, uri)
                if (image != null) newImages += image else imagesDropped++
            }
            supportsEmbeddedContext ->
                fileResults += FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
            else -> unsupportedCount++
        }
    }
    val newFiles = fileResults.filterIsInstance<FileAttachmentHelper.Result.Success>().map { it.file }
    val tooLargeCount = fileResults.count { it is FileAttachmentHelper.Result.TooLarge }

    val combinedImages = (existingImages + newImages).take(ImageAttachmentHelper.MAX_IMAGES)
    val imagesCapped = (existingImages.size + newImages.size) - combinedImages.size
    val combinedFiles = (existingFiles + newFiles).take(FileAttachmentHelper.MAX_FILES)
    val filesCapped = (existingFiles.size + newFiles.size) - combinedFiles.size

    val feedback =
        buildPickFeedbackMessage(
            resources = resources,
            unsupportedCount = unsupportedCount,
            tooLargeCount = tooLargeCount,
            imagesDropped = imagesDropped,
            imagesCapped = imagesCapped,
            filesCapped = filesCapped,
        )
    return Triple(combinedImages, combinedFiles, feedback)
}

private fun buildPickFeedbackMessage(
    resources: Resources,
    unsupportedCount: Int,
    tooLargeCount: Int,
    imagesDropped: Int,
    imagesCapped: Int,
    filesCapped: Int,
): String? =
    when {
        unsupportedCount > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_attachments_unsupported,
                unsupportedCount,
                unsupportedCount,
            )
        tooLargeCount > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_files_too_large,
                tooLargeCount,
                tooLargeCount,
            )
        imagesDropped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_images_dropped,
                imagesDropped,
                imagesDropped,
            )
        imagesCapped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_images_capped,
                ImageAttachmentHelper.MAX_IMAGES,
                ImageAttachmentHelper.MAX_IMAGES,
            )
        filesCapped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_files_capped,
                FileAttachmentHelper.MAX_FILES,
                FileAttachmentHelper.MAX_FILES,
            )
        else -> null
    }

/**
 * Collects one-shot [ChatEffect]s from the view model and renders them as
 * snackbars or navigates back. Extracted from [ChatScreen] to reduce its
 * cyclomatic complexity.
 */
@Composable
internal fun CollectChatEffects(
    viewModel: com.tamimarafat.ferngeist.feature.chat.ChatViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
) {
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowError -> {
                    android.util.Log.e("ChatScreen", "Chat effect error: ${effect.message}")
                    snackbarHostState.showSnackbar(effect.message)
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.NavigateBack ->
                    onNavigateBack()
            }
        }
    }
}

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
    appBarScrollConnection: NestedScrollConnection,
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
                appBarScrollConnection = appBarScrollConnection,
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
    appBarScrollConnection: NestedScrollConnection,
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

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter)
                    .nestedScroll(userScrollDetector)
                    // The app bar is driven by this list alone. Attaching the app bar's
                    // connection at the Scaffold instead would also capture the scroll of any
                    // other Scaffold descendant — notably the composer's text field, which
                    // floats over this list as a sibling — and collapse the bar instead of
                    // scrolling the text.
                    .nestedScroll(appBarScrollConnection),
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
                        Text(text = stringResource(R.string.chat_load_earlier))
                    }
                }
            }
            items(items = windowed, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    state = state,
                    renderedLastMessageId = renderedLastMessageId,
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
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    state: ChatState,
    renderedLastMessageId: String?,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onStreamLayoutSettled: () -> Unit,
    onRetryMessage: ((String) -> Unit)?,
) {
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
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
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
            PermissionRequestHeader(request = request)
            PermissionRequestOptions(
                request = request,
                onGrantPermission = onGrantPermission,
            )
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
private fun PermissionRequestHeader(request: PendingPermissionRequest) {
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
}

@Composable
private fun PermissionRequestOptions(
    request: PendingPermissionRequest,
    onGrantPermission: (String, String) -> Unit,
) {
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
}

@Composable
private fun ToolCallDetailsSheet(
    toolCall: ToolCallDisplay,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

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
            ToolCallDetailsHeader(toolCall = toolCall)
            ToolCallDetailsBody(toolCall = toolCall)
        }
    }
}

@Composable
private fun ToolCallDetailsHeader(toolCall: ToolCallDisplay) {
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
}

@Composable
private fun ToolCallDetailsBody(toolCall: ToolCallDisplay) {
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

@Composable
private fun ThoughtDetailsSheet(
    thought: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
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
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
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
                        if (!isDirectoryEntry(file)) {
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
