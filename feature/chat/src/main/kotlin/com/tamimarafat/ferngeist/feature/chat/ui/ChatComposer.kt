@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R

private const val COLLAPSED_MAX_TOOLBAR_FRACTION = 0.92f

/**
 * Computes the target height for the composer based on expansion and attachment state.
 */
private fun composerTargetHeight(
    composerExpanded: Boolean,
    selectedImages: List<ChatImageData>,
    selectedFiles: List<ChatFileData>,
): Dp = when {
    composerExpanded && (selectedImages.isNotEmpty() || selectedFiles.isNotEmpty()) -> 210.dp
    composerExpanded -> 142.dp
    else -> 62.dp
}

/**
 * Main entry point for the chat composer UI.
 * This bar can be in a collapsed state (showing actions/modes) or an expanded state (for typing).
 *
 * @param state The current chat UI state.
 * @param toolbarConfigOptions Configuration options to be displayed in the options menu.
 * @param composerExpanded Whether the composer is currently expanded for text entry.
 * @param onComposerExpandedChange Callback when the expansion state changes.
 * @param messageText Current text in the composer.
 * @param onMessageTextChange Callback for text changes.
 * @param inputAlpha Alpha value for the text input area (usually for fading during transitions).
 * @param buttonsAlpha Alpha value for the buttons in collapsed state.
 * @param showModeButton Whether to show the mode selection button.
 * @param modeOption The specific select option for modes.
 * @param currentModeLabel The human-readable label for the current mode.
 * @param showStopAction Whether to show the "Stop" button instead of send/expand.
 * @param canCancelStreaming Whether the current stream can actually be canceled.
 * @param screenWidth Total screen width for calculating layout bounds.
 * @param focusRequester To request focus when expanding.
 * @param onFocusCleared Callback when focus is cleared (e.g., closing the composer).
 * @param onHeightChanged Callback to report the current height of the composer to the parent.
 * @param onSend Action to send the current message.
 * @param onCancelStreaming Action to stop the current generation.
 * @param onSetStringConfigOption Callback to update a string-based config option.
 * @param onSetBooleanConfigOption Callback to update a boolean config option.
 * @param onShowCommands Callback to show available commands.
 * @param onShowConfigOptionPicker Callback to show a dedicated picker for a config option.
 */
@Composable
internal fun ChatComposerBar(
    modifier: Modifier = Modifier,
    state: ChatState,
    toolbarConfigOptions: List<ChatConfigOption>,
    composerExpanded: Boolean,
    onComposerExpandedChange: (Boolean) -> Unit,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    screenWidth: Dp,
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
) {
    var showModeMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }

    val animatedHeight by animateDpAsState(
        targetValue = composerTargetHeight(composerExpanded, selectedImages, selectedFiles),
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "ComposerHeight",
    )
    val collapsedMaxToolbarWidth = screenWidth * COLLAPSED_MAX_TOOLBAR_FRACTION

    ComposerSurfaceContainer(
        modifier = modifier, composerExpanded = composerExpanded,
        animatedHeight = animatedHeight, collapsedMaxToolbarWidth = collapsedMaxToolbarWidth,
        onHeightChanged = onHeightChanged,
    ) {
        if (composerExpanded) {
            ExpandedComposerContent(
                messageText, onMessageTextChange, inputAlpha, focusRequester, showStopAction,
                canCancelStreaming,
                { onComposerExpandedChange(false); onFocusCleared() },
                { if (showStopAction && canCancelStreaming) onCancelStreaming(); else if (!showStopAction) onSend() },
                onSend, canSendImages, selectedImages, onImagesChanged, canSendFiles,
                selectedFiles, onFilesChanged, onAttach,
            )
        } else {
            CollapsedComposerActions(
                state, toolbarConfigOptions, buttonsAlpha, showModeButton, modeOption,
                currentModeLabel, showStopAction, canCancelStreaming, collapsedMaxToolbarWidth,
                showModeMenu, { showModeMenu = it }, modeMenuInteractionSource,
                showOptionsMenu, { showOptionsMenu = it }, optionsMenuInteractionSource,
                { onComposerExpandedChange(true) }, onCancelStreaming, onSetStringConfigOption,
                onSetBooleanConfigOption, onShowCommands, onShowConfigOptionPicker,
                showJumpToBottom, onJumpToBottom,
            )
        }
    }
}

