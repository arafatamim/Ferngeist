@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.onSubmitShortcut
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.composer.FileChipRow
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ImageThumbnailRow
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ModeMenuButton
import com.tamimarafat.ferngeist.feature.chat.ui.composer.ToolbarOptionsButton

private const val COLLAPSED_MAX_TOOLBAR_FRACTION = 0.92f

// Resting height of the collapsed pill. Shared with the session switcher bubble
// so its circle always matches the pill's height.
internal val collapsedComposerHeight = 62.dp

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
 * @param composerExpanded Whether the composer is currently expanded for text entry.
 * @param onComposerExpandedChange Callback when the expansion state changes.
 * @param messageText Text field state backing the composer input.
 * @param inputAlpha Alpha (read at draw time) for the text input area (usually for fading during transitions).
 * @param buttonsAlpha Alpha (read at draw time) for the buttons in collapsed state.
 * @param showModeButton Whether to show the mode selection button.
 * @param modeOption The specific select option for modes.
 * @param currentModeLabel The human-readable label for the current mode.
 * @param showStopAction Whether to show the "Stop" button instead of send/expand.
 * @param canCancelStreaming Whether the current stream can actually be canceled.
 * @param focusRequester To request focus when expanding.
 * @param onFocusCleared Callback when focus is cleared (e.g., closing the composer).
 * @param onHeightChanged Callback to report the current height of the composer to the parent.
 * @param onSend Action to send the current message.
 * @param onCancelStreaming Action to stop the current generation.
 * @param onSetStringConfigOption Callback to update a string-based config option.
 * @param onSetBooleanConfigOption Callback to update a boolean config option.
 * @param onShowCommands Callback to show available commands.
 * @param onShowConfigOptionPicker Callback to show a dedicated picker for a config option.
 * @param dropTargetActive Whether a drag is currently hovering the chat, i.e. the drop the
 *   composer is about to receive.
 */
@Composable
internal fun ChatComposerBar(
    modifier: Modifier = Modifier,
    state: ChatState,
    toolbarConfigOptions: List<ChatConfigOption>,
    composerExpanded: Boolean,
    onComposerExpandedChange: (Boolean) -> Unit,
    messageText: TextFieldState,
    inputAlpha: () -> Float,
    buttonsAlpha: () -> Float,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    focusRequester: FocusRequester,
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
    onAttach: () -> Unit,
    dropTargetActive: Boolean,
) {
    var showModeMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }
    // An agent that takes prompts mid-turn keeps writing primary: a new prompt queues and can
    // be steered in, so stop steps aside into the toolbar.
    val stopAside = showStopAction && state.supportsSteering
    val primaryStop = showStopAction && !stopAside

    ComposerSurfaceContainer(
        modifier = modifier,
        composerExpanded = composerExpanded,
        onHeightChanged = onHeightChanged,
    ) { collapsedMaxToolbarWidth ->
        if (composerExpanded) {
            ExpandedComposerContent(
                messageText,
                inputAlpha,
                focusRequester,
                primaryStop,
                canCancelStreaming,
                stopAside,
                {
                    onComposerExpandedChange(false)
                    onFocusCleared()
                },
                {
                    if (primaryStop && canCancelStreaming) {
                        onCancelStreaming()
                    } else if (!primaryStop) {
                        onSend()
                    }
                },
                onSend,
                canSendImages,
                selectedImages,
                onImagesChanged,
                canSendFiles,
                selectedFiles,
                onFilesChanged,
                onAttach,
                dropTargetActive,
            )
        } else {
            CollapsedComposerActions(
                state,
                toolbarConfigOptions,
                buttonsAlpha,
                showModeButton,
                modeOption,
                currentModeLabel,
                primaryStop,
                canCancelStreaming,
                stopAside,
                collapsedMaxToolbarWidth,
                showModeMenu,
                { showModeMenu = it },
                modeMenuInteractionSource,
                showOptionsMenu,
                { showOptionsMenu = it },
                optionsMenuInteractionSource,
                { onComposerExpandedChange(true) },
                onCancelStreaming,
                onSetStringConfigOption,
                onSetBooleanConfigOption,
                onShowCommands,
                onShowConfigOptionPicker,
                showJumpToBottom,
                onJumpToBottom,
                dropTargetActive,
            )
        }
    }
}

