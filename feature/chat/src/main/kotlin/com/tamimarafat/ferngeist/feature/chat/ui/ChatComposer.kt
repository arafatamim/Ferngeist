@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.onSubmitShortcut
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AnimatedComposerAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.composer.AttachmentPickers
import com.tamimarafat.ferngeist.feature.chat.ui.composer.CloseComposerButton
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerActionGroup
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerSuggestionMenu
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ComposerSuggestionState
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ModeMenuButton
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ModelEffortButton
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ToolbarOptionsButton
import com.tamimarafat.ferngeist.feature.chat.ui.composer.composerModelButtonMinWidth
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerDragHandle
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.ComposerMode
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.FullscreenComposerDialog
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.composerDragHandleHeight
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.composerDragHandleWidth
import com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen.rememberComposerBackProgress
import com.tamimarafat.ferngeist.feature.chat.ui.composer.selectOptionFor
import kotlin.math.roundToInt

private const val COLLAPSED_MAX_TOOLBAR_FRACTION = 0.92f

// Height of the collapsed toolbar. Shared with the session switcher bubble so its circle
// always matches the toolbar beside it.
internal val collapsedComposerHeight = FloatingToolbarDefaults.ContainerSize

// M3 medium FAB. The library keeps its size token, and the toolbar-to-FAB gap, internal.
private val composerFabSize = 80.dp
private val toolbarToFabGap = 8.dp
private const val COMPOSER_CONTAINER_KEY = "composer-container"
private val composerPanelPadding = 12.dp
internal val composerPanelContentPadding =
    PaddingValues(start = composerPanelPadding, end = composerPanelPadding, bottom = composerPanelPadding)

// Between the medium (56dp) and large (96dp) icon-button tokens: a notch above medium.
private val composerSendButtonSize = 64.dp

// Floor for the expanded composer. There is no matching ceiling: while expanded the
// panel is sized by its content, and the text field's own line bounds (minLines /
// maxLines) are what stop it growing and make the text scroll instead.
private val expandedComposerMinHeight = 142.dp

/**
 * Main entry point for the chat composer UI.
 * This bar can be in a collapsed state (showing actions/modes) or an expanded state (for typing).
 *
 * @param state The current chat UI state.
 * @param toolbarConfigOptions Configuration options to be displayed in the options menu.
 * @param mode The current composer presentation.
 * @param onModeChange Callback when the expansion state changes.
 * @param messageText Text field state backing the composer input.
 * @param inputAlpha Alpha (read at draw time) for the text input area (usually for fading during transitions).
 * @param buttonsAlpha Alpha (read at draw time) for the buttons in collapsed state.
 * @param showModeButton Whether to show the mode selection button.
 * @param modeOption The specific select option for modes.
 * @param currentModeLabel The human-readable label for the current mode.
 * @param showStopAction Whether a turn is running that can be stopped.
 * @param focusRequester To request focus when expanding.
 * @param onFocusCleared Callback when focus is cleared (e.g., closing the composer).
 * @param onHeightChanged Callback to report the current height of the composer to the parent.
 * @param onSend Action to send the current message.
 * @param onCancelStreaming Action to stop the current generation.
 * @param onSetStringConfigOption Callback to update a string-based config option.
 * @param onSetBooleanConfigOption Callback to update a boolean config option.
 * @param onShowCommands Callback to show available commands.
 * @param onShowConfigOptionPicker Callback to show a dedicated picker for a config option.
 * @param sharedTransitionScope Scope of the FAB-to-panel transform; see its use for why it is hoisted.
 * @param dropTargetActive Whether a drag is currently hovering the chat, i.e. the drop the
 *   composer is about to receive.
 */
