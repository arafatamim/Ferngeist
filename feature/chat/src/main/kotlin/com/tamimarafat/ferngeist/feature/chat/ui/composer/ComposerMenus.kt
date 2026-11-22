package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentLogo
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.R

internal val composerModelButtonMinWidth = 160.dp

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
                modifier = Modifier.widthIn(max = maxWidth).handCursor(),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = Color.Transparent,
                    ),
            ) {
                // The bar, and the panel hugging it, take their width from this label, so sizing
                // the swap here is what makes the whole pill resize smoothly on a mode change.
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
                val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                AnimatedContent(
                    targetState = currentModeLabel,
                    transitionSpec = {
                        (fadeIn(effects) + slideInVertically { it / 2 })
                            .togetherWith(fadeOut(effects) + slideOutVertically { -it / 2 })
                            .using(SizeTransform { _, _ -> spatial })
                    },
                    label = "ModeLabel",
                ) { label ->
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
internal fun ModeMenuContent(
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
                    CheckableDropdownMenuItem(
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
            IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.handCursor()) {
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
internal fun ToolbarOptionsMenuContent(
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

/** The first select option of [category] that has something to pick. */
internal fun List<ChatConfigOption>.selectOptionFor(category: ChatConfigCategory): ChatConfigOption.Select? =
    filterIsInstance<ChatConfigOption.Select>().firstOrNull { it.category == category && it.allChoices().isNotEmpty() }

/**
 * The model and its reasoning effort as an M3 split button: the leading button picks the
 * model, the trailing chevron the effort. Without an effort option it is the leading half alone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModelEffortButton(
    agentIconUrl: String?,
    agentName: String?,
    modelOption: ChatConfigOption.Select,
    effortOption: ChatConfigOption.Select?,
    onSetConfigOption: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }
    val colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    val modelButton: @Composable () -> Unit = {
        Box {
            val label: @Composable RowScope.() -> Unit = { ModelLabel(agentIconUrl, agentName, modelOption) }
            if (effortOption == null) {
                FilledTonalButton(
                    onClick = { modelMenuOpen = true },
                    shapes = ButtonDefaults.shapes(),
                    colors = colors,
                    modifier = Modifier.handCursor(),
                    content = label,
                )
            } else {
                SplitButtonDefaults.TonalLeadingButton(
                    onClick = { modelMenuOpen = true },
                    colors = colors,
                    contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
                    modifier = Modifier.widthIn(min = composerModelButtonMinWidth).handCursor(),
                    content = label,
                )
            }
            ConfigChoiceMenu(modelOption, modelMenuOpen, { modelMenuOpen = false }, onSetConfigOption)
        }
    }
    if (effortOption == null) {
        Box(modifier = modifier) { modelButton() }
        return
    }
    var effortMenuOpen by remember { mutableStateOf(false) }
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = modelButton,
        trailingButton = {
            Box {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = effortMenuOpen,
                    onCheckedChange = { effortMenuOpen = it },
                    colors = colors,
                    modifier = Modifier.handCursor(),
                ) {
                    // Spec: the menu chevron turns on the standard scheme, not the expressive one.
                    val rotation by animateFloatAsState(
                        targetValue = if (effortMenuOpen) 180f else 0f,
                        animationSpec = MotionScheme.standard().defaultSpatialSpec(),
                        label = "EffortChevron",
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_effort_desc),
                        modifier =
                            Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = rotation },
                    )
                }
                ConfigChoiceMenu(effortOption, effortMenuOpen, { effortMenuOpen = false }, onSetConfigOption)
            }
        },
    )
}

/** The leading button's face: the agent's mark beside the model's name. */
@Composable
private fun ModelLabel(
    agentIconUrl: String?,
    agentName: String?,
    modelOption: ChatConfigOption.Select,
) {
    AgentLogo(
        iconUrl = agentIconUrl,
        fallback = Icons.Rounded.SmartToy,
        size = SplitButtonDefaults.LeadingIconSize,
        fallbackName = agentName,
    )
    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
    Text(
        text = modelOption.displayValueLabel() ?: modelOption.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One option's choices as a labelled, checkable menu, anchored to the button that opened it.
 * The popup lines up with one of that button's edges and flips when there is no room.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConfigChoiceMenu(
    option: ChatConfigOption.Select,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSetConfigOption: (String, String) -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Spec: the menu sits 4dp from the split button.
        // Prefer above the button and let the popup flip near the top of the screen.
        popupPositionProvider =
            MenuDefaults.rememberDropdownMenuPopupPositionProvider(
                dropdownMenuAnchorPosition = MenuAnchorPosition.Above,
                offset = DpOffset(0.dp, 4.dp),
            ),
    ) {
        // Model lists run long; the popup itself does not scroll.
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        ) {
            MenuDefaults.DropdownMenuGroupLabel { Text(option.name) }
            val choices = option.allChoices()
            choices.forEachIndexed { index, choice ->
                CheckableDropdownMenuItem(
                    text = { Text(choice.label) },
                    shapes = MenuDefaults.itemShape(index, choices.size),
                    checked = choice.value == option.currentValue,
                    onCheckedChange = { checked ->
                        if (checked && choice.value != option.currentValue) {
                            onDismiss()
                            onSetConfigOption(option.id, choice.value)
                        }
                    },
                )
            }
        }
    }
}
