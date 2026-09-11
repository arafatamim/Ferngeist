package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// region: ChatScreen

/**
 * Full-screen chat session view.
 *
 * Layout hierarchy:
 * - [sharedTransitionScope] Box (shared element bounds for session list transition)
 *   - [Scaffold] with [TwoRowsTopAppBar] via [ChatTopBar]
 *     - [ChatScreenBody] (message list + loading/error states)
 *     - [ChatComposerBar] (floating bottom composer)
 *     - [SnackbarHost]
 *     - Dialogs: thought, tool call, config picker, connection status, commands
 *
 * Auto-scroll is managed by [rememberChatScrollState] which combines a
 * [LazyListState] with an [ChatScrollPolicy] state machine.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionTitle: String,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val screenState = rememberChatScreenState(viewModel, sessionId)

    // --- ViewModel effects ---
    CollectChatEffects(viewModel, screenState.snackbarHostState, onNavigateBack)

    // Auto-focus the text field when the composer expands
    LaunchedEffect(screenState.composerExpanded.value, screenState.imageFocusTrigger.value) {
        if (screenState.composerExpanded.value) {
            screenState.focusRequester.requestFocus()
        }
    }

    ChatScreenScaffold(
        screenState = screenState,
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
}

@Composable
private fun rememberAttachmentPicker(
    state: ChatState,
    selectedImages: MutableState<List<ChatImageData>>,
    selectedFiles: MutableState<List<ChatFileData>>,
    imageFocusTrigger: MutableState<Int>,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): ManagedActivityResultLauncher<Array<String>, List<Uri>> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<android.net.Uri> ->
        coroutineScope.launch {
            val newImages = mutableListOf<ChatImageData>()
            val fileResults = mutableListOf<FileAttachmentHelper.Result>()
            var imagesDropped = 0
            var unsupportedCount = 0
            for (uri in uris) {
                val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
                when {
                    isImage && state.canSendImages -> {
                        val image = ImageAttachmentHelper.uriToChatImageData(context.contentResolver, uri)
                        if (image != null) newImages += image else imagesDropped++
                    }
                    state.supportsEmbeddedContext ->
                        fileResults += FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
                    else -> unsupportedCount++
                }
            }
            val newFiles = fileResults.filterIsInstance<FileAttachmentHelper.Result.Success>().map { it.file }
            val tooLargeCount = fileResults.count { it is FileAttachmentHelper.Result.TooLarge }

            val combinedImages = (selectedImages.value + newImages).take(ImageAttachmentHelper.MAX_IMAGES)
            val imagesCapped = (selectedImages.value.size + newImages.size) - combinedImages.size
            val combinedFiles = (selectedFiles.value + newFiles).take(FileAttachmentHelper.MAX_FILES)
            val filesCapped = (selectedFiles.value.size + newFiles.size) - combinedFiles.size
            selectedImages.value = combinedImages
            selectedFiles.value = combinedFiles
            imageFocusTrigger.value++

            val message =
                attachmentPickFeedbackMessage(
                    resources = resources,
                    unsupportedCount = unsupportedCount,
                    tooLargeCount = tooLargeCount,
                    imagesDropped = imagesDropped,
                    imagesCapped = imagesCapped,
                    filesCapped = filesCapped,
                )
            message?.let { snackbarHostState.showSnackbar(it) }
        }
    }
}

private fun attachmentPickFeedbackMessage(
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
                R.plurals.chat_attachments_unsupported,
                unsupportedCount,
                unsupportedCount,
            )
        tooLargeCount > 0 ->
            resources.getQuantityString(R.plurals.chat_files_too_large, tooLargeCount, tooLargeCount)
        imagesDropped > 0 ->
            resources.getQuantityString(R.plurals.chat_images_dropped, imagesDropped, imagesDropped)
        imagesCapped > 0 ->
            resources.getQuantityString(
                R.plurals.chat_images_capped,
                ImageAttachmentHelper.MAX_IMAGES,
                ImageAttachmentHelper.MAX_IMAGES,
            )
        filesCapped > 0 ->
            resources.getQuantityString(
                R.plurals.chat_files_capped,
                FileAttachmentHelper.MAX_FILES,
                FileAttachmentHelper.MAX_FILES,
            )
        else -> null
    }

private data class ComposerInsets(
    val imeBottomPx: Int,
    val showComposerToolbar: Boolean,
    val listBottomPadding: Dp,
    val bottomFadeBandHeight: Dp,
    val snackbarBottomPadding: Dp,
    val screenWidthDp: Dp,
)

@Composable
private fun rememberComposerInsets(
    state: ChatState,
    composerContentHeightPx: MutableState<Int>,
): ComposerInsets {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val systemBottomInsetPx = if (imeBottomPx > navBottomPx) imeBottomPx else navBottomPx

    // Composer is hidden during initial loading or when error + empty state
    val showComposerToolbar =
        !state.isLoading && !(state.error != null && state.messages.isEmpty())
    val composerContentHeightDp = with(density) { composerContentHeightPx.value.toDp() }
    val containerSize = LocalWindowInfo.current.containerSize
    val systemBottomInsetDp = with(density) { systemBottomInsetPx.toDp() }

    val screenWidthDp =
        remember(density, containerSize) {
            with(density) { containerSize.width.toDp() }
        }

    // Bottom padding for message list: composer height + floating offset + 36dp.
    // No system inset: the list extends edge-to-edge behind the nav bar, and the
    // floating composer still sits above the gesture area via its own padding.
    val listBottomPadding =
        remember(showComposerToolbar, composerContentHeightDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    36.dp
            }
        }

    // Snackbar also needs to sit above the composer bar but with less extra padding (16dp)
    val snackbarBottomPadding =
        remember(showComposerToolbar, composerContentHeightDp, systemBottomInsetDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    16.dp
            }
        }

    // How far the fade climbs from the bottom of the screen: the composer (plus
    // the system bar beneath it) rises this far, and the band starts a little
    // above the composer's own top edge so content begins to dim just before it
    // slides under the pill.
    //
    // [BOTTOM_FADE_LEAD] must stay under the 36dp of clearance the list keeps
    // above the composer (see [listBottomPadding]), otherwise the last message
    // would be tinted while at rest.
    val bottomFadeBandHeight =
        remember(showComposerToolbar, composerContentHeightDp, systemBottomInsetDp) {
            if (!showComposerToolbar) {
                0.dp
            } else {
                composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    BOTTOM_FADE_LEAD
            }
        }
    return ComposerInsets(
        imeBottomPx = imeBottomPx,
        showComposerToolbar = showComposerToolbar,
        listBottomPadding = listBottomPadding,
        bottomFadeBandHeight = bottomFadeBandHeight,
        snackbarBottomPadding = snackbarBottomPadding,
        screenWidthDp = screenWidthDp,
    )
}

// How far above the composer's top edge the bottom fade begins.
private val BOTTOM_FADE_LEAD = 24.dp

// How much of the surface colour the bottom fade reaches at the very bottom of
// the screen. Deliberately short of 1f: content going under the composer is
// dimmed, not dissolved, so it stays faintly readable the way it does when it
// passes under the top bar.
private const val BOTTOM_FADE_MAX_ALPHA = 0.75f

private fun rememberSendHandlers(
    viewModel: ChatViewModel,
    state: ChatState,
    messageText: MutableState<String>,
    selectedImages: MutableState<List<ChatImageData>>,
    selectedFiles: MutableState<List<ChatFileData>>,
    composerExpanded: MutableState<Boolean>,
    focusManager: FocusManager,
    scrollHandle: ChatScrollHandle,
): Pair<() -> Unit, (String) -> Unit> {
    // --- Send message (composer) ---
    val sendMessage: () -> Unit = {
        val hasContent =
            messageText.value.isNotBlank() || selectedImages.value.isNotEmpty() || selectedFiles.value.isNotEmpty()
        if (hasContent) {
            viewModel.dispatch(
                ChatIntent.SendMessage(
                    text = messageText.value,
                    images = selectedImages.value,
                    files = selectedFiles.value,
                ),
            )
            scrollHandle.onSendMessage()
            messageText.value = ""
            selectedImages.value = emptyList()
            selectedFiles.value = emptyList()
            composerExpanded.value = false
            focusManager.clearFocus()
        }
    }

    // --- Slash-command send (commands sheet) ---
    val sendCommand: (String) -> Unit = { command ->
        if (state.isSessionReady) {
            val normalized = command.trim()
            val slashCommand = if (normalized.startsWith("/")) normalized else "/$normalized"
            viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
            scrollHandle.onSendMessage()
        }
    }
    return sendMessage to sendCommand
}

private data class ChatScreenMutations(
    val selectedConfigPickerOptionId: MutableState<String?>,
    val selectedThoughtSegmentId: MutableState<String?>,
    val selectedToolCallSegmentId: MutableState<String?>,
    val showCommandsDialog: MutableState<Boolean>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val showGitStatusSheet: MutableState<Boolean>,
    val composerContentHeightPx: MutableState<Int>,
    val messageText: MutableState<String>,
    val selectedImages: MutableState<List<ChatImageData>>,
    val selectedFiles: MutableState<List<ChatFileData>>,
    val composerExpanded: MutableState<Boolean>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
)

@Composable
private fun rememberChatScreenMutations(): ChatScreenMutations =
    ChatScreenMutations(
        selectedConfigPickerOptionId = remember { mutableStateOf<String?>(null) },
        selectedThoughtSegmentId = remember { mutableStateOf<String?>(null) },
        selectedToolCallSegmentId = remember { mutableStateOf<String?>(null) },
        showCommandsDialog = remember { mutableStateOf(false) },
        showConnectionStatusDialog = remember { mutableStateOf(false) },
        showGitStatusSheet = remember { mutableStateOf(false) },
        composerContentHeightPx = remember { mutableIntStateOf(0) },
        messageText = remember { mutableStateOf("") },
        selectedImages = remember { mutableStateOf<List<ChatImageData>>(emptyList()) },
        selectedFiles = remember { mutableStateOf<List<ChatFileData>>(emptyList()) },
        composerExpanded = remember { mutableStateOf(false) },
        focusRequester = remember { FocusRequester() },
        imageFocusTrigger = remember { mutableIntStateOf(0) },
    )

private class ChatScreenState(
    val state: ChatState,
    val snackbarHostState: SnackbarHostState,
    val selectedConfigPickerOptionId: MutableState<String?>,
    val selectedThoughtSegmentId: MutableState<String?>,
    val selectedToolCallSegmentId: MutableState<String?>,
    val showCommandsDialog: MutableState<Boolean>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val showGitStatusSheet: MutableState<Boolean>,
    val composerContentHeightPx: MutableState<Int>,
    val messageText: MutableState<String>,
    val selectedImages: MutableState<List<ChatImageData>>,
    val selectedFiles: MutableState<List<ChatFileData>>,
    val composerExpanded: MutableState<Boolean>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
    val attachmentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    val scrollHandle: ChatScrollHandle,
    val showJumpToBottom: State<Boolean>,
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
    val listBottomPadding: Dp,
    val bottomFadeBandHeight: Dp,
    val snackbarBottomPadding: Dp,
    val showComposerToolbar: Boolean,
    val screenWidthDp: Dp,
    val buttonsAlpha: State<Float>,
    val inputAlpha: State<Float>,
    val sendMessage: () -> Unit,
    val sendCommand: (String) -> Unit,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
private data class ChatDerivedValues(
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
    composerContentHeightPx: MutableState<Int>,
    imeBottomPx: Int,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatScrollAndAnimation {
    // --- Scroll system ---
    val scrollHandle =
        rememberChatScrollState(
            sessionId = sessionId,
            renderedMessages = renderedMessages,
            composerContentHeightPx = composerContentHeightPx.value,
            imeBottomPx = imeBottomPx,
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
        remember(state.messages, state.pendingMessages) {
            state.messages + state.pendingMessages
        }
    val selectedThought =
        remember(renderedMessages, selectedThoughtSegmentId.value) {
            renderedMessages.thoughtForSegment(selectedThoughtSegmentId.value)
        }
    val selectedToolCall =
        remember(renderedMessages, selectedToolCallSegmentId.value) {
            renderedMessages.toolCallForSegment(selectedToolCallSegmentId.value)
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
private fun rememberChatDerived(
    state: ChatState,
    sessionId: String,
    selectedConfigPickerOptionId: MutableState<String?>,
    selectedThoughtSegmentId: MutableState<String?>,
    selectedToolCallSegmentId: MutableState<String?>,
    composerExpanded: MutableState<Boolean>,
    composerContentHeightPx: MutableState<Int>,
    imeBottomPx: Int,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatDerivedValues {
    val configDerived =
        rememberChatConfigDerived(state, selectedConfigPickerOptionId)
    val gitAdditions = state.gitDiffStats?.additions ?: 0
    val gitDeletions = state.gitDiffStats?.deletions ?: 0
    val messages =
        rememberChatMessages(state, selectedThoughtSegmentId, selectedToolCallSegmentId)
    val scrollAndAnimation =
        rememberChatScrollAndAnimation(
            state = state,
            sessionId = sessionId,
            renderedMessages = messages.renderedMessages,
            activelyStreaming = configDerived.activelyStreaming,
            composerExpanded = composerExpanded,
            composerContentHeightPx = composerContentHeightPx,
            imeBottomPx = imeBottomPx,
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

@Composable
private fun rememberChatScreenState(
    viewModel: ChatViewModel,
    sessionId: String,
): ChatScreenState {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val mutations = rememberChatScreenMutations()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // -- Unified attachment picker: any file; images are downscaled + previewed --
    val attachmentPickerLauncher =
        rememberAttachmentPicker(
            state = state,
            selectedImages = mutations.selectedImages,
            selectedFiles = mutations.selectedFiles,
            imageFocusTrigger = mutations.imageFocusTrigger,
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
        )
    val composerInsets =
        rememberComposerInsets(
            state = state,
            composerContentHeightPx = mutations.composerContentHeightPx,
        )

    val derived =
        rememberChatDerived(
            state = state,
            sessionId = sessionId,
            selectedConfigPickerOptionId = mutations.selectedConfigPickerOptionId,
            selectedThoughtSegmentId = mutations.selectedThoughtSegmentId,
            selectedToolCallSegmentId = mutations.selectedToolCallSegmentId,
            composerExpanded = mutations.composerExpanded,
            composerContentHeightPx = mutations.composerContentHeightPx,
            imeBottomPx = composerInsets.imeBottomPx,
            onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
        )
    val sendHandlers =
        rememberSendHandlers(
            viewModel = viewModel,
            state = state,
            messageText = mutations.messageText,
            selectedImages = mutations.selectedImages,
            selectedFiles = mutations.selectedFiles,
            composerExpanded = mutations.composerExpanded,
            focusManager = focusManager,
            scrollHandle = derived.scrollHandle,
        )
    return buildChatScreenState(
        state = state,
        snackbarHostState = snackbarHostState,
        mutations = mutations,
        attachmentPickerLauncher = attachmentPickerLauncher,
        derived = derived,
        insets = composerInsets,
        sendMessage = sendHandlers.first,
        sendCommand = sendHandlers.second,
    )
}

private fun buildChatScreenState(
    state: ChatState,
    snackbarHostState: SnackbarHostState,
    mutations: ChatScreenMutations,
    attachmentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    derived: ChatDerivedValues,
    insets: ComposerInsets,
    sendMessage: () -> Unit,
    sendCommand: (String) -> Unit,
): ChatScreenState =
    ChatScreenState(
        state = state,
        snackbarHostState = snackbarHostState,
        selectedConfigPickerOptionId = mutations.selectedConfigPickerOptionId,
        selectedThoughtSegmentId = mutations.selectedThoughtSegmentId,
        selectedToolCallSegmentId = mutations.selectedToolCallSegmentId,
        showCommandsDialog = mutations.showCommandsDialog,
        showConnectionStatusDialog = mutations.showConnectionStatusDialog,
        showGitStatusSheet = mutations.showGitStatusSheet,
        composerContentHeightPx = mutations.composerContentHeightPx,
        messageText = mutations.messageText,
        selectedImages = mutations.selectedImages,
        selectedFiles = mutations.selectedFiles,
        composerExpanded = mutations.composerExpanded,
        focusRequester = mutations.focusRequester,
        imageFocusTrigger = mutations.imageFocusTrigger,
        attachmentPickerLauncher = attachmentPickerLauncher,
        scrollHandle = derived.scrollHandle,
        showJumpToBottom = derived.showJumpToBottom,
        activeModel = derived.activeModel,
        modeOption = derived.modeOption,
        toolbarConfigOptions = derived.toolbarConfigOptions,
        selectedConfigPickerOption = derived.selectedConfigPickerOption,
        canCancelStreaming = derived.canCancelStreaming,
        hasStreamingBubble = derived.hasStreamingBubble,
        activelyStreaming = derived.activelyStreaming,
        showStopAction = derived.showStopAction,
        showModeButton = derived.showModeButton,
        currentModeLabel = derived.currentModeLabel,
        gitAdditions = derived.gitAdditions,
        gitDeletions = derived.gitDeletions,
        renderedMessages = derived.renderedMessages,
        selectedThought = derived.selectedThought,
        selectedToolCall = derived.selectedToolCall,
        activePermissionRequest = derived.activePermissionRequest,
        renderedLastMessageId = derived.renderedLastMessageId,
        listBottomPadding = insets.listBottomPadding,
        bottomFadeBandHeight = insets.bottomFadeBandHeight,
        snackbarBottomPadding = insets.snackbarBottomPadding,
        showComposerToolbar = insets.showComposerToolbar,
        screenWidthDp = insets.screenWidthDp,
        buttonsAlpha = derived.buttonsAlphaState,
        inputAlpha = derived.inputAlphaState,
        sendMessage = sendMessage,
        sendCommand = sendCommand,
    )

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun ChatScreenScaffold(
    screenState: ChatScreenState,
    sessionId: String,
    sessionTitle: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    with(sharedTransitionScope) {
        Box(
            modifier =
                Modifier
                    .sharedBounds(
                        sharedContentState =
                            rememberSharedContentState(
                                key = SessionSharedBoundsKey(sessionId),
                            ),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    ).fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

            Scaffold(
                modifier =
                    Modifier
                        .fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    ChatScreenTopBar(
                        screenState = screenState,
                        sessionId = sessionId,
                        sessionTitle = sessionTitle,
                        scrollBehavior = scrollBehavior,
                        coroutineScope = coroutineScope,
                        viewModel = viewModel,
                        onNavigateBack = onNavigateBack,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(),
                ) {
                    ChatScreenOverlays(
                        screenState = screenState,
                        innerPadding = innerPadding,
                        coroutineScope = coroutineScope,
                        focusManager = focusManager,
                        viewModel = viewModel,
                        appBarScrollConnection = scrollBehavior.nestedScrollConnection,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.ChatScreenOverlays(
    screenState: ChatScreenState,
    innerPadding: PaddingValues,
    coroutineScope: CoroutineScope,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
    appBarScrollConnection: NestedScrollConnection,
) {
    ChatScreenContentOverlays(
        screenState = screenState,
        innerPadding = innerPadding,
        viewModel = viewModel,
        appBarScrollConnection = appBarScrollConnection,
    )
    ChatScreenSnackbar(screenState)

    if (screenState.showComposerToolbar) {
        ChatComposerHost(
            screenState = screenState,
            coroutineScope = coroutineScope,
            focusManager = focusManager,
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.ChatComposerHost(
    screenState: ChatScreenState,
    coroutineScope: CoroutineScope,
    focusManager: FocusManager,
    viewModel: ChatViewModel,
) {
    val callbacks =
        rememberComposerCallbacks(
            screenState = screenState,
            coroutineScope = coroutineScope,
            viewModel = viewModel,
        )
    ChatComposerBar(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 1.dp)
                .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                .zIndex(1f),
        state = screenState.state,
        toolbarConfigOptions = screenState.toolbarConfigOptions,
        composerExpanded = screenState.composerExpanded.value,
        onComposerExpandedChange = { screenState.composerExpanded.value = it },
        messageText = screenState.messageText.value,
        onMessageTextChange = { screenState.messageText.value = it },
        inputAlpha = screenState.inputAlpha.value,
        buttonsAlpha = screenState.buttonsAlpha.value,
        showModeButton = screenState.showModeButton,
        modeOption = screenState.modeOption,
        currentModeLabel = screenState.currentModeLabel,
        showStopAction = screenState.showStopAction,
        canCancelStreaming = screenState.canCancelStreaming,
        screenWidth = screenState.screenWidthDp,
        focusRequester = screenState.focusRequester,
        onFocusCleared = { focusManager.clearFocus() },
        onHeightChanged = { screenState.composerContentHeightPx.value = it },
        onSend = screenState.sendMessage,
        onCancelStreaming = callbacks.onCancelStreaming,
        onSetStringConfigOption = callbacks.onSetStringConfigOption,
        onSetBooleanConfigOption = callbacks.onSetBooleanConfigOption,
        onShowCommands = callbacks.onShowCommands,
        onShowConfigOptionPicker = callbacks.onShowConfigOptionPicker,
        showJumpToBottom = screenState.showJumpToBottom.value,
        onJumpToBottom = callbacks.onJumpToBottom,
        canSendImages = screenState.state.canSendImages,
        selectedImages = screenState.selectedImages.value,
        onImagesChanged = { screenState.selectedImages.value = it },
        canSendFiles = screenState.state.supportsEmbeddedContext,
        selectedFiles = screenState.selectedFiles.value,
        onFilesChanged = { screenState.selectedFiles.value = it },
        onAttach = { screenState.attachmentPickerLauncher.launch(arrayOf("*/*")) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ChatScreenTopBar(
    screenState: ChatScreenState,
    sessionId: String,
    sessionTitle: String,
    scrollBehavior: TopAppBarScrollBehavior,
    coroutineScope: CoroutineScope,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    ChatTopBar(
        sessionId = sessionId,
        sessionTitle = screenState.state.title ?: sessionTitle,
        cwd = viewModel.cwd,
        activeModel = screenState.activeModel,
        connectionState = screenState.state.connectionState,
        totalTokens = screenState.state.usage?.totalTokens,
        contextWindowTokens = screenState.state.usage?.contextWindowTokens,
        costAmount = screenState.state.usage?.costAmount,
        costCurrency = screenState.state.usage?.costCurrency,
        gitAdditions = screenState.gitAdditions,
        gitDeletions = screenState.gitDeletions,
        gitBranch = screenState.state.gitStatus?.branch,
        gitChangedFiles =
            screenState.state.gitStatus
                ?.changed
                ?.size ?: 0,
        scrollBehavior = scrollBehavior,
        onNavigateBack = onNavigateBack,
        onConnectionStatusClick = { screenState.showConnectionStatusDialog.value = true },
        onGitStatusClick = { screenState.showGitStatusSheet.value = true },
        onGitStatusLongPress = { viewModel.dispatch(ChatIntent.RefreshGitStatus) },
        onTitleClick = {
            coroutineScope.launch {
                screenState.scrollHandle.jumpToTop()
                scrollBehavior.state.heightOffset = 0f
            }
        },
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
    )
}