@Composable
internal fun ChatComposerBar(
    modifier: Modifier = Modifier,
    state: ChatState,
    toolbarConfigOptions: List<ChatConfigOption>,
    mode: ComposerMode,
    onModeChange: (ComposerMode) -> Unit,
    messageText: TextFieldState,
    mentionState: ComposerSuggestionState,
    onMentionSelected: (String, Int?) -> Unit,
    inputAlpha: () -> Float,
    buttonsAlpha: () -> Float,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    focusRequester: FocusRequester,
    focusEditor: @Composable () -> Unit,
    onFocusCleared: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    onSend: () -> Unit,
    onCancelStreaming: () -> Unit,
    onSetStringConfigOption: (String, String) -> Unit,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
    showJumpToBottom: Boolean,
    onJumpToBottom: () -> Unit,
    canSendImages: Boolean,
    selectedImages: List<ChatImageData>,
    onImagesChanged: (List<ChatImageData>) -> Unit,
    canSendFiles: Boolean,
    selectedFiles: List<ChatFileData>,
    onFilesChanged: (List<ChatFileData>) -> Unit,
    canSendAudio: Boolean,
    selectedAudios: List<ChatAudioData>,
    onAudiosChanged: (List<ChatAudioData>) -> Unit,
    selectedResourceLinks: List<ChatPromptResourceLink>,
    onResourceLinksChanged: (List<ChatPromptResourceLink>) -> Unit,
    attachmentPickers: AttachmentPickers,
    dropTargetActive: Boolean,
    onAttachUris: (List<Uri>) -> Unit,
    snackbarHostState: SnackbarHostState,
    sharedTransitionScope: SharedTransitionScope,
) {
    var showModeMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }
    // Stop is destructive, so it never takes the FAB: it sits in the toolbar. In the expanded
    // composer an agent that takes prompts mid-turn keeps send primary, as a new prompt queues
    // and can be steered in; for any other agent send becomes stop.
    val steering = showStopAction && state.supportsSteering
    val primaryStop = showStopAction && !steering
    val commandInputHint = activeCommandInputHint(messageText.text, state.availableCommands)
    val modelOption = remember(state.configOptions) { state.configOptions.selectOptionFor(ChatConfigCategory.Model) }
    val effortOption =
        remember(state.configOptions) { state.configOptions.selectOptionFor(ChatConfigCategory.ThoughtLevel) }

    val dragDistance = remember(mode) { mutableFloatStateOf(0f) }
    val closeComposer: () -> Unit = {
        onModeChange(ComposerMode.COLLAPSED)
        onFocusCleared()
    }
    val backProgress = rememberComposerBackProgress(enabled = mode == ComposerMode.EXPANDED, onClose = closeComposer)
    val editor: @Composable (Boolean, Boolean, Boolean) -> Unit = { fullscreen, fillEditor, editorDropTargetActive ->
        focusEditor()
        ExpandedComposerContent(
            messageText = messageText,
            mentionState = mentionState,
            onMentionSelected = onMentionSelected,
            commandInputHint = commandInputHint,
            inputAlpha = inputAlpha,
            focusRequester = focusRequester,
            showStopAction = primaryStop,
            queueing = steering,
            fullscreen = fullscreen,
            fillEditor = fillEditor,
            dragDistance = dragDistance,
            onToggleFullscreen = { onModeChange(if (fullscreen) ComposerMode.EXPANDED else ComposerMode.FULLSCREEN) },
            onClose = closeComposer,
            onPrimaryAction = { if (primaryStop) onCancelStreaming() else onSend() },
            onSend = onSend,
            canSendImages = canSendImages,
            selectedImages = selectedImages,
            onImagesChanged = onImagesChanged,
            canSendFiles = canSendFiles,
            selectedFiles = selectedFiles,
            onFilesChanged = onFilesChanged,
            canSendAudio = canSendAudio,
            selectedAudios = selectedAudios,
            onAudiosChanged = onAudiosChanged,
            selectedResourceLinks = selectedResourceLinks,
            onResourceLinksChanged = onResourceLinksChanged,
            attachmentPickers = attachmentPickers,
            dropTargetActive = editorDropTargetActive,
            agentIconUrl = state.iconUrl,
            agentName = state.agentName,
            modelOption = modelOption,
            effortOption = effortOption,
            onSetConfigOption = onSetStringConfigOption,
        )
    }

    if (mode == ComposerMode.FULLSCREEN) {
        FullscreenComposerDialog(
            canSendImages = canSendImages,
            canSendFiles = canSendFiles,
            canSendAudio = canSendAudio,
            onAttachUris = onAttachUris,
            snackbarHostState = snackbarHostState,
            dragDistance = dragDistance,
            onDismiss = closeComposer,
        ) { hovered -> editor(true, true, hovered) }
        return
    }
    val composerExpanded = mode.isExpanded
    val density = LocalDensity.current
    // Height the expanded content needs, measured unconstrained so it reports what the
    // content wants rather than what the panel currently allows. Kept across expansions so
    // the panel opens straight to the height it had last time.
    var expandedContentHeightPx by remember { mutableIntStateOf(0) }
    val expandedHeight = with(density) { expandedContentHeightPx.toDp().coerceAtLeast(expandedComposerMinHeight) }
    // Reports the resting height, not the animated one: the parent turns this into list
    // padding at screen level, so a per-frame value recomposed the whole chat screen and
    // re-laid out the list on every frame of the expand/collapse.
    val restingHeightPx =
        with(density) { (if (composerExpanded) expandedHeight else composerFabSize).roundToPx() }
    LaunchedEffect(restingHeightPx) { onHeightChanged(restingHeightPx) }
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val boundsTransform = BoundsTransform { _, _ -> boundsSpec }
    val crossfadeIn = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())
    val crossfadeOut = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
    BoxWithConstraints(modifier = modifier) {
        val expandedWidth = maxWidth
        val toolbarMaxWidth = maxWidth * COLLAPSED_MAX_TOOLBAR_FRACTION - composerFabSize - toolbarToFabGap
        // The FAB and the expanded composer are one shared element: expanding grows the FAB's
        // bounds into the panel's (an M3 container transform) while one cross-fades into the other.
        // The scope comes from a parent whose bounds hold still: this bar moves and resizes as it
        // expands, and shared bounds are tracked relative to the scope's layout.
        with(sharedTransitionScope) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AnimatedVisibility(
                    visible = !composerExpanded,
                    // The shared bounds carry the FAB and the toolbar folds on its own, below.
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The FAB sits beside the toolbar rather than in its FAB slot: the slot pins it at
                        // 56dp, and resizes it as the toolbar folds, which the transform then chases.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // The toolbar folds into the FAB beside it, so it collapses toward its end.
                            modifier =
                                Modifier.animateEnterExit(
                                    enter =
                                        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                            expandHorizontally(
                                                MaterialTheme.motionScheme.fastSpatialSpec(),
                                                expandFrom = Alignment.End,
                                            ),
                                    exit =
                                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                            shrinkHorizontally(
                                                MaterialTheme.motionScheme.fastSpatialSpec(),
                                                shrinkTowards = Alignment.End,
                                            ),
                                ),
                        ) {
                            HorizontalFloatingToolbar(
                                expanded = true,
                                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                                modifier =
                                    Modifier.widthIn(max = toolbarMaxWidth).graphicsLayer { alpha = buttonsAlpha() },
                            ) {
                                CollapsedComposerActions(
                                    state = state,
                                    toolbarConfigOptions = toolbarConfigOptions,
                                    showModeButton = showModeButton,
                                    modeOption = modeOption,
                                    currentModeLabel = currentModeLabel,
                                    showStop = showStopAction,
                                    collapsedMaxToolbarWidth = toolbarMaxWidth,
                                    showModeMenu = showModeMenu,
                                    onShowModeMenuChange = { showModeMenu = it },
                                    modeMenuInteractionSource = modeMenuInteractionSource,
                                    showOptionsMenu = showOptionsMenu,
                                    onShowOptionsMenuChange = { showOptionsMenu = it },
                                    optionsMenuInteractionSource = optionsMenuInteractionSource,
                                    onCancelStreaming = onCancelStreaming,
                                    onSetStringConfigOption = onSetStringConfigOption,
                                    onSetBooleanConfigOption = onSetBooleanConfigOption,
                                    onShowCommands = onShowCommands,
                                    onShowConfigOptionPicker = onShowConfigOptionPicker,
                                    showJumpToBottom = showJumpToBottom,
                                    onJumpToBottom = onJumpToBottom,
                                )
                            }
                            Spacer(modifier = Modifier.width(toolbarToFabGap))
                        }
                        ComposerFab(
                            dropTargetActive = dropTargetActive,
                            iconAlpha = buttonsAlpha,
                            onClick = { onModeChange(ComposerMode.EXPANDED) },
                            modifier =
                                Modifier.sharedBounds(
                                    sharedContentState = rememberSharedContentState(COMPOSER_CONTAINER_KEY),
                                    animatedVisibilityScope = this@AnimatedVisibility,
                                    enter = crossfadeIn,
                                    exit = crossfadeOut,
                                    boundsTransform = boundsTransform,
                                ),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = composerExpanded,
                    // The shared bounds carry the panel in and out; it keeps no motion of its own.
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                ) {
                    ComposerPanel(
                        expandedWidth = expandedWidth,
                        expandedHeight = expandedHeight,
                        dragDistance = dragDistance,
                        backProgress = { backProgress.value },
                        onContentHeightPx = { expandedContentHeightPx = it },
                        modifier =
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(COMPOSER_CONTAINER_KEY),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                enter = crossfadeIn,
                                exit = crossfadeOut,
                                boundsTransform = boundsTransform,
                            ),
                    ) { fillEditor ->
                        editor(false, fillEditor, dropTargetActive)
                    }
                }
            }
        }
    }
}