/**
 * Width the panel animates between: whatever its bar currently needs when collapsed,
 * and the full available width when expanded.
 *
 * The collapsed width is only known once its content has been measured, and the first
 * value reported is a resting width rather than a change to animate. Snapping on that
 * first target stops the pill growing out of nothing; every later target springs.
 *
 * The result never goes below the collapsed width: the panel wraps its bar, so an
 * undershoot would squeeze the bar, and the squeezed width would then be read back as
 * the bar's own — walking the target down a little on every collapse.
 */
@Composable
private fun rememberPanelWidth(
    composerExpanded: Boolean,
    collapsedWidthPx: Int,
    expandedWidth: Dp,
): Dp {
    val collapsedWidth = with(LocalDensity.current) { collapsedWidthPx.toDp() }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(collapsedWidthPx) {
        if (collapsedWidthPx > 0) settled = true
    }
    val animatedWidth by animateDpAsState(
        targetValue = if (composerExpanded) expandedWidth else collapsedWidth,
        animationSpec =
            if (settled) {
                MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
            } else {
                snap()
            },
        label = "ComposerWidth",
    )
    return animatedWidth.coerceAtLeast(collapsedWidth)
}

/**
 * The Surface + Row container that wraps either expanded or collapsed composer
 * content, switching based on [composerExpanded].
 *
 * The panel animates an explicit size rather than leaning on `animateContentSize`: what
 * the content needs has to be measured with the panel's own constraint lifted (see
 * `wrapContentHeight(unbounded = true)`), and that is information an animator watching
 * the node's own size does not have.
 */
@Composable
private fun ComposerSurfaceContainer(
    modifier: Modifier,
    composerExpanded: Boolean,
    onHeightChanged: (Int) -> Unit,
    content: @Composable RowScope.(collapsedMaxToolbarWidth: Dp) -> Unit,
) {
    // The panel has two resting widths and neither is a constant: the collapsed pill hugs
    // its actions, while the expanded panel fills the width available to it. `maxWidth` is
    // that available width; the pill's is only known once its actions have been measured.
    // The actions also clamp against the same fraction (handed to the content below), so
    // this one measurement answers every question about how wide the composer may be.
    BoxWithConstraints(modifier = modifier) {
        val expandedWidth = maxWidth * COLLAPSED_MAX_TOOLBAR_FRACTION

        // Height the expanded content needs, measured unconstrained so it reports what the
        // content wants rather than what the panel currently allows — otherwise the panel
        // could never grow past its own current size.
        var expandedContentHeightPx by remember { mutableIntStateOf(0) }
        // Width the collapsed bar needs, likewise measured rather than assumed.
        var collapsedContentWidthPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val expandedHeight =
            with(density) {
                expandedContentHeightPx.toDp().coerceAtLeast(expandedComposerMinHeight)
            }
        val widthKnown = composerExpanded || collapsedContentWidthPx > 0
        val restingHeight = if (composerExpanded) expandedHeight else collapsedComposerHeight
        // Reports the resting height, not the animated one: the parent turns this into list
        // padding at screen level, so a per-frame value recomposed the whole chat screen and
        // re-laid out the list on every frame of the expand/collapse.
        val restingHeightPx = with(density) { restingHeight.roundToPx() }
        LaunchedEffect(restingHeightPx) { onHeightChanged(restingHeightPx) }
        val animatedHeight by animateDpAsState(
            targetValue = restingHeight,
            // Themed rather than hand-tuned, so the composer moves in step with the rest of
            // the app: a bounds change is spatial motion, so it takes the spatial spec, and
            // the scheme is already installed app-wide in the theme.
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
            label = "ComposerHeight",
        )
        val animatedWidth = rememberPanelWidth(composerExpanded, collapsedContentWidthPx, expandedWidth)

        ComposerPanel(
            composerExpanded = composerExpanded,
            modifier =
                Modifier
                    .height(animatedHeight)
                    .then(
                        if (widthKnown) {
                            Modifier.width(animatedWidth)
                        } else {
                            // Before the bar has been measured, take its width from its
                            // content rather than from a guess.
                            Modifier.widthIn(max = expandedWidth)
                        },
                    ),
            expandedWidth = expandedWidth,
            onExpandedContentHeightPx = { expandedContentHeightPx = it },
            onCollapsedContentWidthPx = { collapsedContentWidthPx = it },
            content = content,
        )
    }
}

