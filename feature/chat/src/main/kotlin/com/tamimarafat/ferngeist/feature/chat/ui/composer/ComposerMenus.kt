package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatConfigCategory
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.R

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
            // Decided from the space the bar offers, not the label's own drawn size: an overflow
            // check would flip back and forth as the icon frees the room the label then needs.
            BoxWithConstraints {
                val labelWidth =
                    with(LocalDensity.current) {
                        rememberTextMeasurer()
                            .measure(currentModeLabel, MaterialTheme.typography.labelLarge, maxLines = 1)
                            .size.width
                            .toDp()
                    }
                val labelFits =
                    labelWidth + ButtonDefaults.TextButtonContentPadding.horizontalSum() <=
                        minOf(this.maxWidth, maxWidth)
                ModeTextButton(
                    label = currentModeLabel,
                    // Narrow bars show the mode as an icon; the menu still names every mode.
                    iconOnly = !labelFits,
                    maxWidth = maxWidth,
                    onClick = { onExpandedChange(true) },
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

/** The mode button's face: the mode's name, or [Icons.Rounded.Tune] named after it when [iconOnly]. */
@Composable
private fun ModeTextButton(
    label: String,
    iconOnly: Boolean,
    maxWidth: Dp,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
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
            targetState = label.takeUnless { iconOnly },
            transitionSpec = {
                (fadeIn(effects) + slideInVertically { it / 2 })
                    .togetherWith(fadeOut(effects) + slideOutVertically { -it / 2 })
                    .using(SizeTransform { _, _ -> spatial })
            },
            label = "ModeLabel",
        ) { shown ->
            if (shown == null) {
                Icon(Icons.Rounded.Tune, contentDescription = label)
            } else {
                Text(text = shown, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun PaddingValues.horizontalSum(): Dp =
    calculateLeftPadding(LayoutDirection.Ltr) + calculateRightPadding(LayoutDirection.Ltr)

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
    effortOption: ChatConfigOption.Select?,
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
                    effortOption = effortOption,
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
 * commands, config options, and the fallback empty-state item. The model item carries
 * [effortOption] too: both are set in one sheet, so the menu lists them as one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarOptionsMenuContent(
    commandsAdvertised: Boolean,
    configOptions: List<ChatConfigOption>,
    effortOption: ChatConfigOption.Select?,
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
            val subtitle = option.dropdownSubtitle()
            ConfigOptionMenuItem(
                option = option,
                subtitle =
                    effortOption
                        ?.takeIf { option.category == ChatConfigCategory.Model }
                        ?.let { "$subtitle · ${it.dropdownSubtitle()}" }
                        ?: subtitle,
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
    subtitle: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(option.name)
                Text(
                    text = subtitle,
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
