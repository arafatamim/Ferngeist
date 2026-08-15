@file:OptIn(ExperimentalMaterial3Api::class)
package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.allChoices
import com.tamimarafat.ferngeist.feature.chat.R
internal data class PickerItem(
    val id: String,
    val label: String,
    val value: String,
    val description: String? = null,
)

internal data class PickerSheetState(
    val showSearch: Boolean,
    var query: String,
    val filteredOptions: List<PickerItem>,
    val showRecentSection: Boolean,
    val remainingItems: List<PickerItem>,
    val recentItems: List<PickerItem>,
)

@Composable
internal fun rememberPickerSheetState(
    items: List<PickerItem>,
    recentItems: List<PickerItem>,
): PickerSheetState {
    val showSearch = items.size >= 10
    var query by remember { mutableStateOf("") }

    val recentValues = remember(recentItems) { recentItems.map { it.value }.toSet() }
    val remainingItems =
        remember(items, recentValues) {
            items.filter { it.value !in recentValues }
        }
    val showRecentSection = recentItems.isNotEmpty() && query.isBlank()
    val searchPool = remember(remainingItems, recentItems) { recentItems + remainingItems }

    val filteredOptions =
        remember(searchPool, query) {
            if (!showSearch || query.trim().isBlank()) {
                searchPool
            } else {
                val q = query.trim()
                searchPool.filter { item ->
                    item.label.contains(q, ignoreCase = true) ||
                        item.value.contains(q, ignoreCase = true) ||
                        (item.description?.contains(q, ignoreCase = true) == true)
                }
            }
        }
    return PickerSheetState(
        showSearch = showSearch,
        query = query,
        filteredOptions = filteredOptions,
        showRecentSection = showRecentSection,
        remainingItems = remainingItems,
        recentItems = recentItems,
    )
}

@Composable
internal fun PickerSheet(
    title: String,
    items: List<PickerItem>,
    selectedValue: String? = null,
    recentItems: List<PickerItem> = emptyList(),
    onItemClick: (value: String) -> Unit,
    onDismiss: () -> Unit,
    emptyText: String = "",
    noResultsText: String = "",
    searchPlaceholder: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pickerState = rememberPickerSheetState(items, recentItems)
    val showSearch = pickerState.showSearch
    val filteredOptions = pickerState.filteredOptions
    val showRecentSection = pickerState.showRecentSection
    val remainingItems = pickerState.remainingItems
    val recentItemsResolved = pickerState.recentItems

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            if (items.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                PickerSheetContent(
                    query = pickerState.query,
                    onQueryChange = { pickerState.query = it },
                    showSearch = showSearch,
                    searchPlaceholder = searchPlaceholder,
                    filteredOptions = filteredOptions,
                    noResultsText = noResultsText,
                    showRecentSection = showRecentSection,
                    remainingItems = remainingItems,
                    recentItems = recentItemsResolved,
                    selectedValue = selectedValue,
                    onItemClick = onItemClick,
                    sheetState = sheetState,
                    scope = scope,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.PickerSheetContent(
    query: String,
    onQueryChange: (String) -> Unit,
    showSearch: Boolean,
    searchPlaceholder: String,
    filteredOptions: List<PickerItem>,
    noResultsText: String,
    showRecentSection: Boolean,
    remainingItems: List<PickerItem>,
    recentItems: List<PickerItem>,
    selectedValue: String?,
    onItemClick: (String) -> Unit,
    sheetState: SheetState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    if (showSearch) {
        PickerSearchField(
            query = query,
            onQueryChange = onQueryChange,
            searchPlaceholder = searchPlaceholder,
        )
    }
    if (filteredOptions.isEmpty()) {
        Text(
            text = noResultsText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val displayItems = if (showRecentSection) remainingItems else filteredOptions
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showRecentSection) {
                Text(
                    text = stringResource(R.string.chat_recent),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                recentItems.forEach { item ->
                    PickerItemRow(item, selectedValue, onItemClick, sheetState, scope, onDismiss)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.chat_all_items),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            displayItems.forEach { item ->
                PickerItemRow(item, selectedValue, onItemClick, sheetState, scope, onDismiss)
            }
        }
    }
}

@Composable
internal fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = stringResource(R.string.chat_search_desc),
            )
        },
        placeholder = { Text(searchPlaceholder) },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
internal fun PickerItemRow(
    item: PickerItem,
    selectedValue: String?,
    onItemClick: (String) -> Unit,
    sheetState: SheetState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    onItemClick(item.value)
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
            item.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selectedValue != null && item.value == selectedValue) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.chat_selected_desc),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable

internal fun SelectConfigOptionSheet(
    option: ChatConfigOption.Select,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Key format: "config_option:$serverId:$optionId"
    // clearByPrefix uses "config_option:$serverId:" trailing colon to avoid cross-server matches
    val storageKey = remember(option.id, serverId) { "config_option:$serverId:${option.id}" }
    val allChoices = remember(option) { option.allChoices() }
    val enableRecents = allChoices.size >= 10
    val recentValues by recentSelectionStore
        .getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems =
        remember(recentValues, allChoices, enableRecents) {
            if (!enableRecents) {
                emptyList()
            } else {
                recentValues.mapNotNull { val_ ->
                    allChoices.find { it.value == val_ }?.let { choice ->
                        PickerItem(
                            id = choice.id,
                            label = choice.label,
                            value = choice.value,
                            description = choice.description,
                        )
                    }
                }
            }
        }
    PickerSheet(
        title = option.name,
        items =
            allChoices.map { choice ->
                PickerItem(
                    id = choice.id,
                    label = choice.label,
                    value = choice.value,
                    description = choice.description,
                )
            },
        selectedValue = option.currentValue,
        recentItems = recentItems,
        onItemClick = { value ->
            onOptionSelected(value)
            if (enableRecents) {
                scope.launch { recentSelectionStore.addSelection(storageKey, value) }
            }
        },
        onDismiss = onDismiss,
        emptyText = stringResource(R.string.chat_picker_no_values),
        noResultsText = stringResource(R.string.chat_picker_no_models),
        searchPlaceholder = stringResource(R.string.chat_search_placeholder, option.name),
    )
}