@Composable
private fun ComposerPanel(
    composerExpanded: Boolean,
    modifier: Modifier,
    expandedWidth: Dp,
    onExpandedContentHeightPx: (Int) -> Unit,
    onCollapsedContentWidthPx: (Int) -> Unit,
    content: @Composable RowScope.(collapsedMaxToolbarWidth: Dp) -> Unit,
) {
    Surface(
        // Transition between a capsule shape when collapsed and a rounded rectangle when expanded
        shape = if (composerExpanded) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Box(
            // Height only: filling the width would stretch the collapsed pill to the
            // panel's maximum, because the panel's width is content-driven when collapsed.
            modifier = Modifier.fillMaxHeight(),
            // Both resting layouts fill the panel, so this only matters mid-resize. Pinning
            // the collapsed bar to the bottom keeps it at its final position while the panel
            // shrinks, instead of letting it ride the top edge down the screen. The spring's
            // undershoot does squeeze it by a few dp at the tail, which reads as a settle.
            contentAlignment = if (composerExpanded) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            ComposerContentRow(
                composerExpanded = composerExpanded,
                expandedWidth = expandedWidth,
                onExpandedContentHeightPx = onExpandedContentHeightPx,
                onCollapsedContentWidthPx = onCollapsedContentWidthPx,
                content = content,
            )
        }
    }
}

@Composable
private fun ComposerContentRow(
    composerExpanded: Boolean,
    expandedWidth: Dp,
    onExpandedContentHeightPx: (Int) -> Unit,
    onCollapsedContentWidthPx: (Int) -> Unit,
    content: @Composable RowScope.(collapsedMaxToolbarWidth: Dp) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .then(
                    if (composerExpanded) {
                        // Measured unconstrained so the content can report its full
                        // height while the panel is still growing; the excess is
                        // clipped by the panel's shape until the panel catches up.
                        Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
                    } else {
                        // Exactly the resting height, so the bar sits where it will
                        // come to rest rather than stretching to the animating panel.
                        Modifier
                            .height(collapsedComposerHeight)
                            // Unbounded for the same reason as the height above: the
                            // panel's width is this measurement, so a bar measured
                            // under the panel's own width could never report wider than
                            // it already is. Left bounded, the two lock at whichever
                            // width the first frame happened to measure.
                            .wrapContentWidth(unbounded = true)
                    },
                )
                // Placed before the padding so it reports the padded width, which
                // is what the panel itself has to be.
                .onSizeChanged {
                    if (!composerExpanded) onCollapsedContentWidthPx(it.width)
                }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (composerExpanded) {
            val rowScope = this
            Box(modifier = Modifier.fillMaxWidth().onSizeChanged { onExpandedContentHeightPx(it.height) }) {
                with(rowScope) { content(expandedWidth) }
            }
        } else {
            content(expandedWidth)
        }
    }
}

/**
 * UI content for the composer when expanded for text entry.
 */
