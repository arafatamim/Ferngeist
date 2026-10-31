@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.displayBlocks
import com.tamimarafat.ferngeist.feature.chat.localizeChatError
import com.tamimarafat.ferngeist.feature.chat.thoughtRuns
import com.tamimarafat.ferngeist.feature.chat.ui.AGENT_TEXT_MAX_LINES
import com.tamimarafat.ferngeist.feature.chat.ui.details.ThoughtDetailsSheet
import com.tamimarafat.ferngeist.feature.chat.ui.details.ToolCallDetailsSheet
import com.tamimarafat.ferngeist.feature.chat.ui.skeleton.ChatLoadingSkeleton
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
    listBottomPadding: Dp,
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
            ctaLabel = stringResource(R.string.chat_retry),
            onCta = onRetry,
        )
    }
}

@Composable
private fun ChatMessageList(
    state: ChatState,
    scroll: ChatScrollHandle,
    appBarScrollConnection: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)? = null,
) {
    val listState = scroll.listState
    val allMessages = state.messages
    val itemKey: (ChatMessage) -> String = { state.listKeys[it.id] ?: it.id }
    val contentTop = listTopPadding + 8.dp
    val window = rememberMessageWindow(state, allMessages, listState, itemKey)
    val windowed = window.messages
    // The rows above the transcript shift every message's index; the pinned prompt needs its own
    // index to find its slot and to scroll its turn back into view.
    val leadingRows = (if (state.resumedSession) 1 else 0) + (if (window.hasOlder) 1 else 0)
    val userRows =
        remember(windowed, leadingRows) {
            windowed.indices
                .filter { windowed[it].role == ChatMessage.Role.USER && windowed[it].content.isNotBlank() }
                .map { leadingRows + it }
        }
    val fadePx = with(LocalDensity.current) { pinnedPromptFadeDistance.roundToPx() }
    val runway = rememberRunway(scroll, windowed, itemKey, listBottomPadding)
    val messageRow: @Composable (ChatMessage) -> Unit = { message ->
        ChatMessageItem(
            message = message,
            state = state,
            renderedLastMessageId = renderedLastMessageId,
            onThoughtClick = onThoughtClick,
            onToolCallClick = onToolCallClick,
            onRetryMessage = onRetryMessage,
            modifier = Modifier.runwayRow(scroll, runway, itemKey(message)),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(localActiveRails provides scroll.activeRails) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .nestedScroll(scroll.userScrollDetector)
                        // The app bar is driven by this list alone. Attaching the app bar's
                        // connection at the Scaffold instead would also capture the scroll of any
                        // other Scaffold descendant — notably the composer's text field, which
                        // floats over this list as a sibling — and collapse the bar instead of
                        // scrolling the text.
                        .nestedScroll(appBarScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, top = contentTop, end = 16.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leadingItems(state.resumedSession, window)
                // Rows differ in structure (user, plain assistant, segmented assistant), so each
                // carries a contentType: without one Compose cannot tell a like-for-like slot from a
                // different one and re-does the work on every markdown emit. Coarse on purpose — the
                // value only has to match for rows that can share a slot.
                items(
                    items = windowed,
                    key = itemKey,
                    contentType = { messageContentType(it) },
                ) { message ->
                    messageRow(message)
                }
                item(key = BOTTOM_SPACER_KEY) {
                    RunwaySpacer(scroll, runway)
                }
            }
        }
        // Keyed so the pinned chip, once displaced, carries on as the same node instead of being
        // rebuilt mid-push.
        PinnedPromptOverlays(userRows, leadingRows, windowed, scroll, contentTop, fadePx)
        AnimatedVisibility(
            visible = state.isNewChat && allMessages.isEmpty() && state.pendingMessages.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ChatEmptyHero(iconUrl = state.iconUrl)
        }
    }
}

/** The rows above the transcript: the resumed-session seam, then the control that loads older history. */
private fun LazyListScope.leadingItems(
    resumedSession: Boolean,
    window: MessageWindow,
) {
    if (resumedSession) {
        item(key = "__resumed_session_notice") {
            ResumedSessionNotice()
        }
    }
    if (window.hasOlder) {
        item(key = "__load_older") {
            OutlinedButton(
                onClick = window.loadOlder,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.chat_load_earlier))
            }
        }
    }
}

private fun messageContentType(message: ChatMessage): Int =
    when {
        message.role == ChatMessage.Role.USER -> 0
        message.segments.isEmpty() -> 1
        else -> 2
    }

/**
 * Perforated seam at the top of a transcript reattached without history replay
 * (`session/resume`). A rewind marker on a tear line reads as "this conversation
 * continues off-screen", so an empty transcript does not look like a fresh chat.
 */
@Composable
private fun ResumedSessionNotice() {
    val seamColor = MaterialTheme.colorScheme.outlineVariant
    // Dashed rule drawn inline (a named helper would push this file past detekt's
    // 25-function budget); `RowScope` receiver so both sides share one weight.
    val seam: RowScope.() -> Modifier = {
        Modifier
            .weight(1f)
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = seamColor,
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = seam())
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.chat_resumed_session),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = seam())
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    state: ChatState,
    renderedLastMessageId: String?,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Keyed by the same keys the renderer draws from (run keys, not segment ids), so the document
    // looked up here is the one [MessageBubble] will ask for.
    val messageMarkdown =
        remember(message, state.markdownDocuments) {
            if (message.role != ChatMessage.Role.ASSISTANT) {
                persistentMapOf<String, MarkdownRenderedDocument>()
            } else {
                buildMap {
                    message.segments.displayBlocks().forEach { block ->
                        state.markdownDocuments[block.key]?.let { put(block.key, it) }
                    }
                    state.markdownDocuments[message.id]?.let { put(message.id, it) }
                }.toPersistentMap()
            }
        }
    val isLast = message.id == renderedLastMessageId
    CompositionLocalProvider(
        localWorkingTreeChanges provides if (isLast) state.git.turnWorkingTree() else emptyList(),
    ) {
        MessageBubble(
            message = message,
            markdownDocuments = messageMarkdown,
            showStreamingIndicator = message.isStreaming && isLast,
            onThoughtClick = onThoughtClick,
            onToolCallClick = onToolCallClick,
            onRetryMessage = onRetryMessage,
            modifier = modifier,
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

internal fun List<ChatMessage>.thoughtForSegment(segmentId: String?): String? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        // Resolved to the whole run, not the tapped chunk. A streamed thought arrives one segment
        // per chunk, so matching on the segment id alone returned a single fragment and the sheet
        // opened on what looked like broken content.
        message.segments
            .thoughtRuns()
            .firstOrNull { run -> run.segments.any { it.id == targetId } }
            ?.text
            ?.takeIf { it.isNotBlank() }
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
