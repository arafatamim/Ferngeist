package com.tamimarafat.ferngeist.feature.chat.ui

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.activity.ActiveElsewhereDialog
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AttachmentSupport
import com.tamimarafat.ferngeist.feature.chat.ui.composer.HeldAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.composer.processPickedUris
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Scrolling (following the end, the runway under a sent prompt, jumps, restore) is owned by
 * [ChatScrollHandle].
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun ChatScreen(
    sessionId: String,
    fallbackTitle: String,
    onNavigateBack: () -> Unit,
    onSwitchSession: OnSwitchSession,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    sharedBoundsEnabled: Boolean = true,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val screenState = rememberChatScreenState(viewModel, sessionId)

    // --- ViewModel effects ---
    CollectChatEffects(viewModel, screenState.mutations.snackbarHostState, onNavigateBack)

    // Auto-focus the text field when the composer expands
    LaunchedEffect(screenState.mutations.composerExpanded.value, screenState.mutations.imageFocusTrigger.value) {
        if (screenState.mutations.composerExpanded.value) {
            screenState.mutations.focusRequester.requestFocus()
        }
    }

    // --- Event haptics ---
    // A permission request blocks the agent, so it earns an unmistakable tick; a finished reply
    // gets one so the moment can be noticed without watching. Both key off state that only moves
    // one way per event, so neither can fire twice for the same occurrence.
    val haptics = LocalHapticFeedback.current
    val awaitingPermission =
        remember(screenState.state.messages) {
            screenState.state.messages.sumOf { message ->
                message.segments.count { !it.toolCall?.permissionOptions.isNullOrEmpty() }
            }
        }
    LaunchedEffect(awaitingPermission) {
        if (awaitingPermission > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    var wasStreaming by remember { mutableStateOf(screenState.state.isStreaming) }
    LaunchedEffect(screenState.state.isStreaming) {
        if (wasStreaming && !screenState.state.isStreaming) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // The turn's files without reported diffs take git's counts, which must be current.
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
        }
        wasStreaming = screenState.state.isStreaming
    }

    // The working tree moves outside the app — a terminal, another checkout — and the gateway has no
    // change feed, so every resume re-reads the status. A chat's back-stack entry resumes both when
    // the app returns to the foreground and when this chat becomes the current destination again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.dispatch(ChatIntent.RefreshGitStatus)
    }

    ChatScreenScaffold(
        screenState = screenState,
        sessionId = sessionId,
        fallbackTitle = fallbackTitle,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onSwitchSession = onSwitchSession,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
        showBackButton = showBackButton,
        sharedBoundsEnabled = sharedBoundsEnabled,
    )
    screenState.mutations.pendingSend.value?.let { send ->
        ActiveElsewhereDialog(
            onConfirm = {
                screenState.mutations.pendingSend.value = null
                send()
            },
            onDismiss = { screenState.mutations.pendingSend.value = null },
        )
    }
}

internal data class ChatScreenMutations(
    val snackbarHostState: SnackbarHostState,
    val selectedConfigPickerOptionId: MutableState<String?>,
    val selectedThoughtSegmentId: MutableState<String?>,
    val selectedToolCallSegmentId: MutableState<String?>,
    val showCommandsDialog: MutableState<Boolean>,
    val pendingSend: MutableState<(() -> Unit)?>,
    val showConnectionStatusDialog: MutableState<Boolean>,
    val showGitStatusSheet: MutableState<Boolean>,
    val showSwitcherSheet: MutableState<Boolean>,
    val composerContentHeightPx: MutableState<Int>,
    val messageText: TextFieldState,
    val selectedImages: MutableState<List<ChatImageData>>,
    val selectedFiles: MutableState<List<ChatFileData>>,
    val composerExpanded: MutableState<Boolean>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
)