/**
 * The expanded composer's panel: neutral and opaque so the transcript never shows through.
 *
 * Its height is animated explicitly rather than with `animateContentSize`: what the content
 * needs is measured with the panel's own constraint lifted (see
 * `wrapContentHeight(unbounded = true)`), and that is information an animator watching the
 * node's own size does not have.
 */
@Composable
private fun ComposerPanel(
    expandedWidth: Dp,
    expandedHeight: Dp,
    dragDistance: FloatState,
    backProgress: () -> Float,
    onContentHeightPx: (Int) -> Unit,
    modifier: Modifier,
    content: @Composable (fillEditor: Boolean) -> Unit,
) {
    // Growing with the text is spatial motion, so it takes the theme's spatial spec.
    val height by animateDpAsState(
        expandedHeight,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "ComposerHeight",
    )
    val dragHeight = with(LocalDensity.current) { dragDistance.floatValue.toDp() }
    val fillEditor = dragHeight > 0.dp
    val progress = backProgress()
    Surface(
        // The panel's corner, easing to the medium FAB's as a back gesture shrinks it toward one.
        shape = RoundedCornerShape(lerp(28.dp, 20.dp, progress)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier.size(
                lerp(expandedWidth, composerFabSize, progress),
                lerp(height + dragHeight, composerFabSize, progress),
            ),
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            // Keep the editor's measured width stable as its surface narrows.
            val width =
                if (progress > 0f) {
                    Modifier.wrapContentWidth(Alignment.End, unbounded = true).width(expandedWidth)
                } else {
                    Modifier
                }
            val bounds =
                if (fillEditor) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(unbounded = true)
                        .onSizeChanged { onContentHeightPx(it.height) }
                }
            // Inside the size report, so the panel's measured height includes it.
            Box(width.then(bounds).padding(composerPanelContentPadding)) { content(fillEditor) }
        }
    }
}

