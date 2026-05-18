@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.acp.bridge.session.allChoices
import com.tamimarafat.ferngeist.acp.bridge.session.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.ChatState

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
    toolbarConfigOptions: List<SessionConfigOption>,
    composerExpanded: Boolean,
    onComposerExpandedChange: (Boolean) -> Unit,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: SessionConfigOption.Select?,
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
) {
    var showModeMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val modeMenuInteractionSource = remember { MutableInteractionSource() }
    val optionsMenuInteractionSource = remember { MutableInteractionSource() }

    // Animates height between collapsed and expanded states
    val animatedHeight by animateDpAsState(
        targetValue = if (composerExpanded) 142.dp else 62.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "ComposerHeight",
    )
    val collapsedMaxToolbarWidth = screenWidth * 0.92f

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
                        Modifier.fillMaxWidth(0.92f)
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
            if (composerExpanded) {
                ExpandedComposerContent(
                    messageText = messageText,
                    onMessageTextChange = onMessageTextChange,
                    inputAlpha = inputAlpha,
                    focusRequester = focusRequester,
                    showStopAction = showStopAction,
                    canCancelStreaming = canCancelStreaming,
                    onClose = {
                        onComposerExpandedChange(false)
                        onFocusCleared()
                    },
                    onPrimaryAction = {
                        // Primary action depends on whether we are currently streaming
                        if (showStopAction && canCancelStreaming) {
                            onCancelStreaming()
                        } else if (!showStopAction) {
                            onSend()
                        }
                    },
                    onSend = onSend,
                )
            } else {
                CollapsedComposerActions(
                    state = state,
                    toolbarConfigOptions = toolbarConfigOptions,
                    buttonsAlpha = buttonsAlpha,
                    showModeButton = showModeButton,
                    modeOption = modeOption,
                    currentModeLabel = currentModeLabel,
                    showStopAction = showStopAction,
                    canCancelStreaming = canCancelStreaming,
                    collapsedMaxToolbarWidth = collapsedMaxToolbarWidth,
                    showModeMenu = showModeMenu,
                    onShowModeMenuChange = { showModeMenu = it },
                    modeMenuInteractionSource = modeMenuInteractionSource,
                    showOptionsMenu = showOptionsMenu,
                    onShowOptionsMenuChange = { showOptionsMenu = it },
                    optionsMenuInteractionSource = optionsMenuInteractionSource,
                    onExpandComposer = { onComposerExpandedChange(true) },
                    onCancelStreaming = onCancelStreaming,
                    onSetStringConfigOption = onSetStringConfigOption,
                    onSetBooleanConfigOption = onSetBooleanConfigOption,
                    onShowCommands = onShowCommands,
                    onShowConfigOptionPicker = onShowConfigOptionPicker,
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
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputAlpha: Float,
    focusRequester: FocusRequester,
    showStopAction: Boolean,
    canCancelStreaming: Boolean,
    onClose: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .alpha(inputAlpha),
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
    toolbarConfigOptions: List<SessionConfigOption>,
    buttonsAlpha: Float,
    showModeButton: Boolean,
    modeOption: SessionConfigOption.Select?,
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
                Modifier.size(
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
 * A dropdown menu button for selecting the chat mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeMenuButton(
    modifier: Modifier = Modifier,
    currentModeLabel: String,
    modeOption: SessionConfigOption.Select,
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
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
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
    }
}

/**
 * A dropdown button containing various session options and commands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarOptionsButton(
    commandsAdvertised: Boolean,
    configOptions: List<SessionConfigOption>,
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
                                    is SessionConfigOption.BooleanOption -> {
                                        onExpandedChange(false)
                                        onSetBooleanConfigOption(option.id, !option.currentValue)
                                    }

                                    is SessionConfigOption.Select -> {
                                        onExpandedChange(false)
                                        onShowConfigOptionPicker(option.id)
                                    }

                                    is SessionConfigOption.Unknown -> Unit
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
        }
    }
}

/**
 * A menu item for a specific [SessionConfigOption].
 * Displays the option name and its current status/value.
 */
@Composable
internal fun ConfigOptionMenuItem(
    option: SessionConfigOption,
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
        enabled = option is SessionConfigOption.Select || option is SessionConfigOption.BooleanOption,
    )
}

/**
 * Extension to get a user-friendly subtitle for a config option in the dropdown.
 */
@Composable
internal fun SessionConfigOption.dropdownSubtitle(): String =
    when (this) {
        is SessionConfigOption.BooleanOption -> if (currentValue) stringResource(R.string.chat_enabled) else stringResource(R.string.chat_disabled)
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
            contentDescription = if (showStopAction && canCancelStreaming) stringResource(R.string.chat_stop_desc) else stringResource(R.string.chat_send_desc),
        )
    }
}