@Composable
private fun rememberChatScreenMutations(): ChatScreenMutations =
    ChatScreenMutations(
        snackbarHostState = remember { SnackbarHostState() },
        selectedConfigPickerOptionId = remember { mutableStateOf<String?>(null) },
        selectedThoughtSegmentId = remember { mutableStateOf<String?>(null) },
        selectedToolCallSegmentId = remember { mutableStateOf<String?>(null) },
        showCommandsDialog = remember { mutableStateOf(false) },
        pendingSend = remember { mutableStateOf<(() -> Unit)?>(null) },
        showConnectionStatusDialog = remember { mutableStateOf(false) },
        showGitStatusSheet = remember { mutableStateOf(false) },
        showSwitcherSheet = remember { mutableStateOf(false) },
        composerContentHeightPx = remember { mutableIntStateOf(0) },
        messageText = rememberTextFieldState(),
        selectedImages = remember { mutableStateOf<List<ChatImageData>>(emptyList()) },
        selectedFiles = remember { mutableStateOf<List<ChatFileData>>(emptyList()) },
        composerExpanded = remember { mutableStateOf(false) },
        focusRequester = remember { FocusRequester() },
        imageFocusTrigger = remember { mutableIntStateOf(0) },
    )

/** The attachment picker and the composer's send actions the chat screen hands its children. */
internal data class ChatScreenActions(
    val attachmentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    val attachUris: (List<Uri>) -> Unit,
    val sendMessage: () -> Unit,
    val sendCommand: (String) -> Unit,
)

internal class ChatScreenState(
    val state: ChatState,
    val mutations: ChatScreenMutations,
    val derived: ChatDerivedValues,
    val insets: ComposerInsets,
    val actions: ChatScreenActions,
)

@Composable
private fun rememberChatScreenState(
    viewModel: ChatViewModel,
    sessionId: String,
): ChatScreenState {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mutations = rememberChatScreenMutations()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // -- Attachments: the document picker and the composer's content receiver (paste/drop) feed the
    // same ingest, so both apply identical capability checks, size limits and count caps. --
    val context = LocalContext.current
    val resources = LocalResources.current
    // The ingest reads the selected lists, suspends on IO and writes them back, so two arrivals
    // close together (a drop racing the picker, a second paste) would otherwise both start from the
    // same snapshot and the later write would drop the earlier one's attachments.
    val ingestLock = remember { Mutex() }
    val attachUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            // Content can arrive while the composer is collapsed (a drop on the chat pane), and
            // the attachment it just took is only visible in the expanded composer. Pasting
            // cannot reach here collapsed, and the picker opens from the expanded composer, so
            // this only ever acts on the drop.
            mutations.composerExpanded.value = true
            coroutineScope.launch {
                ingestLock.withLock {
                    val picked =
                        processPickedUris(
                            uris = uris,
                            context = context,
                            support =
                                AttachmentSupport(
                                    canSendImages = state.canSendImages,
                                    supportsEmbeddedContext = state.supportsEmbeddedContext,
                                ),
                            held =
                                HeldAttachments(
                                    images = mutations.selectedImages.value,
                                    files = mutations.selectedFiles.value,
                                ),
                            resources = resources,
                        )
                    mutations.selectedImages.value = picked.attachments.images
                    mutations.selectedFiles.value = picked.attachments.files
                    mutations.imageFocusTrigger.value++
                    picked.feedback?.let { mutations.snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }
    val attachmentPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> -> attachUris(uris) }
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
            onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
        )
    val sendHandlers =
        rememberSendHandlers(
            viewModel = viewModel,
            state = state,
            mutations = mutations,
            focusManager = focusManager,
            scrollHandle = derived.scrollHandle,
        )
    return ChatScreenState(
        state = state,
        mutations = mutations,
        derived = derived,
        insets = composerInsets,
        actions =
            ChatScreenActions(
                attachmentPickerLauncher = attachmentPickerLauncher,
                attachUris = attachUris,
                sendMessage = sendHandlers.first,
                sendCommand = sendHandlers.second,
            ),
    )
}

// endregion