@Composable
private fun BoxScope.ChatScreenContentOverlays(
    screenState: ChatScreenState,
    innerPadding: PaddingValues,
    viewModel: ChatViewModel,
    appBarScrollConnection: NestedScrollConnection,
) {
    ChatScreenDialogsHost(screenState, viewModel)
    if (screenState.showGitStatusSheet.value) {
        screenState.state.gitStatus?.let { status ->
            GitStatusSheet(
                status = status,
                gitFileDiff = screenState.state.gitFileDiff,
                gitFileDiffPath = screenState.state.gitFileDiffPath,
                isGitFileDiffLoading = screenState.state.isGitFileDiffLoading,
                gitFileDiffError = screenState.state.gitFileDiffError,
                onLoadGitDiff = { path ->
                    viewModel.dispatch(ChatIntent.LoadGitDiff(path))
                },
                onDismiss = { screenState.showGitStatusSheet.value = false },
            )
        }
    }

    ChatScreenBody(
        state = screenState.state,
        listState = screenState.scrollHandle.listState,
        userScrollDetector = screenState.scrollHandle.userScrollDetector,
        appBarScrollConnection = appBarScrollConnection,
        renderedLastMessageId = screenState.renderedLastMessageId,
        listTopPadding = innerPadding.calculateTopPadding(),
        listBottomPadding = screenState.listBottomPadding,
        onRetryLoad = { viewModel.dispatch(ChatIntent.RetryLoad) },
        onThoughtClick = { segmentId ->
            screenState.selectedThoughtSegmentId.value = segmentId
        },
        onToolCallClick = { segmentId ->
            screenState.selectedToolCallSegmentId.value = segmentId
        },
        onStreamLayoutSettled = screenState.scrollHandle.onStreamLayoutSettled,
        onRetryMessage = { clientId ->
            viewModel.dispatch(ChatIntent.RetryMessage(clientId))
        },
    )

    ChatBottomFade(bandHeight = screenState.bottomFadeBandHeight)

    ChatScreenSnackbar(screenState)
}