@Composable
internal fun ExpandedComposerContent(
    messageText: TextFieldState,
    inputAlpha: () -> Float,
    focusRequester: FocusRequester,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    queueing: Boolean,
    onClose: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSend: () -> Unit,
    canSendImages: Boolean,
    selectedImages: List<ChatImageData>,
    onImagesChanged: (List<ChatImageData>) -> Unit,
    canSendFiles: Boolean,
    selectedFiles: List<ChatFileData>,
    onFilesChanged: (List<ChatFileData>) -> Unit,
    onAttach: () -> Unit,
    dropTargetActive: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .graphicsLayer { alpha = inputAlpha() }
                .onEscapeKey(onEscape = onClose)
                // Restates the send button's contract for the keyboard: the button is disabled
                // while streaming and while the field is blank, so the shortcut is too.
                .onSubmitShortcut(
                    enabled = !showStopAction && messageText.text.isNotBlank(),
                    onSubmit = onSend,
                ),
    ) {
        // -- Selected image thumbnails --
        if (selectedImages.isNotEmpty()) {
            ImageThumbnailRow(
                images = selectedImages,
                onRemove = { index -> onImagesChanged(selectedImages.toMutableList().also { it.removeAt(index) }) },
            )
        }
        if (selectedFiles.isNotEmpty()) {
            FileChipRow(
                files = selectedFiles,
                onRemove = { index -> onFilesChanged(selectedFiles.toMutableList().also { it.removeAt(index) }) },
            )
        }
        ExpandedComposerTextField(
            messageText = messageText,
            focusRequester = focusRequester,
            onSend = onSend,
        )
        ExpandedComposerBottomBar(
            onClose = onClose,
            canSendImages = canSendImages,
            canSendFiles = canSendFiles,
            onAttach = onAttach,
            showStopAction = showStopAction,
            canCancelStreaming = canCancelStreaming,
            queueing = queueing,
            messageText = messageText.text,
            onPrimaryAction = onPrimaryAction,
            dropTargetActive = dropTargetActive,
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
    focusRequester: FocusRequester,
    onSend: () -> Unit,
) {
    val selectionColors =
        TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.onPrimary,
            backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
        )
    val decorator =
        remember(messageText) {
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
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                        )
                    }
                    innerTextField()
                }
            }
        }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            state = messageText,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onPrimary,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            // The field's IME action is Send, so every action reported here is a send; the default
            // action (dismissing the keyboard) is deliberately skipped, as the button does not
            // dismiss it either.
            onKeyboardAction = KeyboardActionHandler { onSend() },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
            decorator = decorator,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun AttachComposerButton(
    onAttach: () -> Unit,
    dropTargetActive: Boolean,
) {
    IconButton(onClick = onAttach, modifier = Modifier.handCursor()) {
        Icon(
            imageVector =
                if (dropTargetActive) {
                    Icons.Default.MoveToInbox
                } else {
                    Icons.Default.AttachFile
                },
            contentDescription = stringResource(R.string.chat_attach_desc),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * The bottom action row inside [ExpandedComposerContent], containing the
 * close button, attach-file affordance, and primary action button.
 */
@Composable
private fun ExpandedComposerBottomBar(
    onClose: () -> Unit,
    canSendImages: Boolean,
    canSendFiles: Boolean,
    onAttach: () -> Unit,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    queueing: Boolean,
    messageText: CharSequence,
    onPrimaryAction: () -> Unit,
    dropTargetActive: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.handCursor()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_composer_close_desc),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSendImages || canSendFiles) {
                AttachComposerButton(onAttach = onAttach, dropTargetActive = dropTargetActive)
            }

            PrimaryComposerActionButton(
                showStopAction = showStopAction,
                canCancelStreaming = canCancelStreaming,
                // Only enable send if there's text, or if we are stopping a stream
                enabled = if (showStopAction) canCancelStreaming else messageText.isNotBlank(),
                onClick = onPrimaryAction,
                chatIcon = if (queueing) Icons.AutoMirrored.Filled.ScheduleSend else Icons.Default.ArrowUpward,
            )
        }
    }
}