/**
 * The Surface + Row container that wraps either expanded or collapsed composer
 * content, switching based on [composerExpanded].
 */
@Composable
private fun ComposerSurfaceContainer(
    modifier: Modifier,
    composerExpanded: Boolean,
    animatedHeight: Dp,
    collapsedMaxToolbarWidth: Dp,
    onHeightChanged: (Int) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        // Transition between a capsule shape when collapsed and a rounded rectangle when expanded
        shape = if (composerExpanded) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier =
            modifier
                .height(animatedHeight)
                .then(
                    if (composerExpanded) {
                        Modifier.fillMaxWidth(COLLAPSED_MAX_TOOLBAR_FRACTION)
                    } else {
                        // Limit width in collapsed state to maintain "pill" look on wide screens
                        Modifier.widthIn(max = collapsedMaxToolbarWidth)
                    },
                ).onSizeChanged { onHeightChanged(it.height) },
    ) {
        Row(
            modifier =
                Modifier
                    .then(
                        if (composerExpanded) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxHeight()
                                .wrapContentWidth()
                        },
                    ).animateContentSize(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    ).padding(horizontal = 12.dp),
            verticalAlignment = if (composerExpanded) Alignment.Bottom else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

/**
 * UI content for the composer when expanded for text entry.
 */
@Composable
internal fun ExpandedComposerContent(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    focusRequester: FocusRequester,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
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
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .alpha(inputAlpha),
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
            onMessageTextChange = onMessageTextChange,
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
            messageText = messageText,
            onPrimaryAction = onPrimaryAction,
        )
    }
}

/**
 * The text input area inside [ExpandedComposerContent], including
 * selection-colors and the placeholder hint.
 */
@Composable
private fun ColumnScope.ExpandedComposerTextField(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
) {
    val selectionColors =
        TextSelectionColors(
            handleColor = MaterialTheme.colorScheme.onPrimary,
            backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
        )
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = messageText,
            onValueChange = onMessageTextChange,
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onPrimary,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (messageText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_composer_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                        )
                    }
                    innerTextField()
                }
            },
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
    messageText: String,
    onPrimaryAction: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_composer_close_desc),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canSendImages || canSendFiles) {
                IconButton(onClick = onAttach) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.chat_attach_desc),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            PrimaryComposerActionButton(
                showStopAction = showStopAction,
                canCancelStreaming = canCancelStreaming,
                // Only enable send if there's text, or if we are stopping a stream
                enabled = if (showStopAction) canCancelStreaming else messageText.isNotBlank(),
                onClick = onPrimaryAction,
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
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: ChatConfigOption.Select?,
    currentModeLabel: String,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
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
) {
    if (showModeButton && modeOption != null) {
        ModeMenuButton(
            modifier = Modifier.alpha(buttonsAlpha),
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
    )

    AnimatedVisibility(
        visible = showJumpToBottom,
        enter =
            fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                scaleIn(initialScale = 0.6f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                expandHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
        exit =
            fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
                scaleOut(targetScale = 0.6f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                shrinkHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMedium)),
    ) {
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
    buttonsAlpha: Float,
    onCancelStreaming: () -> Unit,
    onExpandComposer: () -> Unit,
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
                    .alpha(buttonsAlpha)
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
            chatIcon = Icons.Default.Edit,
        )
    }
}

