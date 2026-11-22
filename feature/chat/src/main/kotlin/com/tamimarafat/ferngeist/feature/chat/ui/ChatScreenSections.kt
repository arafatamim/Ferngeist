@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.diagnostics.ConnectionDiagnosticsSheet
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.localizeChatError
import com.tamimarafat.ferngeist.feature.chat.thoughtRuns
import com.tamimarafat.ferngeist.feature.chat.ui.AGENT_TEXT_MAX_LINES
import com.tamimarafat.ferngeist.feature.chat.ui.details.ThoughtDetailsSheet
import com.tamimarafat.ferngeist.feature.chat.ui.details.ToolCallDetailsSheet
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle
import com.tamimarafat.ferngeist.feature.chat.ui.skeleton.ChatLoadingSkeleton
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.ChatMessageList
import kotlinx.coroutines.launch
import com.tamimarafat.ferngeist.core.git.R as GitR

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
    onSwitchSession: com.tamimarafat.ferngeist.feature.chat.OnSwitchSession,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowError -> {
                    android.util.Log.e("ChatScreen", "Chat effect error: ${effect.message}")
                    snackbarHostState.showSnackbar(localizeChatError(context, effect.message))
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(localizeChatError(context, effect.message))
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.SwitchSession ->
                    onSwitchSession(effect.session, null)

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
    selectedThought: List<ContentBlock>?,
    onDismissThought: () -> Unit,
    selectedToolCall: ToolCallDisplay?,
    onDismissToolCall: () -> Unit,
    activePermissionRequest: PendingPermissionRequest?,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    onStopTurn: () -> Unit,
    activeElicitationRequest: ChatElicitationRequest?,
    onElicitationSubmit: (String, Map<String, ChatElicitationValue>) -> Unit,
    onElicitationDecline: (String) -> Unit,
    onElicitationCancel: (String) -> Unit,
    onElicitationOpenUrl: (ChatElicitationRequest.Url) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    fetchQuota: suspend () -> List<com.tamimarafat.ferngeist.core.model.AgentQuota>?,
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
            onStopTurn = onStopTurn,
        )
    }

    activeElicitationRequest?.let { request ->
        ElicitationSheet(
            request = request,
            onSubmit = onElicitationSubmit,
            onDecline = onElicitationDecline,
            onCancel = onElicitationCancel,
            onOpenUrl = onElicitationOpenUrl,
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionDiagnosticsSheet(
            connectionState = connectionState,
            diagnostics = diagnostics,
            usage = usage,
            fetchQuota = fetchQuota,
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
    scroll: ChatScrollHandle,
    appBarScrollConnection: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: State<Dp>,
    onRetryLoad: () -> Unit,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)? = null,
) {
    when {
        state.isLoading && state.messages.isEmpty() -> {
            ChatLoadingSkeleton(listTopPadding = listTopPadding)
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
                scroll = scroll,
                appBarScrollConnection = appBarScrollConnection,
                renderedLastMessageId = renderedLastMessageId,
                listTopPadding = listTopPadding,
                listBottomPadding = listBottomPadding,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
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
            body = localizeChatError(LocalContext.current, message),
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = stringResource(GitR.string.chat_retry),
            onCta = onRetry,
        )
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

internal fun List<ChatMessage>.thoughtForSegment(segmentId: String?): List<ContentBlock>? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        // Resolved to the whole run, not the tapped chunk. A streamed thought arrives one segment
        // per chunk, so matching on the segment id alone returned a single fragment and the sheet
        // opened on what looked like broken content.
        message.segments
            .thoughtRuns()
            .firstOrNull { run -> run.segments.any { it.id == targetId } }
            ?.segments
            ?.mapNotNull { it.contentBlock }
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
                            inputHint = cmd.inputHint,
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
                    inputHint = cmd.inputHint,
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
