package com.tamimarafat.ferngeist.feature.chat.ui.composer.model

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentLogo
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.core.model.displayValueLabel
import com.tamimarafat.ferngeist.feature.chat.R

/**
 * The model and its reasoning effort behind one button. The sheet it opens searches the models,
 * puts the recently picked ones first, and pins the effort row beneath the list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModelEffortButton(
    agentIconUrl: String?,
    agentName: String?,
    modelOption: ChatConfigOption.Select,
    effortOption: ChatConfigOption.Select?,
    recentModelValues: List<String>,
    onSetConfigOption: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = { open = true },
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = modifier.handCursor(),
    ) {
        AgentLogo(
            iconUrl = agentIconUrl,
            fallback = Icons.Rounded.SmartToy,
            size = ButtonDefaults.IconSize,
            fallbackName = agentName,
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            text = modelOption.displayValueLabel() ?: modelOption.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        effortOption?.displayValueLabel()?.let { effort ->
            Text(text = " · $effort", color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
    if (open) {
        ModelEffortSheet(modelOption, effortOption, recentModelValues, onSetConfigOption) { open = false }
    }
}

/** The model list over the effort row; the composer's model button and the options menu both open it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelEffortSheet(
    modelOption: ChatConfigOption.Select,
    effortOption: ChatConfigOption.Select?,
    recentModelValues: List<String>,
    onSetConfigOption: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    val dismiss = { scope.dismissSheet(sheetState, onDismiss) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    // Key events reach the focus target and its ancestors only, so the sheet holds
                    // focus itself for when the search field is absent.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { dismiss() },
        ) {
            ModelList(
                option = modelOption,
                recentValues = recentModelValues,
                onPick = { choice ->
                    onSetConfigOption(modelOption.id, choice.value)
                    // With no effort to set next, the pick is the whole errand.
                    if (effortOption == null) dismiss()
                },
            )
            effortOption?.let { effort ->
                SectionLabel(effort.name)
                EffortButtonGroup(effort, onSetConfigOption)
            }
        }
    }
}

/** Search above a scrolling list of the recent models and then each provider's group. */
@Composable
private fun ColumnScope.ModelList(
    option: ChatConfigOption.Select,
    recentValues: List<String>,
    onPick: (ChatConfigChoice) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sections = remember(option, recentValues, query) { option.modelMenuSections(query, recentValues) }
    if (option.allChoices().size >= SEARCH_THRESHOLD) {
        SheetSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.chat_search_placeholder, option.name),
        )
    }
    // Takes what the sheet has left, so the effort row below stays on screen.
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (sections.recent.isNotEmpty()) {
            SectionLabel(stringResource(R.string.chat_recent))
            ChoiceList(sections.recent, option.currentValue, onPick)
        }
        // Folding only helps when there is more than one provider and no search narrowing them.
        if (query.isBlank() && sections.groups.size > 1) {
            FoldableProviderGroups(option, sections.groups, onPick)
        } else {
            // An unlabelled list still needs a heading to part it from the recents above.
            val fallbackLabel = stringResource(R.string.chat_all_items).takeIf { sections.recent.isNotEmpty() }
            sections.groups.forEach { group ->
                (group.label ?: fallbackLabel)?.let { SectionLabel(it) }
                ChoiceList(group.choices, option.currentValue, onPick)
            }
        }
        if (sections.recent.isEmpty() && sections.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_picker_no_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** Each provider folded behind its label, except the one holding the current model. */
@Composable
private fun FoldableProviderGroups(
    option: ChatConfigOption.Select,
    groups: List<ChatConfigChoiceGroup>,
    onPick: (ChatConfigChoice) -> Unit,
) {
    var expandedIds by remember(option.id) {
        mutableStateOf(groups.filter { g -> g.choices.any { it.value == option.currentValue } }.map { it.id }.toSet())
    }
    groups.forEach { group ->
        val expanded = group.id in expandedIds
        FoldHeader(group.label ?: option.name, group.choices.size, expanded) {
            expandedIds = if (expanded) expandedIds - group.id else expandedIds + group.id
        }
        if (expanded) ChoiceList(group.choices, option.currentValue, onPick)
    }
}

/** One run of choices as a connected segmented list; the current one is selected and checked. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChoiceList(
    choices: List<ChatConfigChoice>,
    currentValue: String?,
    onPick: (ChatConfigChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        choices.forEachIndexed { index, choice ->
            val current = choice.value == currentValue
            SegmentedListItem(
                selected = current,
                onClick = { if (!current) onPick(choice) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = choices.size),
                trailingContent =
                    if (current) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                supportingContent =
                    choice.description?.let { description ->
                        { Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                modifier = Modifier.handCursor(),
            ) {
                Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun FoldHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // Spec: a disclosure chevron turns on the standard scheme, not the expressive one.
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionScheme.standard().defaultSpatialSpec(),
        label = "GroupFoldChevron",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .handCursor()
                .clickable(onClick = onToggle)
                .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

/** A pill search field, focused on open: picking a known model by name is the common case. */
@Composable
private fun SheetSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_search_desc)) },
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        // Matches for a flat list have no heading above them, so the field keeps its own gap.
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(focus),
    )
}

/** The effort choices as a connected button group; picking one keeps the sheet open. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EffortButtonGroup(
    effortOption: ChatConfigOption.Select,
    onSetConfigOption: (String, String) -> Unit,
) {
    val choices = effortOption.allChoices()
    // shortcut: every level shares one row, so labels ellipsize past ~6 levels; wrap to a FlowRow if agents ship more.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, choice ->
            val checked = choice.value == effortOption.currentValue
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (it && !checked) onSetConfigOption(effortOption.id, choice.value) },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f).handCursor().semantics { role = Role.RadioButton },
            ) {
                Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val SEARCH_THRESHOLD = 10

/** What the model sheet lists: [recent] picks first, then each provider's [groups]. */
internal data class ModelMenuSections(
    val recent: List<ChatConfigChoice>,
    val groups: List<ChatConfigChoiceGroup>,
)

/**
 * Splits the choices for the sheet. A blank [query] lists [recentValues] still on offer ahead of
 * every group, and short lists skip recents since they fit on screen anyway; a query drops the
 * recents and keeps only the matching choices, hiding the groups it empties.
 */
internal fun ChatConfigOption.Select.modelMenuSections(
    query: String,
    recentValues: List<String>,
): ModelMenuSections {
    val all = allChoices()
    val groups = groups.ifEmpty { listOf(ChatConfigChoiceGroup(id = id, choices = choices)) }
    val q = query.trim()
    if (q.isEmpty()) {
        val recent = recentValues.mapNotNull { v -> all.firstOrNull { it.value == v } }
        return ModelMenuSections(if (all.size < SEARCH_THRESHOLD) emptyList() else recent, groups)
    }
    val matching =
        groups
            .map { group ->
                group.copy(
                    choices =
                        group.choices.filter {
                            it.label.contains(q, ignoreCase = true) ||
                                it.value.contains(q, ignoreCase = true) ||
                                it.description?.contains(q, ignoreCase = true) == true
                        },
                )
            }.filter { it.choices.isNotEmpty() }
    return ModelMenuSections(emptyList(), matching)
}