/**
 * Mirrors the top bar at the bottom of the chat: content going under the
 * composer is dimmed into the surface instead of being cut off by it.
 *
 * The band starts just above the composer's top edge and runs to the bottom of
 * the screen, so content is only dimmed once it reaches the composer — and, at
 * rest, the last message ends below the band's transparent edge and stays
 * crisp.
 *
 * Unlike the top bar, the band never reaches the surface colour: it bottoms out
 * at [BOTTOM_FADE_MAX_ALPHA], leaving what has scrolled behind the composer
 * faintly readable rather than dissolving it away.
 */
@Composable
private fun BoxScope.ChatBottomFade(bandHeight: Dp) {
    if (bandHeight <= 0.dp) return
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bandHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to surface.copy(alpha = BOTTOM_FADE_MAX_ALPHA),
                    ),
                ),
    )
}

@Composable
private fun BoxScope.ChatScreenSnackbar(screenState: ChatScreenState) {
    SnackbarHost(
        hostState = screenState.snackbarHostState,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = screenState.snackbarBottomPadding,
                ).zIndex(2f),
    )
}

@Composable
private fun ChatScreenDialogsHost(
    screenState: ChatScreenState,
    viewModel: ChatViewModel,
) {
    ChatScreenDialogs(
        selectedConfigPickerOption = screenState.selectedConfigPickerOption,
        onConfigOptionSelected = { optionId, value ->
            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = optionId,
                    value = ChatConfigValue.StringValue(value),
                ),
            )
        },
        onDismissConfigPicker = { screenState.selectedConfigPickerOptionId.value = null },
        selectedThought = screenState.selectedThought,
        onDismissThought = { screenState.selectedThoughtSegmentId.value = null },
        selectedToolCall = screenState.selectedToolCall,
        onDismissToolCall = { screenState.selectedToolCallSegmentId.value = null },
        activePermissionRequest = screenState.activePermissionRequest,
        onPermissionGrant = { toolCallId, optionId ->
            viewModel.dispatch(
                ChatIntent.GrantPermission(toolCallId, optionId),
            )
        },
        onPermissionDeny = { toolCallId ->
            viewModel.dispatch(ChatIntent.DenyPermission(toolCallId))
        },
        showConnectionStatusDialog = screenState.showConnectionStatusDialog.value,
        connectionState = screenState.state.connectionState,
        diagnostics = screenState.state.connectionDiagnostics,
        usage = screenState.state.usage,
        onDismissConnectionStatus = { screenState.showConnectionStatusDialog.value = false },
        showCommandsDialog = screenState.showCommandsDialog.value,
        commands = screenState.state.availableCommands,
        serverId = screenState.state.serverId,
        recentSelectionStore = viewModel.recentSelectionStore,
        onDismissCommands = { screenState.showCommandsDialog.value = false },
        onCommandClick = screenState.sendCommand,
    )
}

private data class ComposerCallbacks(
    val onCancelStreaming: () -> Unit,
    val onSetStringConfigOption: (String, String) -> Unit,
    val onSetBooleanConfigOption: (String, Boolean) -> Unit,
    val onShowCommands: () -> Unit,
    val onShowConfigOptionPicker: (String) -> Unit,
    val onJumpToBottom: () -> Unit,
)

private fun rememberComposerCallbacks(
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
        onShowCommands = { screenState.showCommandsDialog.value = true },
        onShowConfigOptionPicker = { optionId ->
            screenState.selectedConfigPickerOptionId.value = optionId
        },
        onJumpToBottom = {
            coroutineScope.launch { screenState.scrollHandle.jumpToBottom() }
        },
    )

// endregion
