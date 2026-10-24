package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
internal data class ChatDerivedValues(
    val activeModel: String?,
    val modeOption: ChatConfigOption.Select?,
    val toolbarConfigOptions: List<ChatConfigOption>,
    val selectedConfigPickerOption: ChatConfigOption.Select?,
    val canCancelStreaming: Boolean,
    val hasStreamingBubble: Boolean,
    val activelyStreaming: Boolean,
    val showStopAction: Boolean,
    val showModeButton: Boolean,
    val currentModeLabel: String,
    val gitAdditions: Int,
    val gitDeletions: Int,
    val renderedMessages: List<ChatMessage>,
    val selectedThought: String?,
    val selectedToolCall: ToolCallDisplay?,
    val activePermissionRequest: PendingPermissionRequest?,
    val renderedLastMessageId: String?,
    val scrollHandle: ChatScrollHandle,
    val showJumpToBottom: State<Boolean>,
    val buttonsAlphaState: State<Float>,
    val inputAlphaState: State<Float>,
    val fadeSpring: SpringSpec<Float>,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private data class ChatScrollAndAnimation(
    val scrollHandle: ChatScrollHandle,
    val showJumpToBottom: State<Boolean>,
    val buttonsAlphaState: State<Float>,
    val inputAlphaState: State<Float>,
    val fadeSpring: SpringSpec<Float>,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberChatScrollAndAnimation(
    state: ChatState,
    sessionId: String,
    renderedMessages: List<ChatMessage>,
    activelyStreaming: Boolean,
    composerExpanded: MutableState<Boolean>,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatScrollAndAnimation {
    // --- Scroll system ---
    val scrollHandle =
        rememberChatScrollState(
            sessionId = sessionId,
            renderedMessages = renderedMessages,
            activelyStreaming = activelyStreaming,
            restoredScrollSnapshot = state.restoredScrollSnapshot,
            restoreReady = renderedMessages.isNotEmpty() && !state.isLoading,
            onScrollSnapshotChanged = onScrollSnapshotChanged,
        )

    val showJumpToBottom = scrollHandle.showJumpToBottom

    // --- Composer spring animations ---
    val fadeSpring =
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    val buttonsAlphaState =
        animateFloatAsState(
            targetValue = if (composerExpanded.value) 0f else 1f,
            animationSpec = fadeSpring,
            label = "ButtonsAlpha",
        )
    val inputAlphaState =
        animateFloatAsState(
            targetValue = if (composerExpanded.value) 1f else 0f,
            animationSpec = fadeSpring,
            label = "InputAlpha",
        )

    return ChatScrollAndAnimation(
        scrollHandle = scrollHandle,
        showJumpToBottom = showJumpToBottom,
        buttonsAlphaState = buttonsAlphaState,
        inputAlphaState = inputAlphaState,
        fadeSpring = fadeSpring,
    )
}

private data class ChatMessagesState(
    val renderedMessages: List<ChatMessage>,
    val selectedThought: String?,
    val selectedToolCall: ToolCallDisplay?,
    val activePermissionRequest: PendingPermissionRequest?,
    val renderedLastMessageId: String?,
)

@Composable
private fun rememberChatMessages(
    state: ChatState,
    selectedThoughtSegmentId: MutableState<String?>,
    selectedToolCallSegmentId: MutableState<String?>,
): ChatMessagesState {
    // --- Messages & selections ---
    val renderedMessages =
        state.messages
    val selectedThought =
        remember(renderedMessages, selectedThoughtSegmentId.value) {
            renderedMessages.thoughtForSegment(selectedThoughtSegmentId.value)
        }
    val selectedToolCall =
        remember(renderedMessages, selectedToolCallSegmentId.value, state.git.status, state.git.dirtyAtStart) {
            renderedMessages.toolCallForSelection(selectedToolCallSegmentId.value, state.git.turnWorkingTree())
        }
    val activePermissionRequest =
        remember(renderedMessages) {
            renderedMessages.latestPendingPermissionRequest()
        }
    val renderedLastMessageId = renderedMessages.lastOrNull()?.id

    return ChatMessagesState(
        renderedMessages = renderedMessages,
        selectedThought = selectedThought,
        selectedToolCall = selectedToolCall,
        activePermissionRequest = activePermissionRequest,
        renderedLastMessageId = renderedLastMessageId,
    )
}

private data class ChatConfigDerived(
    val activeModel: String?,
    val modeOption: ChatConfigOption.Select?,
    val toolbarConfigOptions: List<ChatConfigOption>,
    val selectedConfigPickerOption: ChatConfigOption.Select?,
    val canCancelStreaming: Boolean,
    val hasStreamingBubble: Boolean,
    val activelyStreaming: Boolean,
    val showStopAction: Boolean,
    val showModeButton: Boolean,
    val currentModeLabel: String,
)

@Composable
private fun rememberChatConfigDerived(
    state: ChatState,
    selectedConfigPickerOptionId: MutableState<String?>,
): ChatConfigDerived {
    // --- Derived values from config options ---
    val activeModel =
        remember(state.configOptions) {
            state.configOptions
                .firstOrNull { it.category is ChatConfigCategory.Model }
                ?.displayValueLabel()
        }
    val modeOption =
        remember(state.configOptions) {
            state.configOptions
                .filterIsInstance<ChatConfigOption.Select>()
                .firstOrNull {
                    it.category is ChatConfigCategory.Mode && it.allChoices().isNotEmpty()
                }
        }
    val toolbarConfigOptions =
        remember(state.configOptions) {
            state.configOptions.filterNot { it.category is ChatConfigCategory.Mode }
        }
    val selectedConfigPickerOption =
        remember(state.configOptions, selectedConfigPickerOptionId.value) {
            val optionId = selectedConfigPickerOptionId.value ?: return@remember null
            state.configOptions.firstOrNull { it.id == optionId } as? ChatConfigOption.Select
        }

    // --- Streaming / stop flags ---
    val canCancelStreaming = state.canCancelStreaming
    val hasStreamingBubble = state.messages.lastOrNull()?.isStreaming == true
    val activelyStreaming = state.isStreaming || hasStreamingBubble
    val showStopAction = state.isStreaming && hasStreamingBubble

    // --- Composer mode button ---
    val showModeButton = modeOption != null
    val defaultModeLabel = stringResource(R.string.chat_mode_label)
    val currentModeLabel =
        remember(modeOption) {
            modeOption?.displayValueLabel()?.uppercase() ?: defaultModeLabel
        }

    return ChatConfigDerived(
        activeModel = activeModel,
        modeOption = modeOption,
        toolbarConfigOptions = toolbarConfigOptions,
        selectedConfigPickerOption = selectedConfigPickerOption,
        canCancelStreaming = canCancelStreaming,
        hasStreamingBubble = hasStreamingBubble,
        activelyStreaming = activelyStreaming,
        showStopAction = showStopAction,
        showModeButton = showModeButton,
        currentModeLabel = currentModeLabel,
    )
}

@Composable
internal fun rememberChatDerived(
    state: ChatState,
    sessionId: String,
    selectedConfigPickerOptionId: MutableState<String?>,
    selectedThoughtSegmentId: MutableState<String?>,
    selectedToolCallSegmentId: MutableState<String?>,
    composerExpanded: MutableState<Boolean>,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatDerivedValues {
    val configDerived =
        rememberChatConfigDerived(state, selectedConfigPickerOptionId)
    val gitAdditions = state.git.diffStats?.additions ?: 0
    val gitDeletions = state.git.diffStats?.deletions ?: 0
    val messages =
        rememberChatMessages(state, selectedThoughtSegmentId, selectedToolCallSegmentId)
    val scrollAndAnimation =
        rememberChatScrollAndAnimation(
            state = state,
            sessionId = sessionId,
            renderedMessages = messages.renderedMessages,
            activelyStreaming = configDerived.activelyStreaming,
            composerExpanded = composerExpanded,
            onScrollSnapshotChanged = onScrollSnapshotChanged,
        )
    return buildChatDerivedValues(
        configDerived = configDerived,
        gitAdditions = gitAdditions,
        gitDeletions = gitDeletions,
        messages = messages,
        scrollAndAnimation = scrollAndAnimation,
    )
}

private fun buildChatDerivedValues(
    configDerived: ChatConfigDerived,
    gitAdditions: Int,
    gitDeletions: Int,
    messages: ChatMessagesState,
    scrollAndAnimation: ChatScrollAndAnimation,
): ChatDerivedValues =
    ChatDerivedValues(
        activeModel = configDerived.activeModel,
        modeOption = configDerived.modeOption,
        toolbarConfigOptions = configDerived.toolbarConfigOptions,
        selectedConfigPickerOption = configDerived.selectedConfigPickerOption,
        canCancelStreaming = configDerived.canCancelStreaming,
        hasStreamingBubble = configDerived.hasStreamingBubble,
        activelyStreaming = configDerived.activelyStreaming,
        showStopAction = configDerived.showStopAction,
        showModeButton = configDerived.showModeButton,
        currentModeLabel = configDerived.currentModeLabel,
        gitAdditions = gitAdditions,
        gitDeletions = gitDeletions,
        renderedMessages = messages.renderedMessages,
        selectedThought = messages.selectedThought,
        selectedToolCall = messages.selectedToolCall,
        activePermissionRequest = messages.activePermissionRequest,
        renderedLastMessageId = messages.renderedLastMessageId,
        scrollHandle = scrollAndAnimation.scrollHandle,
        showJumpToBottom = scrollAndAnimation.showJumpToBottom,
        buttonsAlphaState = scrollAndAnimation.buttonsAlphaState,
        inputAlphaState = scrollAndAnimation.inputAlphaState,
        fadeSpring = scrollAndAnimation.fadeSpring,
    )
