@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.IncrementalMarkdown
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.AGENT_TEXT_MAX_LINES
import com.tamimarafat.ferngeist.feature.chat.ui.ContentBlockRenderer
import com.tamimarafat.ferngeist.feature.chat.ui.DiffRenderer
import com.tamimarafat.ferngeist.feature.chat.ui.RenderedMarkdown
import com.tamimarafat.ferngeist.feature.chat.ui.TerminalRenderer
import com.tamimarafat.ferngeist.feature.chat.ui.files.diffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.filePath
import com.tamimarafat.ferngeist.feature.chat.ui.files.gitFallbackDiffs
import com.tamimarafat.ferngeist.feature.chat.ui.files.titleWithFile
import com.tamimarafat.ferngeist.feature.chat.ui.git.GitTooltip
import com.tamimarafat.ferngeist.feature.chat.ui.localFileOpener
import com.tamimarafat.ferngeist.feature.chat.ui.toolCallStatusLabel
import com.tamimarafat.ferngeist.feature.chat.ui.toolKindLabel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull

@Composable
internal fun ToolCallDetailsSheet(
    toolCall: ToolCallDisplay,
    onDismiss: () -> Unit,
) {
    // A diff can outrun the screen, and the git fallback that fills one lands after the sheet
    // opens, so the sheet's content grows under the reader. PartiallyExpanded is anchored at half
    // the screen, so a sheet that opens on a still-empty body settles there once the diff arrives:
    // leaving it out leaves only positions the growing content cannot drop the sheet to.
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
                    // Key events reach the active focus target and its ancestors only, and this
                    // sheet's content is text with nothing focusable in it.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToolCallDetailsHeader(toolCall = toolCall)
            ToolCallDetailsBody(toolCall = toolCall)
        }
    }
}

@Composable
internal fun ToolCallDetailsHeader(toolCall: ToolCallDisplay) {
    val defaultToolCallLabel = stringResource(R.string.chat_tool_call)
    val toolCallSheetTitle = toolCall.titleWithFile().ifBlank { defaultToolCallLabel }
    // An edit's diff and a read's slice both show only part of the file.
    val openablePath = toolCall.filePath()?.takeIf { toolCall.kind == ToolKind.EDIT || toolCall.kind == ToolKind.READ }
    val openFile = localFileOpener.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = toolCallSheetTitle,
                style = MaterialTheme.typography.bodyLarge,
                // Same reason as the permission sheet: an agent can dump an entire transcript
                // into the tool title, so cap it rather than let it push the body off-screen.
                maxLines = AGENT_TEXT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (openablePath != null && openFile != null) {
                val label = stringResource(R.string.chat_file_open_full)
                GitTooltip(label) {
                    IconButton(
                        onClick = { openFile(listOf(openablePath)) },
                        modifier = Modifier.size(32.dp).handCursor(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        toolCall.kind?.let { kind ->
            Text(
                text = toolKindLabel(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        toolCall.status?.let { status ->
            Text(
                text = toolCallStatusLabel(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ToolCallDetailsBody(toolCall: ToolCallDisplay) {
    if (!toolCall.permissionOptions.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.chat_awaiting_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (toolCall.kind == ToolKind.EXECUTE) {
        val rawInput = toolCall.rawInput
        val displayText = rawInput?.toString()
        if (!displayText.isNullOrBlank()) {
            ContentBlockRenderer(
                block = ContentBlock.Text(displayText),
            )
        }
    }

    val reported = toolCall.content.orEmpty()
    val gitDiffs = toolCall.gitFallbackDiffs()
    // No reported diff: git's, when gitFallbackDiffs has one, else the one rebuilt from the call's input.
    val content =
        if (reported.any { it is ToolCallContent.Diff }) reported else reported + gitDiffs.ifEmpty { toolCall.diffs() }
    if (content.isNotEmpty()) {
        content.forEach { tc ->
            when (tc) {
                is ToolCallContent.Content -> ContentBlockRenderer(tc.content)
                is ToolCallContent.Diff -> {
                    // Nested inside the sheet's vertical scroll column,
                    // so render rows eagerly without an inner lazy list.
                    DiffRenderer(tc, scrollable = false)
                }

                is ToolCallContent.Terminal -> TerminalRenderer(tc)
            }
        }
    } else {
        val rawOutput = toolCall.rawOutput
        if (rawOutput != null) {
            ContentBlockRenderer(
                block = ContentBlock.Text(rawOutput.toString()),
            )
        } else {
            Text(
                text = stringResource(R.string.chat_no_tool_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ThoughtDetailsSheet(
    thought: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    // Its own engine, fed only the tail a live thought grows by, as the transcript's runs are.
    val markdown = remember { IncrementalMarkdown() }
    val document = remember(thought) { markdown.update(thought).let { markdown.document } }
    val scroll = rememberScrollState()
    // Growing while open means the thought is live, so the sheet follows it down; settled
    // reasoning never grows and stays at the top. Scrolling up stops the follow, and scrolling
    // back to the end resumes it.
    val openedLength = remember { thought.length }
    var follow by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(thought.length) { if (thought.length > openedLength && !touched) follow = true }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.drop(1).filter { !it }.collect {
            touched = true
            follow = scroll.value >= scroll.maxValue
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { if (follow) scroll.maxValue else null }.filterNotNull().collect { scroll.scrollTo(it) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(scroll)
                    // Key events reach the active focus target and its ancestors only, and this
                    // sheet's content is text with nothing focusable in it.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            RenderedMarkdown(text = thought, document = document)
        }
    }
}
