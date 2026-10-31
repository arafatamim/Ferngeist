package com.tamimarafat.ferngeist.feature.chat.selection

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.feature.chat.R

internal val LocalQuoteSelection = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun quoteDraft(
    draft: String,
    selected: String,
): String {
    val separator = if (draft.isEmpty()) "" else "\n\n"
    return draft + separator + selected.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
}

@Composable
internal fun QuoteSelectionContainer(content: @Composable () -> Unit) {
    val onQuote = LocalQuoteSelection.current
    val state = rememberSelectionState()
    val label = stringResource(R.string.quote_selection)
    val modifier =
        if (onQuote == null) {
            Modifier
        } else {
            Modifier.appendTextContextMenuComponents {
                item(key = "quote", label = label) {
                    val selected = state.selectedTexts.joinToString("\n") { it.text }
                    if (selected.isNotEmpty()) onQuote(selected)
                    close()
                }
            }
        }
    SelectionContainer(state = state, modifier = modifier, content = content)
}