/**
 * Jump-to-bottom scroll button wrapped in a [TooltipBox].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpToBottomButton(
    onJumpToBottom: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.chat_scroll_to_bottom))
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onJumpToBottom) {
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
            )
        }
    }
}

/**
 * A dropdown menu button for selecting the chat mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeMenuButton(
    modifier: Modifier = Modifier,
    currentModeLabel: String,
    modeOption: ChatConfigOption.Select,
    maxWidth: Dp,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetConfigOption: (String, String) -> Unit,
) {
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(stringResource(R.string.chat_mode)) } },
            state = rememberTooltipState(),
        ) {
            TextButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.widthIn(max = maxWidth),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = Color.Transparent,
                    ),
            ) {
                Text(
                    text = currentModeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            ModeMenuContent(
                modeOption = modeOption,
                onExpandedChange = onExpandedChange,
                interactionSource = interactionSource,
                onSetConfigOption = onSetConfigOption,
            )
        }
    }
}

/**
 * The dropdown content inside [ModeMenuButton], rendering each available mode
 * as a [DropdownMenuItem] with optional tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeMenuContent(
    modeOption: ChatConfigOption.Select,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetConfigOption: (String, String) -> Unit,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(0, 1),
        interactionSource = interactionSource,
    ) {
        val availableModes = modeOption.allChoices()
        val modeCount = availableModes.size
        if (modeCount == 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_no_modes_available)) },
                onClick = { onExpandedChange(false) },
                enabled = false,
            )
        } else {
            availableModes.forEachIndexed { index, mode ->
                val item: @Composable () -> Unit = {
                    DropdownMenuItem(
                        text = { Text(mode.label.uppercase()) },
                        shapes = MenuDefaults.itemShape(index, modeCount),
                        checked = mode.value == modeOption.currentValue,
                        onCheckedChange = { checked ->
                            if (checked && mode.value != modeOption.currentValue) {
                                onExpandedChange(false)
                                onSetConfigOption(modeOption.id, mode.value)
                            }
                        },
                    )
                }
                val description = mode.description
                if (!description.isNullOrBlank()) {
                    TooltipBox(
                        positionProvider =
                            TooltipDefaults.rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above,
                            ),
                        tooltip = { PlainTooltip { Text(description) } },
                        state = rememberTooltipState(),
                    ) { item() }
                } else {
                    item()
                }
            }
        }
    }
}

/**
 * A dropdown button containing various session options and commands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarOptionsButton(
    commandsAdvertised: Boolean,
    configOptions: List<ChatConfigOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(R.string.chat_options)) } },
        state = rememberTooltipState(),
    ) {
        Box {
            IconButton(onClick = { onExpandedChange(true) }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.chat_options),
                )
            }
            DropdownMenuPopup(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                ToolbarOptionsMenuContent(
                    commandsAdvertised = commandsAdvertised,
                    configOptions = configOptions,
                    onExpandedChange = onExpandedChange,
                    interactionSource = interactionSource,
                    onSetBooleanConfigOption = onSetBooleanConfigOption,
                    onShowCommands = onShowCommands,
                    onShowConfigOptionPicker = onShowConfigOptionPicker,
                )
            }
        }
    }
}

/**
 * The dropdown menu content inside [ToolbarOptionsButton], rendering the
 * commands, config options, and the fallback empty-state item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarOptionsMenuContent(
    commandsAdvertised: Boolean,
    configOptions: List<ChatConfigOption>,
    onExpandedChange: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource,
    onSetBooleanConfigOption: (String, Boolean) -> Unit,
    onShowCommands: () -> Unit,
    onShowConfigOptionPicker: (String) -> Unit,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(0, 1),
        interactionSource = interactionSource,
    ) {
        val hasConfigOptions = configOptions.isNotEmpty()
        if (commandsAdvertised) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_commands)) },
                onClick = {
                    onExpandedChange(false)
                    onShowCommands()
                },
            )
        }

        configOptions.forEach { option ->
            ConfigOptionMenuItem(
                option = option,
                onClick = {
                    when (option) {
                        is ChatConfigOption.BooleanOption -> {
                            onExpandedChange(false)
                            onSetBooleanConfigOption(option.id, !option.currentValue)
                        }

                        is ChatConfigOption.Select -> {
                            onExpandedChange(false)
                            onShowConfigOptionPicker(option.id)
                        }

                        is ChatConfigOption.Unknown -> Unit
                    }
                },
            )
        }

        if (!commandsAdvertised && !hasConfigOptions) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_no_options_available)) },
                onClick = { onExpandedChange(false) },
                enabled = false,
            )
        }
    }
}

/**
 * A menu item for a specific [SessionConfigOption].
 * Displays the option name and its current status/value.
 */