/** The FAB's face: the primary action, writing, with its label as a tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerFab(
    dropTargetActive: Boolean,
    iconAlpha: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val icon = if (dropTargetActive) Icons.Default.MoveToInbox else Icons.Default.Edit
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip { Text(stringResource(R.string.chat_chat)) }
        },
        state = rememberTooltipState(),
    ) {
        // Primary rather than the vibrant toolbar's tertiary container: the FAB is the screen's
        // one high-emphasis action and has to stand out against the pale toolbar beside it.
        MediumFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier.handCursor(),
        ) {
            Crossfade(
                targetState = icon,
                modifier = Modifier.graphicsLayer { alpha = iconAlpha() },
                label = "ComposerFabIcon",
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = stringResource(R.string.chat_chat),
                    modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize),
                )
            }
        }
    }
}

/**
 * UI content for the composer when expanded for text entry.
 */
@Composable
internal fun ExpandedComposerContent(
    messageText: TextFieldState,
    mentionState: ComposerSuggestionState,
    onMentionSelected: (String, Int?) -> Unit,
    commandInputHint: String?,
    inputAlpha: () -> Float,
    focusRequester: FocusRequester,
    showStopAction: Boolean,
    queueing: Boolean,
    fullscreen: Boolean,
    fillEditor: Boolean,
    dragDistance: MutableFloatState,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSend: () -> Unit,
    canSendImages: Boolean,
    selectedImages: List<ChatImageData>,
    onImagesChanged: (List<ChatImageData>) -> Unit,
    canSendFiles: Boolean,
    selectedFiles: List<ChatFileData>,
    onFilesChanged: (List<ChatFileData>) -> Unit,
    canSendAudio: Boolean,
    selectedAudios: List<ChatAudioData>,
    onAudiosChanged: (List<ChatAudioData>) -> Unit,
    selectedResourceLinks: List<ChatPromptResourceLink>,
    onResourceLinksChanged: (List<ChatPromptResourceLink>) -> Unit,
    attachmentPickers: AttachmentPickers,
    dropTargetActive: Boolean,
    agentIconUrl: String?,
    agentName: String?,
    modelOption: ChatConfigOption.Select?,
    effortOption: ChatConfigOption.Select?,
    onSetConfigOption: (String, String) -> Unit,
) {
    val hasAttachments =
        selectedImages.isNotEmpty() ||
            selectedFiles.isNotEmpty() ||
            selectedAudios.isNotEmpty() ||
            selectedResourceLinks.isNotEmpty()
    val editorBounds = if (fillEditor) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    val lineLimits =
        if (fillEditor) {
            TextFieldLineLimits.MultiLine()
        } else {
            TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8)
        }
    Column(
        modifier =
            editorBounds
                .graphicsLayer { alpha = inputAlpha() }
                .onPreviewKeyEvent { mentionState.onKey(it, onMentionSelected) }
                .onEscapeKey(onEscape = onClose)
                // Restates the send button's contract for the keyboard: the button is disabled
                // while streaming and while the field is blank, so the shortcut is too.
                .onSubmitShortcut(
                    enabled = !showStopAction && (messageText.text.isNotBlank() || hasAttachments),
                    onSubmit = onSend,
                ),
    ) {
        ExpandedComposerHeader(
            fullscreen = fullscreen,
            dragDistance = dragDistance,
            onToggleFullscreen = onToggleFullscreen,
            onClose = onClose,
            agentIconUrl = agentIconUrl,
            agentName = agentName,
            modelOption = modelOption,
            effortOption = effortOption,
            onSetConfigOption = onSetConfigOption,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Outside the text card, on the panel: what is attached reads apart from what is typed.
        AnimatedComposerAttachments(
            images = selectedImages,
            audios = selectedAudios,
            files = selectedFiles,
            links = selectedResourceLinks,
            onImagesChanged = onImagesChanged,
            onAudiosChanged = onAudiosChanged,
            onFilesChanged = onFilesChanged,
            onLinksChanged = onResourceLinksChanged,
        )
        Surface(
            // Concentric with the panel: its 28dp corner less the 12dp inset.
            shape = MaterialTheme.shapes.large,
            // Two tones above the panel, so the field reads as raised without a shadow.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = if (fillEditor) Modifier.weight(1f).fillMaxWidth() else Modifier,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                ExpandedComposerTextField(
                    messageText = messageText,
                    mentionState = mentionState,
                    onMentionSelected = onMentionSelected,
                    commandInputHint = commandInputHint,
                    focusRequester = focusRequester,
                    onSend = onSend,
                    lineLimits = lineLimits,
                    modifier = editorBounds,
                )
            }
        }
        ExpandedComposerBottomBar(
            canSendImages = canSendImages,
            canSendFiles = canSendFiles,
            canSendAudio = canSendAudio,
            attachmentPickers = attachmentPickers,
            showStopAction = showStopAction,
            queueing = queueing,
            messageText = messageText.text,
            hasAttachments = hasAttachments,
            onPrimaryAction = onPrimaryAction,
            dropTargetActive = dropTargetActive,
        )
    }
}

