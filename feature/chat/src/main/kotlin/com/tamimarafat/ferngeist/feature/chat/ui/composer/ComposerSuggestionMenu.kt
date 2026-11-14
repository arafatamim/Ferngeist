package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal data class ComposerSuggestion(
    val value: String,
    val label: String,
    val description: String?,
)

internal class ComposerSuggestionState(
    val start: Int?,
    val suggestions: List<ComposerSuggestion>,
    expanded: Boolean,
) {
    var visible by mutableStateOf(expanded && start != null && suggestions.isNotEmpty())
    var selected by mutableIntStateOf(0)

    fun onKey(
        event: KeyEvent,
        onSelect: (String, Int?) -> Unit,
    ): Boolean {
        if (!visible || event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isMetaPressed) return false
        if (event.isAltPressed || event.isShiftPressed) return false
        return when (event.key) {
            Key.DirectionDown -> {
                selected = moveSuggestionSelection(selected, 1, suggestions.size)
                true
            }
            Key.DirectionUp -> {
                selected = moveSuggestionSelection(selected, -1, suggestions.size)
                true
            }
            Key.Enter, Key.NumPadEnter, Key.Tab -> {
                visible = false
                onSelect(suggestions[selected].value, start)
                true
            }
            Key.Escape -> {
                visible = false
                true
            }
            else -> false
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ComposerSuggestionMenu(
    state: ComposerSuggestionState,
    anchor: IntOffset,
    onSelect: (String, Int?) -> Unit,
) {
    var lastSuggestions by remember { mutableStateOf(state.suggestions) }
    var lastSelected by remember { mutableIntStateOf(state.selected) }
    SideEffect {
        if (state.visible) {
            lastSuggestions = state.suggestions
            lastSelected = state.selected
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (state.visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "mention menu",
    )
    val position = rememberSuggestionPosition(anchor, state.visible)
    if (!state.visible && alpha <= 0f) return
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { state.visible = false },
        properties = PopupProperties(focusable = false),
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            modifier =
                Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            val suggestions = if (state.visible) state.suggestions else lastSuggestions
            val highlighted = if (state.visible) state.selected else lastSelected
            ComposerSuggestionItems(suggestions, highlighted, state, onSelect)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberSuggestionPosition(
    anchor: IntOffset,
    visible: Boolean,
): PopupPositionProvider {
    val menuPosition = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Above)
    return remember(menuPosition, anchor, visible) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val bounds = if (visible) anchorBounds else IntRect(anchor.x, anchor.y, anchor.x, anchor.y)
                return menuPosition.calculatePosition(bounds, windowSize, layoutDirection, popupContentSize)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ComposerSuggestionItems(
    suggestions: List<ComposerSuggestion>,
    highlighted: Int,
    state: ComposerSuggestionState,
    onSelect: (String, Int?) -> Unit,
) {
    suggestions.forEachIndexed { index, suggestion ->
        val requester = remember(suggestion.value) { BringIntoViewRequester() }
        val selected = index == highlighted
        LaunchedEffect(selected, state.visible) {
            if (selected && state.visible) requester.bringIntoView()
        }
        SelectableDropdownMenuItem(
            selected = selected,
            shapes = MenuDefaults.itemShape(index, suggestions.size),
            text = { Text(suggestion.label) },
            supportingText =
                suggestion.description?.let { description ->
                    { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                },
            modifier = Modifier.bringIntoViewRequester(requester),
            onClick = {
                if (state.visible) {
                    state.visible = false
                    onSelect(suggestion.value, state.start)
                }
            },
        )
    }
}

internal fun matchingSuggestions(
    paths: List<String>,
    query: String,
): List<String> =
    paths.filter { path ->
        var index = 0
        path.forEach { character ->
            if (index < query.length && character.equals(query[index], ignoreCase = true)) index++
        }
        index == query.length
    }

internal fun moveSuggestionSelection(
    selected: Int,
    delta: Int,
    count: Int,
): Int = (selected + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
