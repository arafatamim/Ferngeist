package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.focus.FocusManager
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun rememberSendHandlers(
    viewModel: ChatViewModel,
    state: ChatState,
    mutations: ChatScreenMutations,
    focusManager: FocusManager,
    scrollHandle: ChatScrollHandle,
): Pair<() -> Unit, (String) -> Unit> {
    // --- Send message (composer) ---
    val sendMessage: () -> Unit = {
        val hasContent =
            mutations.messageText.text.isNotBlank() ||
                mutations.selectedImages.value.isNotEmpty() ||
                mutations.selectedFiles.value.isNotEmpty()
        if (hasContent) {
            confirmOrSend(viewModel, mutations) {
                viewModel.dispatch(
                    ChatIntent.SendMessage(
                        text = mutations.messageText.text.toString(),
                        images = mutations.selectedImages.value,
                        files = mutations.selectedFiles.value,
                    ),
                )
                scrollHandle.onSendMessage()
                mutations.messageText.clearText()
                mutations.selectedImages.value = emptyList()
                mutations.selectedFiles.value = emptyList()
                mutations.composerExpanded.value = false
                focusManager.clearFocus()
            }
        }
    }

    // --- Slash-command send (commands sheet) ---
    val sendCommand: (String) -> Unit = { command ->
        if (state.isSessionReady) {
            confirmOrSend(viewModel, mutations) {
                val normalized = command.trim()
                val slashCommand = if (normalized.startsWith("/")) normalized else "/$normalized"
                viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
                scrollHandle.onSendMessage()
            }
        }
    }
    return sendMessage to sendCommand
}

private fun confirmOrSend(
    viewModel: ChatViewModel,
    mutations: ChatScreenMutations,
    send: () -> Unit,
) {
    if (viewModel.needsActivityConfirmation()) {
        mutations.pendingSend.value = send
    } else {
        send()
    }
}

internal data class ComposerCallbacks(
    val onCancelStreaming: () -> Unit,
    val onSetStringConfigOption: (String, String) -> Unit,
    val onSetBooleanConfigOption: (String, Boolean) -> Unit,
    val onShowCommands: () -> Unit,
    val onShowConfigOptionPicker: (String) -> Unit,
    val onJumpToBottom: () -> Unit,
)

internal fun rememberComposerCallbacks(
    screenState: ChatScreenState,
    coroutineScope: CoroutineScope,
    viewModel: ChatViewModel,
): ComposerCallbacks =
    ComposerCallbacks(
        onCancelStreaming = {
            viewModel.dispatch(ChatIntent.CancelStreaming)
        },
        onSetStringConfigOption = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.StringValue(value),
                ),
            )
        },
        onSetBooleanConfigOption = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.BoolValue(value),
                ),
            )
        },
        onShowCommands = { screenState.mutations.showCommandsDialog.value = true },
        onShowConfigOptionPicker = { optionId ->
            screenState.mutations.selectedConfigPickerOptionId.value = optionId
        },
        onJumpToBottom = {
            coroutineScope.launch { screenState.derived.scrollHandle.jumpToBottom() }
        },
    )
