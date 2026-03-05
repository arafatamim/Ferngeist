package com.tamimarafat.ferngeist.feature.chat.ui

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.connectionStateLabel
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.UsageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

private enum class AutoScrollMode {
    FOLLOWING,
    PAUSED_BY_USER,
}

private const val INITIAL_FOLLOW_SETTLE_MS = 420L
private const val COMPOSER_FOLLOW_SETTLE_MS = 120L
private const val STREAM_FOLLOW_TICK_MS = 96L
private const val USER_SCROLL_SIGNAL_WINDOW_MS = 220L
private const val USER_RESUME_IDLE_MS = 320L
private const val MAX_SCROLL_BY_PX = 1200
private const val FOLLOW_TOLERANCE_PX = 24
private const val RESUME_TOLERANCE_PX = 48
private const val FOLLOW_CORRECTION_PASSES = 4
private const val FOLLOW_CORRECTION_DELAY_MS = 16L
private const val SEND_FOLLOW_PASSES = 3
private const val SEND_FOLLOW_DELAY_MS = 32L

private data class ContentAnchor(
    val messageCount: Int,
    val lastMessageId: String?,
    val lastMessageContentLength: Int,
    val lastMessageSegmentCount: Int,
    val lastToolOutputLength: Int,
    val isStreaming: Boolean,
)

private class ChatScrollState(
    val listState: LazyListState,
) {
    private var autoScrollMode by mutableStateOf(AutoScrollMode.FOLLOWING)
    private var programmaticScrollDepth by mutableStateOf(0)
    private var lastUserScrollUptimeMs by mutableStateOf(0L)
    private var initialFollowSettled by mutableStateOf(false)

    val isFollowing: Boolean
        get() = autoScrollMode == AutoScrollMode.FOLLOWING

    val userScrollDetector: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 0.5f) {
                lastUserScrollUptimeMs = SystemClock.uptimeMillis()
                if (programmaticScrollDepth == 0) {
                    autoScrollMode = AutoScrollMode.PAUSED_BY_USER
                }
            }
            return Offset.Zero
        }
    }

    fun resumeFollowing() {
        autoScrollMode = AutoScrollMode.FOLLOWING
    }

    suspend fun scrollToBottomForSend() {
        repeat(SEND_FOLLOW_PASSES) {
            scrollToBottom(smooth = true)
            delay(SEND_FOLLOW_DELAY_MS)
        }
    }

    suspend fun observeIdleResume() {
        snapshotFlow { listState.isScrollInProgress to (programmaticScrollDepth > 0) }
            .distinctUntilChanged()
            .collect { (inProgress, isProgrammaticScroll) ->
                val now = SystemClock.uptimeMillis()
                val recentlyUserScrolled = (now - lastUserScrollUptimeMs) <= USER_SCROLL_SIGNAL_WINDOW_MS

                if (!inProgress &&
                    !isProgrammaticScroll &&
                    autoScrollMode == AutoScrollMode.PAUSED_BY_USER &&
                    !recentlyUserScrolled &&
                    (now - lastUserScrollUptimeMs) >= USER_RESUME_IDLE_MS &&
                    listState.isAtBottom(RESUME_TOLERANCE_PX)
                ) {
                    autoScrollMode = AutoScrollMode.FOLLOWING
                }
            }
    }

    suspend fun observeManualBottomResume(activelyStreaming: Boolean) {
        snapshotFlow { listState.isAtBottom(RESUME_TOLERANCE_PX) }
            .distinctUntilChanged()
            .collect { isBottom ->
                if (isBottom &&
                    activelyStreaming &&
                    autoScrollMode == AutoScrollMode.PAUSED_BY_USER
                ) {
                    autoScrollMode = AutoScrollMode.FOLLOWING
                    scrollToBottom()
                }
            }
    }

    suspend fun onContentAnchorChanged(contentAnchor: ContentAnchor) {
        if (!isFollowing || contentAnchor.messageCount == 0) return
        if (!initialFollowSettled) {
            initialFollowSettled = true
            delay(INITIAL_FOLLOW_SETTLE_MS)
        }
        scrollToBottom()
    }

    suspend fun onComposerInsetsChanged(messageCount: Int) {
        if (!isFollowing || messageCount == 0) return
        delay(COMPOSER_FOLLOW_SETTLE_MS)
        scrollToBottom(smooth = true)
    }

    suspend fun followWhileStreaming(activelyStreaming: Boolean) {
        if (!activelyStreaming || !isFollowing) return
        while (true) {
            scrollToBottom()
            delay(STREAM_FOLLOW_TICK_MS)
        }
    }

    suspend fun scrollToBottom(smooth: Boolean = false) {
        if (!isFollowing) return
        programmaticScrollDepth += 1
        try {
            var pass = 0
            while (pass < FOLLOW_CORRECTION_PASSES) {
                val info = listState.layoutInfo
                val lastIndex = info.totalItemsCount - 1
                if (lastIndex < 0) break

                val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
                if (lastVisible == null) {
                    if (smooth) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
                } else {
                    val overflow = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                    if (overflow <= FOLLOW_TOLERANCE_PX) {
                        break
                    }
                    val delta = overflow.coerceAtMost(MAX_SCROLL_BY_PX)
                    if (smooth) listState.animateScrollBy(delta.toFloat()) else listState.scrollBy(delta.toFloat())
                }

                pass += 1
                if (pass < FOLLOW_CORRECTION_PASSES) {
                    delay(FOLLOW_CORRECTION_DELAY_MS)
                }
            }

            val finalInfo = listState.layoutInfo
            val finalLastIndex = finalInfo.totalItemsCount - 1
            if (finalLastIndex >= 0 && !listState.isAtBottom(FOLLOW_TOLERANCE_PX)) {
                val finalLastVisible = finalInfo.visibleItemsInfo.lastOrNull { it.index == finalLastIndex }
                if (finalLastVisible == null) {
                    if (smooth) listState.animateScrollToItem(finalLastIndex) else listState.scrollToItem(finalLastIndex)
                } else {
                    val overflow = (finalLastVisible.offset + finalLastVisible.size) - finalInfo.viewportEndOffset
                    if (overflow > FOLLOW_TOLERANCE_PX) {
                        if (smooth) listState.animateScrollBy(overflow.toFloat()) else listState.scrollBy(overflow.toFloat())
                    }
                }
            }
        } finally {
            programmaticScrollDepth = (programmaticScrollDepth - 1).coerceAtLeast(0)
        }
    }
}