@Composable
private fun ExpandedComposerHeader(
    fullscreen: Boolean,
    dragDistance: MutableFloatState,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    agentIconUrl: String?,
    agentName: String?,
    modelOption: ChatConfigOption.Select?,
    effortOption: ChatConfigOption.Select?,
    onSetConfigOption: (String, String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sideWidth = ((maxWidth - composerDragHandleWidth) / 2).coerceAtLeast(0.dp)
        val effortWidth = if (effortOption == null) 0.dp else 50.dp
        // Give the controls a separate row when the selector cannot fit beside the handle.
        val controlsBelowHandle = modelOption != null && sideWidth < composerModelButtonMinWidth + effortWidth
        val controlsTop = if (controlsBelowHandle) composerDragHandleHeight else composerPanelPadding
        val selectorWidth = if (controlsBelowHandle) maxWidth else sideWidth
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = controlsTop),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloseComposerButton(onClose = onClose, fullscreen = fullscreen)
            if (modelOption != null) {
                Spacer(Modifier.weight(1f))
                ModelEffortButton(
                    agentIconUrl = agentIconUrl,
                    agentName = agentName,
                    modelOption = modelOption,
                    effortOption = effortOption,
                    onSetConfigOption = onSetConfigOption,
                    modifier = Modifier.widthIn(max = selectorWidth),
                )
            }
        }
        ComposerDragHandle(
            fullscreen = fullscreen,
            dragDistance = dragDistance,
            onToggle = onToggleFullscreen,
            onDismiss = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The text input area inside [ExpandedComposerContent], including
 * selection-colors and the placeholder hint.
 *
 * Uses the [TextFieldState] text field: it is the only overload that can act as a content receiver,
 * which is what lets paste and drag-and-drop deliver attachments (see
 * [composerContentReceiver]).
 */
@Composable
private fun ExpandedComposerTextField(
    messageText: TextFieldState,
    mentionState: ComposerSuggestionState,
    onMentionSelected: (String, Int?) -> Unit,
    commandInputHint: String?,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    lineLimits: TextFieldLineLimits,
    modifier: Modifier,
) {
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    var menuAnchor by remember { mutableStateOf(IntOffset.Zero) }
    val selectionColors =
        TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.primary,
            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        )
    val decorator =
        remember(messageText, mentionState, onMentionSelected, commandInputHint, scrollState) {
            TextFieldDecorator { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (messageText.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_composer_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                    val caret =
                        textLayout?.let { layout ->
                            layout.getCursorRect(messageText.selection.end.coerceAtMost(layout.layoutInput.text.length))
                        }
                    if (caret != null) {
                        commandInputHint?.let { hint ->
                            val hintAnchor = textLayout?.let { it.getCursorRect(it.layoutInput.text.length) } ?: caret
                            Text(
                                text = "[$hint]",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.offset {
                                        IntOffset(
                                            hintAnchor.left.roundToInt(),
                                            (hintAnchor.top - scrollState.value).roundToInt(),
                                        )
                                    },
                            )
                        }
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(caret.left.roundToInt(), (caret.top - scrollState.value).roundToInt())
                                }.size(0.dp)
                                .onGloballyPositioned { coordinates ->
                                    if (mentionState.visible) {
                                        val position = coordinates.positionInWindow()
                                        menuAnchor = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                                    }
                                },
                        ) {
                            ComposerSuggestionMenu(mentionState, menuAnchor, onMentionSelected)
                        }
                    }
                }
            }
        }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            state = messageText,
            scrollState = scrollState,
            onTextLayout = { getResult -> getResult()?.let { textLayout = it } },
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            // The field's IME action is Send, so every action reported here is a send; the default
            // action (dismissing the keyboard) is deliberately skipped, as the button does not
            // dismiss it either.
            onKeyboardAction = KeyboardActionHandler { onSend() },
            lineLimits = lineLimits,
            decorator = decorator,
            modifier = modifier.focusRequester(focusRequester),
        )
    }
}

