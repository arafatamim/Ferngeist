@file:OptIn(ExperimentalMaterial3Api::class)

package com.tamimarafat.ferngeist.feature.chat.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.feature.chat.GitRefsState
import com.tamimarafat.ferngeist.feature.chat.R

/** The refs the picker offers under each heading; [default] leads its section so the usual target is first. */
internal fun comparePickerSections(refs: GitRefsState): List<Pair<ChatGitRef.Kind, List<ChatGitRef>>> =
    listOf(ChatGitRef.Kind.BRANCH, ChatGitRef.Kind.REMOTE, ChatGitRef.Kind.TAG).mapNotNull { kind ->
        val ofKind = refs.refs.filter { it.kind == kind }
        val (leading, rest) = ofKind.partition { it.name == refs.default || it.name == "origin/${refs.default}" }
        (leading + rest).takeIf { it.isNotEmpty() }?.let { kind to it }
    }

/** Whether [other] is the target the picker is showing as chosen; kind matters since a tag can share a branch's name. */
internal fun ChatGitRef?.isSameTarget(other: ChatGitRef): Boolean = this?.kind == other.kind && this.name == other.name

/**
 * Bottom sheet over the git sheet to choose what it compares against. `Working tree` clears the
 * target, so there is no separate clear control. No filter field: a repo's ref list is short enough
 * to scroll for now.
 */
@Composable
internal fun GitComparePicker(
    refs: GitRefsState,
    selected: ChatGitRef?,
    onSelect: (ChatGitRef?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    val sections = remember(refs) { comparePickerSections(refs) }
    val choose: (ChatGitRef?) -> Unit = { ref ->
        onSelect(ref)
        scope.dismissSheet(sheetState, onDismiss)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    // Key events reach the active focus target and its ancestors only.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
        ) {
            Text(
                text = stringResource(R.string.chat_git_compare_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = "working-tree") {
                    CompareRow(
                        label = stringResource(R.string.chat_git_compare_working_tree),
                        detail = stringResource(R.string.chat_git_compare_uncommitted),
                        monospace = false,
                        isCurrent = false,
                        isSelected = selected == null,
                        onClick = { choose(null) },
                    )
                }
                sections.forEach { (kind, entries) -> compareSection(kind, entries, selected, choose) }
            }
        }
    }
}

private fun LazyListScope.compareSection(
    kind: ChatGitRef.Kind,
    entries: List<ChatGitRef>,
    selected: ChatGitRef?,
    choose: (ChatGitRef) -> Unit,
) {
    item(key = "header-$kind") {
        Text(
            text =
                stringResource(
                    when (kind) {
                        ChatGitRef.Kind.REMOTE -> R.string.chat_git_compare_remote
                        ChatGitRef.Kind.TAG -> R.string.chat_git_compare_tags
                        else -> R.string.chat_git_compare_local
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
    // Names are unique within a kind: they are refs of one namespace.
    items(entries, key = { "$kind/${it.name}" }) { ref ->
        CompareRow(
            label = ref.name,
            detail = if (ref.isHead) stringResource(R.string.chat_git_compare_current) else null,
            monospace = true,
            isCurrent = ref.isHead,
            isSelected = selected.isSameTarget(ref),
            onClick = { choose(ref) },
        )
    }
}

@Composable
private fun CompareRow(
    label: String,
    detail: String?,
    monospace: Boolean,
    isCurrent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .handCursor()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail != null) {
                val colors = MaterialTheme.colorScheme
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.chat_selected_desc),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
