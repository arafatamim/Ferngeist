package com.tamimarafat.ferngeist.feature.chat.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimarafat.ferngeist.core.common.ui.auth.AuthenticationDialog
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.OnSwitchSession
import com.tamimarafat.ferngeist.feature.chat.activity.ActiveElsewhereDialog
import com.tamimarafat.ferngeist.feature.chat.selection.localQuoteSelection
import com.tamimarafat.ferngeist.feature.chat.selection.quoteDraft
import com.tamimarafat.ferngeist.feature.chat.ui.audio.localAudioPlaybackController
import com.tamimarafat.ferngeist.feature.chat.ui.audio.rememberAudioPlaybackController
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AttachmentPickers
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AttachmentSupport
import com.tamimarafat.ferngeist.feature.chat.ui.composer.HeldAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode
import com.tamimarafat.ferngeist.feature.chat.ui.composer.processPickedUris
import com.tamimarafat.ferngeist.feature.chat.ui.composer.rememberAttachmentPickers
import com.tamimarafat.ferngeist.feature.chat.ui.scroll.ChatScrollHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tamimarafat.ferngeist.core.common.R as CommonR

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
    // A new chat's route keeps the shared new-session sentinel; once minted, the real id is the one
    // its list card shares bounds under, so the morph back lands on that card.
    val mintedSessionId by viewModel.mintedSessionIds.collectAsStateWithLifecycle()

    screenState.state.authentication?.let { request ->
        var selectedMethod by remember(request.agentName) { mutableStateOf<String?>(null) }
        AuthenticationDialog(
            request = request,
            gatewayBacked = screenState.state.gatewayWorkspaceConnection != null,
            title =
                stringResource(
                    CommonR.string.common_auth_title,
                    request.agentName,
                ),
            selectedAuthMethodId = selectedMethod,
            onSelectedAuthMethodChange = { selectedMethod = it },
            allowEnvironmentAuth = false,
            onSubmit = { methodId, _ -> viewModel.dispatch(ChatIntent.Authenticate(methodId)) },
            onReconnect = {},
            onDismiss = {
                if (request.isAuthenticating) onNavigateBack() else viewModel.dispatch(ChatIntent.DismissAuthentication)
            },
        )
    }

    // --- ViewModel effects ---
    CollectChatEffects(viewModel, screenState.mutations.snackbarHostState, onNavigateBack, onSwitchSession)

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

    val audioPlayback = rememberAudioPlaybackController(sessionId, viewModel.artifactIo)
    CompositionLocalProvider(
        localAudioPlaybackController provides audioPlayback,
        localQuoteSelection provides { selected ->
            val draft = screenState.mutations.messageText
            draft.edit { replace(0, length, quoteDraft(toString(), selected)) }
            screenState.mutations.openComposer()
        },
    ) {
        ChatScreenScaffold(
            screenState = screenState,
            sessionId = mintedSessionId ?: sessionId,
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
    }
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
    val selectedAudios: MutableState<List<ChatAudioData>> = mutableStateOf(emptyList()),
    val selectedResourceLinks: MutableState<List<ChatPromptResourceLink>> = mutableStateOf(emptyList()),
    val composerMode: MutableState<ComposerMode>,
    val focusRequester: FocusRequester,
    val imageFocusTrigger: MutableState<Int>,
    val composerFocusTrigger: MutableState<Int>,
    val sendCheckLock: Mutex = Mutex(),
) {
    fun openComposer() {
        if (composerMode.value == ComposerMode.COLLAPSED) composerMode.value = ComposerMode.EXPANDED
    }

    fun applyPickedAttachments(
        previous: HeldAttachments,
        attachments: HeldAttachments,
    ) {
        // Append the accepted suffix so a removal made during decoding stays removed.
        val images = attachments.images.drop(previous.images.size)
        val files = attachments.files.drop(previous.files.size)
        val audios = attachments.audios.drop(previous.audios.size)
        if (images.isNotEmpty() || files.isNotEmpty() || audios.isNotEmpty()) {
            selectedImages.value += images
            selectedFiles.value += files
            selectedAudios.value += audios
            composerMode.value = ComposerMode.FULLSCREEN
        }
        imageFocusTrigger.value++
    }

    fun attachResourceLink(link: ChatPromptResourceLink) {
        val existing = selectedResourceLinks.value
        if (existing.none { it.uri == link.uri }) {
            selectedResourceLinks.value = existing + link
            composerMode.value = ComposerMode.FULLSCREEN
        }
    }
}

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
        selectedAudios = remember { mutableStateOf<List<ChatAudioData>>(emptyList()) },
        selectedResourceLinks = remember { mutableStateOf<List<ChatPromptResourceLink>>(emptyList()) },
        composerMode = remember { mutableStateOf(ComposerMode.COLLAPSED) },
        focusRequester = remember { FocusRequester() },
        imageFocusTrigger = remember { mutableIntStateOf(0) },
        composerFocusTrigger = remember { mutableIntStateOf(0) },
    )

/** The attachment picker and the composer's send actions the chat screen hands its children. */
internal data class ChatScreenActions(
    val attachmentPickers: AttachmentPickers,
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

    // -- Attachments: the pickers, camera, and content receiver (paste/drop) feed the same ingest,
    // so all sources apply identical capability checks, size limits and count caps. --
    val context = LocalContext.current
    val resources = LocalResources.current
    // The ingest reads the selected lists, suspends on IO and writes them back, so two arrivals
    // close together (a drop racing the picker, a second paste) would otherwise both start from the
    // same snapshot and the later write would drop the earlier one's attachments.
    val ingestLock = remember { Mutex() }
    val attachUris: (List<Uri>) -> Job? = { uris ->
        if (uris.isEmpty()) {
            null
        } else {
            coroutineScope.launch {
                ingestLock.withLock {
                    // A pane drop can arrive while collapsed. Open only after something is accepted.
                    val held =
                        HeldAttachments(
                            images = mutations.selectedImages.value,
                            files = mutations.selectedFiles.value,
                            audios = mutations.selectedAudios.value,
                        )
                    val picked =
                        processPickedUris(
                            uris = uris,
                            context = context,
                            support =
                                AttachmentSupport(
                                    canSendImages = state.canSendImages,
                                    supportsEmbeddedContext = state.supportsEmbeddedContext,
                                    canSendAudio = state.canSendAudio,
                                ),
                            held = held,
                            resources = resources,
                        )
                    mutations.applyPickedAttachments(previous = held, attachments = picked.attachments)
                    picked.feedback?.let { mutations.snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }
    val attachmentPickers =
        rememberAttachmentPickers(attachUris) { message ->
            coroutineScope.launch { mutations.snackbarHostState.showSnackbar(message) }
        }
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
            composerExpanded = mutations.composerMode.value.isExpanded,
            onScrollSnapshotChanged = viewModel::persistScrollSnapshot,
        )
    val sendHandlers =
        coroutineScope.rememberSendHandlers(
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
                attachmentPickers = attachmentPickers,
                attachUris = { attachUris(it) },
                sendMessage = sendHandlers.first,
                sendCommand = sendHandlers.second,
            ),
    )
}

// endregion