/** The bottom row: a button group of secondary actions beside the send button. */
@Composable
private fun ExpandedComposerBottomBar(
    canSendImages: Boolean,
    canSendFiles: Boolean,
    canSendAudio: Boolean,
    attachmentPickers: AttachmentPickers,
    showStopAction: Boolean,
    queueing: Boolean,
    messageText: CharSequence,
    hasAttachments: Boolean,
    onPrimaryAction: () -> Unit,
    dropTargetActive: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerActionGroup(
            canSendImages = canSendImages,
            showFiles = canSendImages || canSendFiles || canSendAudio,
            pickers = attachmentPickers,
            dropTargetActive = dropTargetActive,
        )
        // Keep send at the trailing edge even when there are no attachment actions.
        Spacer(modifier = Modifier.weight(1f))
        PrimaryComposerActionButton(
            showStopAction = showStopAction,
            // Attachments alone are a sendable prompt, just like text alone.
            enabled = showStopAction || messageText.isNotBlank() || hasAttachments,
            onClick = onPrimaryAction,
            chatIcon = if (queueing) Icons.AutoMirrored.Filled.ScheduleSend else Icons.AutoMirrored.Filled.Send,
        )
    }
}

/**
 * UI content for the composer in its collapsed state, showing quick actions.
 */
