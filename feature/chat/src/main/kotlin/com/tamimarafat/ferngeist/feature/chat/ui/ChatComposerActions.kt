package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.focus.FocusManager
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal fun CoroutineScope.rememberSendHandlers(
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
                mutations.selectedFiles.value.isNotEmpty() ||
                mutations.selectedAudios.value.isNotEmpty() ||
                mutations.selectedResourceLinks.value.isNotEmpty()
        if (hasContent) {
            confirmOrSend(viewModel, mutations) {
                viewModel.dispatch(
                    ChatIntent.SendMessage(
                        text = mutations.messageText.text.toString(),
                        images = mutations.selectedImages.value,
                        files = mutations.selectedFiles.value,
                        audios = mutations.selectedAudios.value,
                        resourceLinks = mutations.selectedResourceLinks.value,
                    ),
                )
                scrollHandle.onSendMessage()
                mutations.messageText.clearText()
                mutations.selectedImages.value = emptyList()
                mutations.selectedFiles.value = emptyList()
                mutations.selectedAudios.value = emptyList()
                mutations.selectedResourceLinks.value = emptyList()
                mutations.composerMode.value = ComposerMode.COLLAPSED
                focusManager.clearFocus()
            }
        }
    }

    // --- Slash-command send (commands sheet) ---
    val sendCommand = sendCommandHandler(viewModel, state, mutations, scrollHandle)
    return sendMessage to sendCommand
}

private fun CoroutineScope.sendCommandHandler(
    viewModel: ChatViewModel,
    state: ChatState,
    mutations: ChatScreenMutations,
    scrollHandle: ChatScrollHandle,
): (String) -> Unit =
    { command ->
        if (state.isSessionReady) {
            val normalized = command.trim()
            val slashCommand = if (normalized.startsWith("/")) normalized else "/$normalized"
            val hint = state.availableCommands.findCommand(slashCommand)?.inputHint
            if (hint != null) {
                // A command that takes an argument is staged. The user can see what the
                // argument should be and send it themselves once it is typed.
                mutations.messageText.edit {
                    replace(0, 0, "$slashCommand ")
                }
                mutations.openComposer()
                mutations.composerFocusTrigger.value++
            } else {
                confirmOrSend(viewModel, mutations) {
                    viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
                    scrollHandle.onSendMessage()
                }
            }
        }
    }

private fun List<ChatCommand>.findCommand(slashCommand: String): ChatCommand? {
    val name = slashCommand.removePrefix("/")
    return firstOrNull { it.name == name }
}

/**
 * The hint a slash command currently staged in the composer should show, if any.
 *
 * Commands are represented by the exact `/name ` prefix the sheet inserts. As soon as the
 * user types an argument the hint disappears. Deriving this from the text means a hand-typed
 * command gets the same hint as one picked from the sheet without adding autocomplete.
 */
internal fun activeCommandInputHint(
    text: CharSequence,
    commands: List<ChatCommand>,
): String? {
    val staged = text.toString()
    if (staged.isEmpty() || !staged.endsWith(' ')) return null
    return commands
        .firstOrNull { command ->
            command.inputHint != null && staged == "/${command.name} "
        }?.inputHint
}

private fun CoroutineScope.confirmOrSend(
    viewModel: ChatViewModel,
    mutations: ChatScreenMutations,
    send: () -> Unit,
) {
    launch(start = CoroutineStart.UNDISPATCHED) {
        if (mutations.pendingSend.value != null || !mutations.sendCheckLock.tryLock()) return@launch
        try {
            if (viewModel.needsActivityConfirmation()) {
                mutations.pendingSend.value = send
            } else {
                send()
            }
        } finally {
            mutations.sendCheckLock.unlock()
        }
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
