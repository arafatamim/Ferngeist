package com.tamimarafat.ferngeist.feature.chat.ui.composer.model

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentLogo
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.edgeFadeMask
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
    // No partial stop: the sheet opens at full height so the list and effort row show at once.
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
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
    val listState = rememberLazyListState()
    LaunchedEffect(sections.recent) {
        // A keyed list otherwise keeps showing its old top row as new recents move above it.
        if (sections.recent.isNotEmpty()) listState.requestScrollToItem(0)
    }
    val pick: (ChatConfigChoice) -> Unit = { choice ->
        query = ""
        onPick(choice)
    }
    if (option.allChoices().size >= SEARCH_THRESHOLD) {
        SheetSearchField(
            query = query,
            onQueryChange = {
                query = it
                // Re-ranking moves rows, and a keyed list would otherwise scroll after its top row.
                listState.requestScrollToItem(0)
            },
            placeholder = stringResource(R.string.chat_search_placeholder, option.name),
        )
    }
    // Each provider folds behind its label, except the one holding the current model.
    val expandedIds =
        remember(option.id) {
            sections.groups
                .filter { g -> g.choices.any { it.value == option.currentValue } }
                .mapTo(mutableStateSetOf()) { it.id }
        }
    val recentLabel = stringResource(R.string.chat_recent)
    // An unlabelled list still needs a heading to part it from the recents above.
    val fallbackLabel = stringResource(R.string.chat_all_items).takeIf { sections.recent.isNotEmpty() }
    val emptyText = stringResource(R.string.chat_picker_no_models)
    // Folding only helps when there is more than one provider and no search narrowing them.
    val foldable = query.isBlank() && sections.groups.size > 1
    // Takes what the sheet has left, so the effort row below stays on screen.
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f, fill = false).edgeFadeMask(listState = listState),
    ) {
        if (sections.recent.isNotEmpty()) {
            sectionLabel(key = "recent-label", text = recentLabel)
            choiceRun("recent", sections.recent, option.currentValue, pick)
        }
        providerGroups(
            groups = sections.groups,
            currentValue = option.currentValue,
            expandedIds = expandedIds.takeIf { foldable },
            unlabelled = if (foldable) option.name else fallbackLabel,
            onPick = pick,
        )
        if (sections.recent.isEmpty() && sections.groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = itemMotion().padding(vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * Each provider's choices; with [expandedIds] they fold behind a header the user toggles, otherwise
 * they sit under a label. [unlabelled] names a group that has no label of its own.
 */
private fun LazyListScope.providerGroups(
    groups: List<ChatConfigChoiceGroup>,
    currentValue: String?,
    expandedIds: SnapshotStateSet<String>?,
    unlabelled: String?,
    onPick: (ChatConfigChoice) -> Unit,
) = groups.forEach { group ->
    val expanded = expandedIds == null || group.id in expandedIds
    if (expandedIds != null) {
        item(key = "fold:${group.id}") {
            FoldHeader(group.label ?: unlabelled.orEmpty(), group.choices.size, expanded, itemMotion()) {
                if (!expandedIds.remove(group.id)) expandedIds.add(group.id)
            }
        }
    } else {
        (group.label ?: unlabelled)?.let { sectionLabel(key = "label:${group.id}", text = it) }
    }
    if (expanded) choiceRun("group:${group.id}", group.choices, currentValue, onPick)
}

/** Re-ranking and folding move rows: fade what comes and goes, glide what stays. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LazyItemScope.itemMotion(): Modifier {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    return Modifier.animateItem(
        fadeInSpec = effects,
        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        fadeOutSpec = effects,
    )
}

private fun LazyListScope.sectionLabel(
    key: String,
    text: String,
) = item(key = key) { SectionLabel(text, itemMotion()) }

/** One run of choices as a connected segmented list, keyed by [runId] so rows keep their identity. */
private fun LazyListScope.choiceRun(
    runId: String,
    choices: List<ChatConfigChoice>,
    currentValue: String?,
    onPick: (ChatConfigChoice) -> Unit,
) = itemsIndexed(choices, key = { _, choice -> "$runId:${choice.value}" }) { index, choice ->
    ChoiceRow(choice, choice.value == currentValue, index, choices.size, onPick, itemMotion())
}

/** One choice in its run; the current one is selected and checked. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChoiceRow(
    choice: ChatConfigChoice,
    current: Boolean,
    index: Int,
    count: Int,
    onPick: (ChatConfigChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        selected = current,
        onClick = { onPick(choice) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
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
        modifier =
            modifier
                .padding(top = if (index > 0) ListItemDefaults.SegmentedGap else 0.dp)
                .handCursor(),
    ) {
        Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun FoldHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
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
            modifier
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
    // Registered after the sheet's own back handler, so back drops the keyboard before the sheet.
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = WindowInsets.ime.getBottom(LocalDensity.current) > 0) { focusManager.clearFocus() }
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
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gaps = ButtonGroupDefaults.ConnectedSpaceBetween * (choices.size - 1)
        // An equal share fills the row when the levels fit; past that they keep their labels and scroll.
        val minButtonWidth = (maxWidth - gaps) / choices.size
        Row(
            modifier =
                Modifier
                    .edgeFadeMask(scrollState = scrollState, orientation = Orientation.Horizontal)
                    .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            EffortToggleButtons(choices, effortOption, minButtonWidth, onSetConfigOption)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EffortToggleButtons(
    choices: List<ChatConfigChoice>,
    effortOption: ChatConfigOption.Select,
    minButtonWidth: Dp,
    onSetConfigOption: (String, String) -> Unit,
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
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.widthIn(min = minButtonWidth).handCursor().semantics { role = Role.RadioButton },
        ) {
            Text(choice.label, maxLines = 1)
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
 * every group. A query drops the recents and keeps only the matching choices
 * (see [modelMatchRank]), hiding the groups it empties.
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
        return ModelMenuSections(recent, groups)
    }
    // Best hits first within each group, and the group holding the best hit first; ties keep order.
    val matching =
        groups
            .mapNotNull { group ->
                val ranked =
                    group.choices
                        .mapNotNull { choice -> modelMatchRank(choice, group.label, q)?.let { choice to it } }
                        .sortedBy { it.second }
                ranked.firstOrNull()?.let { (_, best) -> best to group.copy(choices = ranked.map { it.first }) }
            }.sortedBy { it.first }
            .map { it.second }
    return ModelMenuSections(emptyList(), matching)
}