@Composable
private fun rememberChatScrollState(
    contentAnchor: ContentAnchor,
    composerContentHeightPx: Int,
    imeBottomPx: Int,
    activelyStreaming: Boolean,
    renderedLastMessageId: String?,
): ChatScrollState {
    val listState = rememberLazyListState()
    val chatScrollState = remember(listState) {
        ChatScrollState(listState = listState)
    }

    LaunchedEffect(chatScrollState) {
        chatScrollState.observeIdleResume()
    }

    LaunchedEffect(contentAnchor, chatScrollState.isFollowing) {
        chatScrollState.onContentAnchorChanged(contentAnchor)
    }

    LaunchedEffect(
        composerContentHeightPx,
        imeBottomPx,
        contentAnchor.messageCount,
        chatScrollState.isFollowing,
    ) {
        chatScrollState.onComposerInsetsChanged(contentAnchor.messageCount)
    }

    LaunchedEffect(activelyStreaming, chatScrollState.isFollowing, renderedLastMessageId) {
        chatScrollState.followWhileStreaming(activelyStreaming)
    }

    LaunchedEffect(chatScrollState, activelyStreaming) {
        chatScrollState.observeManualBottomResume(activelyStreaming)
    }

    return chatScrollState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    sessionTitle: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }
    var showModeMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var composerContentHeightPx by remember { mutableStateOf(0) }
    var messageText by remember { mutableStateOf("") }
    var composerExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val systemBottomInsetPx = if (imeBottomPx > navBottomPx) imeBottomPx else navBottomPx
    val showComposerToolbar = !state.isLoading && !(state.error != null && state.messages.isEmpty())
    val composerContentHeightDp = with(density) { composerContentHeightPx.toDp() }
    val systemBottomInsetDp = with(density) { systemBottomInsetPx.toDp() }
    val listBottomPadding = with(density) {
        if (!showComposerToolbar) {
            0.dp
        } else {
            composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    36.dp
        }
    }
    val snackbarBottomPadding = with(density) {
        if (!showComposerToolbar) {
            0.dp
        } else {
            composerContentHeightDp +
                    systemBottomInsetDp +
                    FloatingToolbarDefaults.ScreenOffset +
                    16.dp
        }
    }
    val modelOption = remember(state.configOptions) {
        state.configOptions.firstOrNull { it.id == "model" && it.options.isNotEmpty() }
    }
    val activeModel = remember(state.configOptions) {
        val currentModelOption = state.configOptions.firstOrNull { it.id == "model" }
        currentModelOption?.let { option ->
            option.options.firstOrNull { it.value == option.currentValue }?.label
                ?: option.currentValue
        }
    }
    val canCancelStreaming = state.canCancelStreaming
    val hasStreamingBubble = state.messages.lastOrNull()?.isStreaming == true
    val activelyStreaming = state.isStreaming || hasStreamingBubble
    val showStopAction = state.isStreaming && hasStreamingBubble
    val hasToolbarOptions = true
    val showModeButton = state.availableModes.isNotEmpty() || !state.currentModeId.isNullOrBlank()
    val currentModeLabel = remember(state.availableModes, state.currentModeId) {
        state.availableModes.firstOrNull { it.id == state.currentModeId }?.name?.uppercase(Locale.getDefault())
            ?: state.currentModeId?.uppercase(Locale.getDefault())
            ?: "MODE"
    }
    val renderedMessages = state.messages
    val markdownStates = state.markdownStates
    val renderedLastMessageId = renderedMessages.lastOrNull()?.id
    val contentAnchor = remember(renderedMessages, state.isStreaming) {
        val last = renderedMessages.lastOrNull()
        val lastToolOutputLength = last?.segments
            ?.lastOrNull { it.toolCall != null }
            ?.toolCall
            ?.output
            ?.length ?: 0
        ContentAnchor(
            messageCount = renderedMessages.size,
            lastMessageId = last?.id,
            lastMessageContentLength = last?.content?.length ?: 0,
            lastMessageSegmentCount = last?.segments?.size ?: 0,
            lastToolOutputLength = lastToolOutputLength,
            isStreaming = state.isStreaming,
        )
    }
    val scrollState = rememberChatScrollState(
        contentAnchor = contentAnchor,
        composerContentHeightPx = composerContentHeightPx,
        imeBottomPx = imeBottomPx,
        activelyStreaming = activelyStreaming,
        renderedLastMessageId = renderedLastMessageId,
    )
    val listState = scrollState.listState

    // --- Composer spring animations ---
    val fadeSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val buttonsAlpha by animateFloatAsState(
        targetValue = if (composerExpanded) 0f else 1f,
        animationSpec = fadeSpring,
        label = "ButtonsAlpha"
    )
    val inputAlpha by animateFloatAsState(
        targetValue = if (composerExpanded) 1f else 0f,
        animationSpec = fadeSpring,
        label = "InputAlpha"
    )

    val sendMessage: () -> Unit = {
        if (messageText.isNotBlank()) {
            viewModel.dispatch(ChatIntent.SendMessage(messageText))
            scrollState.resumeFollowing()
            coroutineScope.launch {
                scrollState.scrollToBottomForSend()
            }
            messageText = ""
            composerExpanded = false
            focusManager.clearFocus()
        }
    }

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

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    // Auto-focus the text field when the composer expands.
    LaunchedEffect(composerExpanded) {
        if (composerExpanded) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(topBar = {
        ChatTopBar(
            sessionTitle = sessionTitle,
            activeModel = activeModel,
            usage = state.usage,
            connectionState = state.connectionState,
            onNavigateBack = onNavigateBack,
            onConnectionStatusClick = { showConnectionStatusDialog = true }
        )
    }) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(
                    if (composerExpanded) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            if (messageText.isBlank()) {
                                composerExpanded = false
                                focusManager.clearFocus()
                            }
                        }
                    } else Modifier
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showModelPicker) {
                    ModelPicker(modelOption = modelOption, onModelSelected = { value ->
                        viewModel.dispatch(ChatIntent.SetConfigOption("model", value))
                        showModelPicker = false
                    }, onDismiss = { showModelPicker = false })
                }

                if (showConnectionStatusDialog) {
                    ConnectionDiagnosticsDialog(
                        connectionState = state.connectionState,
                        diagnostics = state.connectionDiagnostics,
                        totalTokens = state.usage?.totalTokens ?: state.connectionDiagnostics.lastTotalTokens,
                        contextWindowTokens = state.usage?.contextWindowTokens
                            ?: state.connectionDiagnostics.lastContextWindowTokens,
                        costAmount = state.usage?.costUsd ?: state.connectionDiagnostics.lastCostAmount,
                        costCurrency = if (state.usage?.costUsd != null) {
                            "USD"
                        } else {
                            state.connectionDiagnostics.lastCostCurrency
                        },
                        onDismiss = { showConnectionStatusDialog = false }
                    )
                }

                if (showCommandsDialog) {
                    CommandsDialog(
                        commands = state.availableCommands,
                        onDismiss = { showCommandsDialog = false },
                        onCommandClick = { command ->
                            showCommandsDialog = false
                            val normalized = command.trim()
                            val slashCommand = if (normalized.startsWith("/")) normalized else "/$normalized"
                            viewModel.dispatch(ChatIntent.SendMessage(slashCommand))
                        }
                    )
                }

                if (state.isLoading && state.messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(64.dp)
                        )
                    }
                } else if (state.error != null && state.messages.isEmpty()) {
                    val errorMessage = state.error ?: "Failed to load session."
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { viewModel.dispatch(ChatIntent.RetryLoad) }) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollState.userScrollDetector)
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp, top = 8.dp, end = 16.dp, bottom = 0.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = renderedMessages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                markdownStates = markdownStates,
                                showStreamingIndicator = state.isStreaming && message.id == renderedLastMessageId,
                                expandedToolCalls = state.expandedToolCalls,
                                onToolCallToggle = { toolCallId ->
                                    viewModel.dispatch(ChatIntent.ToggleToolCallExpansion(toolCallId))
                                },
                                onPermissionGrant = { toolCallId, optionId ->
                                    viewModel.dispatch(
                                        ChatIntent.GrantPermission(
                                            toolCallId,
                                            optionId
                                        )
                                    )
                                },
                                onPermissionDeny = { toolCallId ->
                                    viewModel.dispatch(ChatIntent.DenyPermission(toolCallId))
                                })
                        }
                        item(key = "__chat_bottom_spacer") {
                            Spacer(modifier = Modifier.height(listBottomPadding))
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = snackbarBottomPadding)
                    .zIndex(2f)
            )

            if (showComposerToolbar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 1.dp)
                        .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                        .zIndex(1f)
                ) {
                    val animatedHeight by animateDpAsState(
                        targetValue = if (composerExpanded) 128.dp else 48.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "ComposerHeight"
                    )
                    val expandedToolbarWidth = configuration.screenWidthDp.dp * 0.97f
                    val collapsedMaxToolbarWidth = configuration.screenWidthDp.dp * 0.92f

                    Surface(
                        shape = if (composerExpanded) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .height(animatedHeight)
                            .widthIn(max = if (composerExpanded) expandedToolbarWidth else collapsedMaxToolbarWidth)
                            .onSizeChanged { composerContentHeightPx = it.height }
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
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .padding(horizontal = 4.dp),
                            verticalAlignment = if (composerExpanded) Alignment.Bottom else Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (composerExpanded) {
                                // --- Expanded: top text field + bottom action row ---
                                Column(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)
                                        .alpha(inputAlpha)
                                ) {
                                    val selectionColors = TextSelectionColors(
                                        handleColor = MaterialTheme.colorScheme.onPrimary,
                                        backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f)
                                    )
                                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                                        BasicTextField(
                                            value = messageText,
                                            onValueChange = { messageText = it },
                                            singleLine = false,
                                            minLines = 3,
                                            maxLines = 8,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .focusRequester(focusRequester),
                                            decorationBox = { innerTextField ->
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                                    contentAlignment = Alignment.TopStart
                                                ) {
                                                    if (messageText.isEmpty()) {
                                                        Text(
                                                            text = "Type a message\u2026",
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        IconButton(
                                            onClick = {
                                                composerExpanded = false
                                                focusManager.clearFocus()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close composer"
                                            )
                                        }

                                        FilledIconButton(
                                            onClick = {
                                                if (showStopAction && canCancelStreaming) {
                                                    viewModel.dispatch(ChatIntent.CancelStreaming)
                                                } else if (!showStopAction) {
                                                    sendMessage()
                                                }
                                            },
                                            enabled = if (showStopAction) canCancelStreaming else messageText.isNotBlank(),
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.onPrimary,
                                                contentColor = MaterialTheme.colorScheme.primary
                                            ),
                                            shapes = IconButtonDefaults.shapes()
                                        ) {
                                            Icon(
                                                imageVector = if (showStopAction && canCancelStreaming) {
                                                    Icons.Default.Stop
                                                } else {
                                                    Icons.Default.ArrowUpward
                                                },
                                                contentDescription = if (showStopAction && canCancelStreaming) "Stop" else "Send"
                                            )
                                        }
                                    }
                                }
                            } else {
                                // --- Collapsed: mode + chat + model buttons ---
                                if (showModeButton) {
                                    Box(modifier = Modifier.alpha(buttonsAlpha)) {
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                                TooltipAnchorPosition.Above
                                            ),
                                            tooltip = { PlainTooltip { Text("Mode") } },
                                            state = rememberTooltipState()
                                        ) {
                                            Button(
                                                onClick = { showModeMenu = true },
                                                modifier = Modifier.widthIn(max = collapsedMaxToolbarWidth * 0.45f)
                                            ) {
                                                Text(
                                                    text = currentModeLabel,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        DropdownMenuPopup(
                                            expanded = showModeMenu,
                                            onDismissRequest = { showModeMenu = false }
                                        ) {
                                            DropdownMenuGroup(
                                                shapes = MenuDefaults.groupShape(0, 1),
                                                interactionSource = modeMenuInteractionSource,
                                            ) {
                                                val modeCount = state.availableModes.size
                                                if (modeCount == 0) {
                                                    DropdownMenuItem(
                                                        text = { Text("No modes available") },
                                                        onClick = { showModeMenu = false },
                                                        enabled = false
                                                    )
                                                } else {
                                                    state.availableModes.forEachIndexed { index, mode ->
                                                        DropdownMenuItem(
                                                            text = { Text(mode.name.uppercase()) },
                                                            shapes = MenuDefaults.itemShape(index, modeCount),
                                                            checked = mode.id == state.currentModeId,
                                                            onCheckedChange = { checked ->
                                                                if (checked && mode.id != state.currentModeId) {
                                                                    showModeMenu = false
                                                                    viewModel.dispatch(ChatIntent.SetMode(mode.id))
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                        TooltipAnchorPosition.Above
                                    ),
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
                                    state = rememberTooltipState()
                                ) {
                                    FilledIconButton(
                                        onClick = {
                                            if (showStopAction && canCancelStreaming) {
                                                viewModel.dispatch(ChatIntent.CancelStreaming)
                                            } else if (!showStopAction) {
                                                composerExpanded = true
                                            }
                                        },
                                        enabled = !showStopAction || canCancelStreaming,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onPrimary,
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shapes = IconButtonDefaults.shapes()
                                    ) {
                                        Icon(
                                            imageVector = if (showStopAction && canCancelStreaming) {
                                                Icons.Default.Stop
                                            } else {
                                                Icons.Default.Edit
                                            },
                                            contentDescription = if (showStopAction && canCancelStreaming) "Stop" else "Chat"
                                        )
                                    }
                                }

                                if (hasToolbarOptions) {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                            TooltipAnchorPosition.Above
                                        ),
                                        tooltip = { PlainTooltip { Text("Options") } },
                                        state = rememberTooltipState()
                                    ) {
                                        Box {
                                            IconButton(
                                                onClick = { showModelMenu = true },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Model"
                                                )
                                            }
                                            DropdownMenuPopup(
                                                expanded = showModelMenu,
                                                onDismissRequest = { showModelMenu = false }
                                            ) {
                                                DropdownMenuGroup(
                                                    shapes = MenuDefaults.groupShape(0, 1),
                                                    interactionSource = optionsMenuInteractionSource,
                                                ) {
                                                    if (state.commandsAdvertised) {
                                                        DropdownMenuItem(
                                                            text = { Text("Commands") },
                                                            onClick = {
                                                                showModelMenu = false
                                                                showCommandsDialog = true
                                                            }
                                                        )
                                                    }

                                                    if (modelOption != null) {
                                                        DropdownMenuItem(
                                                            text = { Text("Change model") },
                                                            onClick = {
                                                                showModelMenu = false
                                                                showModelPicker = true
                                                            }
                                                        )
                                                    } else if (!state.commandsAdvertised) {
                                                        DropdownMenuItem(
                                                            text = { Text("No options available") },
                                                            onClick = { showModelMenu = false },
                                                            enabled = false
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatTopBar(
    sessionTitle: String,
    activeModel: String?,
    usage: UsageState?,
    connectionState: AcpConnectionState,
    onNavigateBack: () -> Unit,
    onConnectionStatusClick: () -> Unit,
) {
    TopAppBar(title = {
        Column {
            Text(
                text = sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            activeModel?.takeIf { it.isNotBlank() }?.let { model ->
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }, navigationIcon = {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back"
            )
        }
    }, actions = {
        val connectionLabel = connectionStateLabel(connectionState)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text("Connection: $connectionLabel") } },
            state = rememberTooltipState()
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Connection status"
                        stateDescription = connectionLabel
                    }
                    .clickable(onClick = onConnectionStatusClick),
                contentAlignment = Alignment.Center
            ) {
                when (connectionState) {
                    is AcpConnectionState.Connecting -> {
                        LoadingIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    is AcpConnectionState.Connected -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }

                    is AcpConnectionState.Failed -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }

                    is AcpConnectionState.Disconnected -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(12.dp)
                        ) {}
                    }
                }
            }
        }

    })
}

@Composable
private fun CommandsDialog(
    commands: List<String>,
    onDismiss: () -> Unit,
    onCommandClick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commands") },
        text = {
            if (commands.isEmpty()) {
                Text(
                    text = "No commands advertised by the server.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    commands.forEach { command ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCommandClick(command) }
                        ) {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun LazyListState.isAtBottom(tolerancePx: Int = 2): Boolean {
    val layoutInfo = this.layoutInfo
    val total = layoutInfo.totalItemsCount
    if (total == 0) return true
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index != total - 1) return false
    val itemBottom = lastVisible.offset + lastVisible.size
    return itemBottom <= layoutInfo.viewportEndOffset + tolerancePx
}

@Composable
private fun ModelPicker(
    modelOption: SessionConfigOption?,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(modelOption) { mutableStateOf("") }
    val filteredOptions = remember(modelOption, query) {
        val options = modelOption?.options.orEmpty()
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            options
        } else {
            options.filter { choice ->
                choice.label.contains(trimmedQuery, ignoreCase = true) || choice.value.contains(
                    trimmedQuery, ignoreCase = true
                ) || (choice.description?.contains(trimmedQuery, ignoreCase = true) == true)
            }
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select Model") }, text = {
        if (modelOption == null || modelOption.options.isEmpty()) {
            Text(
                text = "No models available from agent.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search models") },
                    placeholder = { Text("Type model name") })

                if (filteredOptions.isEmpty()) {
                    Text(
                        text = "No matching models.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredOptions, key = { it.id }) { choice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelSelected(choice.value) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = choice.value == modelOption.currentValue,
                                    onClick = { onModelSelected(choice.value) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = choice.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    choice.description?.let { description ->
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = onDismiss) {
            Text("Close")
        }
    })
}