@Composable
internal fun ConfigOptionMenuItem(
    option: ChatConfigOption,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(option.name)
                Text(
                    text = option.dropdownSubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onClick,
        enabled = option is ChatConfigOption.Select || option is ChatConfigOption.BooleanOption,
    )
}

/**
 * Extension to get a user-friendly subtitle for a config option in the dropdown.
 */
@Composable
internal fun ChatConfigOption.dropdownSubtitle(): String =
    when (this) {
        is ChatConfigOption.BooleanOption ->
            if (currentValue) {
                stringResource(
                    R.string.chat_enabled,
                )
            } else {
                stringResource(R.string.chat_disabled)
            }
        else -> displayValueLabel() ?: stringResource(R.string.chat_not_selected)
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
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (showStopAction && canCancelStreaming) Icons.Default.Stop else chatIcon,
            contentDescription =
                if (showStopAction &&
                    canCancelStreaming
                ) {
                    stringResource(R.string.chat_stop_desc)
                } else {
                    stringResource(R.string.chat_send_desc)
                },
        )
    }
}

/**
 * Horizontal scrolling row of attached-image thumbnails, each with a remove (x) affordance.
 */
@Composable
private fun ImageThumbnailRow(
    images: List<ChatImageData>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(
            items = images,
            key = { _, image -> image.base64 },
        ) { index, image ->
            ImageThumbnailItem(
                image = image,
                onRemove = { onRemove(index) },
            )
        }
    }
}

/**
 * Horizontal scrolling row of attached-file chips, each with a remove (x) affordance.
 */
@Composable
private fun FileChipRow(
    files: List<ChatFileData>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(
            items = files,
            key = { index, file -> "${file.name}:$index" },
        ) { index, file ->
            FileChipItem(file = file, onRemove = { onRemove(index) })
        }
    }
}

@Composable
private fun FileChipItem(
    file: ChatFileData,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
        modifier = modifier.height(56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.widthIn(max = 160.dp)) {
                Text(
                    text = file.name,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = FileAttachmentHelper.formatSize(file.sizeBytes),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_remove_file_desc),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun ImageThumbnailItem(
    image: ChatImageData,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val bitmap =
            remember(image.base64) {
                runCatching {
                    val bytes = Base64.decode(image.base64, Base64.DEFAULT)
                    BitmapFactory
                        .decodeByteArray(bytes, 0, bytes.size)
                        ?.asImageBitmap()
                }.getOrNull()
            }

        ThumbnailImageContent(bitmap = bitmap)

        // Remove button — positioned top-end of the thumbnail
        ThumbnailRemoveButton(onRemove = onRemove)
    }
}

/**
 * The thumbnail image surface inside [ImageThumbnailItem], showing either the
 * decoded bitmap or a placeholder icon when decoding fails.
 */
@Composable
private fun ThumbnailImageContent(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
        modifier = modifier.size(56.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image_desc),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The remove (x) button overlay for [ImageThumbnailItem], positioned at the
 * top-end of the thumbnail.
 */
@Composable
private fun BoxScope.ThumbnailRemoveButton(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
        modifier =
            modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(18.dp),
    ) {
        IconButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_remove_image_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
