package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreenDialogsHost(
    screenState: ChatScreenState,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val noBrowserMessage = stringResource(R.string.chat_elicitation_no_browser)
    ChatScreenDialogs(
        selectedConfigPickerOption = screenState.derived.selectedConfigPickerOption,
        onConfigOptionSelected = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.StringValue(value),
                ),
            )
        },
        onDismissConfigPicker = { screenState.mutations.selectedConfigPickerOptionId.value = null },
        selectedThought = screenState.derived.selectedThought,
        onDismissThought = { screenState.mutations.selectedThoughtSegmentId.value = null },
        selectedToolCall = screenState.derived.selectedToolCall,
        onDismissToolCall = { screenState.mutations.selectedToolCallSegmentId.value = null },
        activePermissionRequest = screenState.derived.activePermissionRequest,
        onPermissionGrant = { toolCallId, optionId ->
            viewModel.dispatch(
                ChatIntent.GrantPermission(toolCallId, optionId),
            )
        },
        onPermissionDeny = { toolCallId ->
            viewModel.dispatch(ChatIntent.DenyPermission(toolCallId))
        },
        activeElicitationRequest = screenState.state.pendingElicitations.firstOrNull(),
        onElicitationSubmit = { key, values ->
            viewModel.dispatch(ChatIntent.SubmitElicitation(key, values))
        },
        onElicitationDecline = { key ->
            viewModel.dispatch(ChatIntent.DeclineElicitation(key))
        },
        onElicitationCancel = { key ->
            viewModel.dispatch(ChatIntent.CancelElicitation(key))
        },
        onElicitationOpenUrl = { request ->
            openElicitationUrl(
                context = context,
                request = request,
                onConsented = { viewModel.dispatch(ChatIntent.SubmitElicitation(request.key, emptyMap())) },
                onNoHandler = {
                    coroutineScope.launch { screenState.mutations.snackbarHostState.showSnackbar(noBrowserMessage) }
                },
            )
        },
        showConnectionStatusDialog = screenState.mutations.showConnectionStatusDialog.value,
        connectionState = screenState.state.connectionState,
        diagnostics = screenState.state.connectionDiagnostics,
        fetchQuota = remember(viewModel) { viewModel::fetchProviderQuotas },
        usage = screenState.state.usage,
        onDismissConnectionStatus = { screenState.mutations.showConnectionStatusDialog.value = false },
        showCommandsDialog = screenState.mutations.showCommandsDialog.value,
        commands = screenState.state.availableCommands,
        serverId = screenState.state.serverId,
        recentSelectionStore = viewModel.recentSelectionStore,
        onDismissCommands = { screenState.mutations.showCommandsDialog.value = false },
        onCommandClick = screenState.actions.sendCommand,
    )
}

/**
 * Opens a URL-mode elicitation in the system browser: the Open button is the
 * explicit consent, so a successful launch also answers `accept` (empty content:
 * the interaction itself happens out of band). When nothing handles the link,
 * the sheet stays up and the failure surfaces as a snackbar instead.
 *
 * Only http(s) links open: the URL is agent-controlled, and other schemes
 * (`intent:`, `tel:`, `file:`, ...) could dispatch arbitrary app components.
 */
private fun openElicitationUrl(
    context: android.content.Context,
    request: com.tamimarafat.ferngeist.core.model.ChatElicitationRequest.Url,
    onConsented: () -> Unit,
    onNoHandler: () -> Unit,
) {
    val uri = runCatching { request.url.toUri() }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || (scheme != "http" && scheme != "https")) {
        onNoHandler()
        return
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (opened) onConsented() else onNoHandler()
}
