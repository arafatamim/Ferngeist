package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.core.common.ui.SessionSharedBoundsKey
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedConfigPickerOptionId by remember { mutableStateOf<String?>(null) }
    var selectedThoughtSegmentId by remember { mutableStateOf<String?>(null) }
    var selectedToolCallSegmentId by remember { mutableStateOf<String?>(null) }
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var composerContentHeightPx by remember { mutableIntStateOf(0) }
    var messageText by remember { mutableStateOf("") }
    var composerExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val systemBottomInsetPx = if (imeBottomPx > navBottomPx) imeBottomPx else navBottomPx

    // Composer is hidden during initial loading or when error + empty state
    val showComposerToolbar =
        !state.isLoading && !(state.error != null && state.messages.isEmpty())
    val composerContentHeightDp = with(density) { composerContentHeightPx.toDp() }
    val screenWidthDp =
        with(density) {
            LocalWindowInfo.current.containerSize.width.toDp()
        }
    val systemBottomInsetDp = with(density) { systemBottomInsetPx.toDp() }

    // Bottom padding for message list: composer height + system insets + floating offset + 36dp
    val listBottomPadding =
        if (!showComposerToolbar) {
            0.dp
        } else {
            composerContentHeightDp +
                systemBottomInsetDp +
                FloatingToolbarDefaults.ScreenOffset +
                36.dp
        }

    // Snackbar also needs to sit above the composer bar but with less extra padding (16dp)
    val snackbarBottomPadding =
        if (!showComposerToolbar) {
            0.dp
        } else {
            composerContentHeightDp +
                systemBottomInsetDp +
                FloatingToolbarDefaults.ScreenOffset +
                16.dp
        }

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
        remember(state.configOptions, selectedConfigPickerOptionId) {
            val optionId = selectedConfigPickerOptionId ?: return@remember null
            state.configOptions.firstOrNull { it.id == optionId }
                as? ChatConfigOption.Select
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

    // --- Messages & selections ---
    val renderedMessages = state.messages
    val selectedThought =
        remember(renderedMessages, selectedThoughtSegmentId) {
            renderedMessages.thoughtForSegment(selectedThoughtSegmentId)
        }
    val selectedToolCall =
        remember(renderedMessages, selectedToolCallSegmentId) {
            renderedMessages.toolCallForSegment(selectedToolCallSegmentId)
        }
    val activePermissionRequest =
        remember(renderedMessages) {
            renderedMessages.latestPendingPermissionRequest()
        }
    val renderedLastMessageId = renderedMessages.lastOrNull()?.id

    // --- Scroll system ---
    val scrollHandle =
        rememberChatScrollState(
            sessionId = sessionId,
            renderedMessages = renderedMessages,
            composerContentHeightPx = composerContentHeightPx,
            imeBottomPx = imeBottomPx,
            activelyStreaming = activelyStreaming,
            restoredScrollSnapshot = state.restoredScrollSnapshot,
            restoreReady = renderedMessages.isNotEmpty() && !state.isLoading,
            onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
        )

    // --- Composer spring animations ---
    val fadeSpring =
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    val buttonsAlpha by
        animateFloatAsState(
            targetValue = if (composerExpanded) 0f else 1f,
            animationSpec = fadeSpring,
            label = "ButtonsAlpha",
        )
    val inputAlpha by
        animateFloatAsState(
            targetValue = if (composerExpanded) 1f else 0f,
            animationSpec = fadeSpring,
            label = "InputAlpha",
        )

    // --- Send message (composer) ---
    // Only send when the session bridge is active. If it isn't (e.g. disconnected),
    // leave the text in the composer instead of dispatching a send that would fail.
    val sendMessage: () -> Unit = {
        if (messageText.isNotBlank() && state.isSessionReady) {
            viewModel.dispatch(ChatIntent.SendMessage(messageText))
            scrollHandle.onSendMessage()
            messageText = ""
            composerExpanded = false
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

    // --- ViewModel effects ---
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

    // Auto-focus the text field when the composer expands
    LaunchedEffect(composerExpanded) {
        if (composerExpanded) {
            focusRequester.requestFocus()
        }
    }

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
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = Color.Transparent,
                topBar = {
                    ChatTopBar(
                        sessionId = sessionId,
                        sessionTitle = state.title ?: sessionTitle,
                        activeModel = activeModel,
                        connectionState = state.connectionState,
                        totalTokens = state.usage?.totalTokens,
                        contextWindowTokens = state.usage?.contextWindowTokens,
                        costAmount = state.usage?.costAmount,
                        costCurrency = state.usage?.costCurrency,
                        scrollBehavior = scrollBehavior,
                        onNavigateBack = onNavigateBack,
                        onConnectionStatusClick = { showConnectionStatusDialog = true },
                        onTitleClick = {
                            coroutineScope.launch {
                                scrollHandle.jumpToTop()
                                scrollBehavior.state.heightOffset = 0f
                            }
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    ChatScreenDialogs(
                        selectedConfigPickerOption = selectedConfigPickerOption,
                        onConfigOptionSelected = { optionId, value ->
                            viewModel.dispatch(
                                ChatIntent.SetConfigOption(
                                    optionId = optionId,
                                    value = ChatConfigValue.StringValue(value),
                                ),
                            )
                        },
                        onDismissConfigPicker = { selectedConfigPickerOptionId = null },
                        selectedThought = selectedThought,
                        onDismissThought = { selectedThoughtSegmentId = null },
                        selectedToolCall = selectedToolCall,
                        onDismissToolCall = { selectedToolCallSegmentId = null },
                        activePermissionRequest = activePermissionRequest,
                        onPermissionGrant = { toolCallId, optionId ->
                            viewModel.dispatch(
                                ChatIntent.GrantPermission(toolCallId, optionId),
                            )
                        },
                        onPermissionDeny = { toolCallId ->
                            viewModel.dispatch(ChatIntent.DenyPermission(toolCallId))
                        },
                        showConnectionStatusDialog = showConnectionStatusDialog,
                        connectionState = state.connectionState,
                        diagnostics = state.connectionDiagnostics,
                        usage = state.usage,
                        onDismissConnectionStatus = { showConnectionStatusDialog = false },
                        showCommandsDialog = showCommandsDialog,
                        commands = state.availableCommands,
                        serverId = state.serverId,
                        recentSelectionStore = viewModel.recentSelectionStore,
                        onDismissCommands = { showCommandsDialog = false },
                        onCommandClick = sendCommand,
                    )

                    ChatScreenBody(
                        state = state,
                        listState = scrollHandle.listState,
                        userScrollDetector = scrollHandle.userScrollDetector,
                        renderedLastMessageId = renderedLastMessageId,
                        listTopPadding = innerPadding.calculateTopPadding(),
                        listBottomPadding = listBottomPadding,
                        onRetryLoad = { viewModel.dispatch(ChatIntent.RetryLoad) },
                        onThoughtClick = { segmentId ->
                            selectedThoughtSegmentId = segmentId
                        },
                        onToolCallClick = { segmentId ->
                            selectedToolCallSegmentId = segmentId
                        },
                        onStreamLayoutSettled = scrollHandle.onStreamLayoutSettled,
                    )

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = snackbarBottomPadding,
                                ).zIndex(2f),
                    )

                    if (showComposerToolbar) {
                        ChatComposerBar(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .imePadding()
                                    .padding(horizontal = 1.dp)
                                    .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                                    .zIndex(1f),
                            state = state,
                            toolbarConfigOptions = toolbarConfigOptions,
                            composerExpanded = composerExpanded,
                            onComposerExpandedChange = { composerExpanded = it },
                            messageText = messageText,
                            onMessageTextChange = { messageText = it },
                            inputAlpha = inputAlpha,
                            buttonsAlpha = buttonsAlpha,
                            showModeButton = showModeButton,
                            modeOption = modeOption,
                            currentModeLabel = currentModeLabel,
                            showStopAction = showStopAction,
                            canCancelStreaming = canCancelStreaming,
                            screenWidth = screenWidthDp,
                            focusRequester = focusRequester,
                            onFocusCleared = { focusManager.clearFocus() },
                            onHeightChanged = { composerContentHeightPx = it },
                            onSend = sendMessage,
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
                            onShowCommands = { showCommandsDialog = true },
                            onShowConfigOptionPicker = { optionId ->
                                selectedConfigPickerOptionId = optionId
                            },
                        )
                    }
                }
            }
        }
    }
}
// endregion