/**
 * UI content for the composer in its collapsed state, showing quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsedComposerActions(
    state: ChatState,
    toolbarConfigOptions: List<ChatConfigOption>,
    buttonsAlpha: () -> Float,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    stopAside: Boolean,
    collapsedMaxToolbarWidth: Dp,
    showModeMenu: Boolean,
    onShowModeMenuChange: (Boolean) -> Unit,
    modeMenuInteractionSource: MutableInteractionSource,
    showOptionsMenu: Boolean,
    onShowOptionsMenuChange: (Boolean) -> Unit,
    optionsMenuInteractionSource: MutableInteractionSource,
    onExpandComposer: () -> Unit,
    onCancelStreaming: () -> Unit,
    onSetStringConfigOption: (String, String) -> Unit,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
    showJumpToBottom: Boolean,
    onJumpToBottom: () -> Unit,
    dropTargetActive: Boolean,
) {
    if (showModeButton && modeOption != null) {
        ModeMenuButton(
            modifier = Modifier.graphicsLayer { alpha = buttonsAlpha() },
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

    CollapsedPrimaryButton(
        showStopAction = showStopAction,
        canCancelStreaming = canCancelStreaming,
        buttonsAlpha = buttonsAlpha,
        onCancelStreaming = onCancelStreaming,
        onExpandComposer = onExpandComposer,
        dropTargetActive = dropTargetActive,
    )

    AnimatedVisibility(visible = stopAside, enter = toolbarActionEnter, exit = toolbarActionExit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.width(6.dp))
            AsideStopButton(canCancelStreaming = canCancelStreaming, onCancelStreaming = onCancelStreaming)
        }
    }

    AnimatedVisibility(visible = showJumpToBottom, enter = toolbarActionEnter, exit = toolbarActionExit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(6.dp))
            JumpToBottomButton(onJumpToBottom = onJumpToBottom)
        }
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

/**
 * The primary action button in the collapsed composer, wrapped in a [TooltipBox]
 * that shows the current mode label ("Stop", "Cancel unavailable", or "Chat").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsedPrimaryButton(
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    buttonsAlpha: () -> Float,
    onCancelStreaming: () -> Unit,
    onExpandComposer: () -> Unit,
    dropTargetActive: Boolean,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    when {
                        showStopAction && canCancelStreaming -> stringResource(R.string.chat_stop)
                        showStopAction && !canCancelStreaming -> stringResource(R.string.chat_cancel_unavailable)
                        else -> stringResource(R.string.chat_chat)
                    },
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        PrimaryComposerActionButton(
            showStopAction = showStopAction,
            canCancelStreaming = canCancelStreaming,
            enabled = !showStopAction || canCancelStreaming,
            modifier =
                Modifier
                    .graphicsLayer { alpha = buttonsAlpha() }
                    .size(
                        IconButtonDefaults.smallContainerSize(
                            IconButtonDefaults.IconButtonWidthOption.Wide,
                        ),
                    ),
            onClick = {
                if (showStopAction && canCancelStreaming) {
                    onCancelStreaming()
                } else if (!showStopAction) {
                    onExpandComposer()
                }
            },
            chatIcon = if (dropTargetActive) Icons.Default.MoveToInbox else Icons.Default.Edit,
        )
    }
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

/** Stop as a plain toolbar action, for agents whose primary action stays on writing mid-turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsideStopButton(
    canCancelStreaming: Boolean,
    onCancelStreaming: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    stringResource(if (canCancelStreaming) R.string.chat_stop else R.string.chat_cancel_unavailable),
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onCancelStreaming,
            enabled = canCancelStreaming,
            modifier = Modifier.handCursor(enabled = canCancelStreaming),
        ) {
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
    canCancelStreaming: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    chatIcon: ImageVector = Icons.Default.ArrowUpward,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.onPrimary,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.handCursor(enabled = enabled),
    ) {
        val stopping = showStopAction && canCancelStreaming
        Crossfade(targetState = if (stopping) Icons.Default.Stop else chatIcon, label = "PrimaryComposerIcon") {
            Icon(
                imageVector = it,
                contentDescription =
                    stringResource(if (stopping) R.string.chat_stop_desc else R.string.chat_send_desc),
            )
        }
    }
}