@Composable
internal fun RowScope.CollapsedComposerActions(
    state: ChatState,
    toolbarConfigOptions: List<ChatConfigOption>,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStop: Boolean,
    collapsedMaxToolbarWidth: Dp,
    showModeMenu: Boolean,
    onShowModeMenuChange: (Boolean) -> Unit,
    modeMenuInteractionSource: MutableInteractionSource,
    showOptionsMenu: Boolean,
    onShowOptionsMenuChange: (Boolean) -> Unit,
    optionsMenuInteractionSource: MutableInteractionSource,
    onCancelStreaming: () -> Unit,
    onSetStringConfigOption: (String, String) -> Unit,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
    showJumpToBottom: Boolean,
    onJumpToBottom: () -> Unit,
) {
    if (showModeButton && modeOption != null) {
        ModeMenuButton(
            // Weighted so the fixed-size actions are measured first and the label takes
            // what is left, ellipsizing on a narrow bar.
            modifier = Modifier.weight(1f, fill = false),
            currentModeLabel = currentModeLabel,
            modeOption = modeOption,
            maxWidth = collapsedMaxToolbarWidth * 0.45f,
            expanded = showModeMenu,
            onExpandedChange = onShowModeMenuChange,
            interactionSource = modeMenuInteractionSource,
            onSetConfigOption = onSetStringConfigOption,
        )
        Spacer(modifier = Modifier.width(6.dp))
    }

    AnimatedVisibility(visible = showStop, enter = toolbarActionEnter, exit = toolbarActionExit) {
        StopButton(onCancelStreaming = onCancelStreaming)
    }

    AnimatedVisibility(visible = showJumpToBottom, enter = toolbarActionEnter, exit = toolbarActionExit) {
        JumpToBottomButton(onJumpToBottom = onJumpToBottom)
    }

    ToolbarOptionsButton(
        commandsAdvertised = state.commandsAdvertised,
        configOptions = toolbarConfigOptions,
        expanded = showOptionsMenu,
        onExpandedChange = onShowOptionsMenuChange,
        interactionSource = optionsMenuInteractionSource,
        onSetBooleanConfigOption = onSetBooleanConfigOption,
        onShowCommands = onShowCommands,
        onShowConfigOptionPicker = onShowConfigOptionPicker,
    )
}

/** A toolbar action popping in or out, with the bar resizing around it. */
private val toolbarActionEnter =
    fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
        scaleIn(initialScale = 0.6f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
        expandHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium))
private val toolbarActionExit =
    fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
        scaleOut(targetScale = 0.6f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
        shrinkHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium))

/** Stop as a plain toolbar action: destructive, so it never takes the FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopButton(onCancelStreaming: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.chat_stop))
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onCancelStreaming, modifier = Modifier.handCursor()) {
            Icon(imageVector = Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop_desc))
        }
    }
}

/**
 * Jump-to-bottom scroll button wrapped in a [TooltipBox].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpToBottomButton(onJumpToBottom: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.chat_scroll_to_bottom))
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onJumpToBottom, modifier = Modifier.handCursor()) {
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
            )
        }
    }
}

/**
 * The primary action button (Send or Stop).
 * Uses a filled icon button style consistent with the composer theme.
 */
@Composable
internal fun PrimaryComposerActionButton(
    showStopAction: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    chatIcon: ImageVector,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes =
            IconButtonDefaults.shapes(
                shape = IconButtonDefaults.mediumRoundShape,
                pressedShape = IconButtonDefaults.mediumPressedShape,
            ),
        modifier = modifier.size(composerSendButtonSize).handCursor(enabled = enabled),
    ) {
        Crossfade(targetState = if (showStopAction) Icons.Default.Stop else chatIcon, label = "PrimaryComposerIcon") {
            Icon(
                imageVector = it,
                contentDescription =
                    stringResource(if (showStopAction) R.string.chat_stop_desc else R.string.chat_send_desc),
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
    }
}
