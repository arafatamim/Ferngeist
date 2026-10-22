package com.tamimarafat.ferngeist.feature.sessionlist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.baseSuggestions
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.newWorktreeBranchName
import com.tamimarafat.ferngeist.gateway.GatewayGitRef
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import kotlinx.coroutines.launch

/**
 * Bottom sheet that starts a chat in a new gateway worktree of [repo]: what the branch is called,
 * and which ref — the repo's `HEAD`, its default branch, or the branch of a worktree already
 * there — it is cut from.
 *
 * The repo is the filter the session list already holds, so it is not asked for again. The form
 * lives with the sheet, not the view model: a half-typed branch is view state that dies on
 * dismiss, and each open starts from a fresh branch name so a kept one cannot hit "already
 * exists". The sheet stays open through a create — it shows the busy state and any inline error —
 * and closes when the chat opens.
 *
 * @param branches the branches of [repo]'s existing worktrees, offered as bases.
 * @param initialBase the base chosen when the sheet opens: the picked worktree's branch, or `""`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewWorktreeSheet(
    repo: String,
    branches: List<String>,
    initialBase: String,
    error: String?,
    creating: Boolean,
    onFormChanged: () -> Unit,
    onCreate: (repo: String, base: String?, branch: String?) -> Unit,
    loadRefs: suspend (String) -> GatewayGitRefs,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    var branch by rememberSaveable { mutableStateOf(newWorktreeBranchName()) }
    var base by rememberSaveable { mutableStateOf(initialBase) }
    var customBase by rememberSaveable { mutableStateOf(false) }
    var refs by remember { mutableStateOf(GatewayGitRefs()) }
    LaunchedEffect(repo) { refs = loadRefs(repo) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.sessionlist_new_worktree_chat),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = repo,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = stringResource(R.string.sessionlist_worktree_sheet_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = branch,
                onValueChange = {
                    branch = it
                    onFormChanged()
                },
                singleLine = true,
                isError = error != null,
                label = { Text(stringResource(R.string.sessionlist_worktree_branch)) },
                placeholder = { Text(stringResource(R.string.sessionlist_worktree_optional)) },
                supportingText = error?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            branch = newWorktreeBranchName()
                            onFormChanged()
                        },
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = stringResource(R.string.sessionlist_worktree_branch_shuffle_desc),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sessionlist_worktree_branch_from),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BranchFromChips(
                    branches = (listOf(refs.default, initialBase) + branches).filter { it.isNotBlank() }.distinct(),
                    refs = refs.refs,
                    base = base,
                    custom = customBase,
                    onBaseChange = {
                        base = it
                        onFormChanged()
                    },
                    onCustomChange = { customBase = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(R.string.sessionlist_cwd_cancel))
                }
                Button(
                    onClick = { onCreate(repo, base.trim().ifBlank { null }, branch.trim().ifBlank { null }) },
                    enabled = !creating && repo.isNotBlank(),
                    modifier = Modifier.handCursor(),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(stringResource(R.string.sessionlist_worktree_create))
                    }
                }
            }
        }
    }
}

/**
 * Repo `HEAD` (a blank [base]), each of [branches], and Custom, which reveals a free-text ref
 * that suggests matching [refs].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BranchFromChips(
    branches: List<String>,
    refs: List<GatewayGitRef>,
    base: String,
    custom: Boolean,
    onBaseChange: (String) -> Unit,
    onCustomChange: (Boolean) -> Unit,
) {
    val options = listOf("" to stringResource(R.string.sessionlist_worktree_base_hint)) + branches.map { it to it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = !custom && base == value,
                onClick = {
                    onCustomChange(false)
                    onBaseChange(value)
                },
                modifier = Modifier.handCursor(),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
            )
        }
        FilterChip(
            selected = custom,
            onClick = { onCustomChange(true) },
            modifier = Modifier.handCursor(),
            label = { Text(stringResource(R.string.sessionlist_worktree_base_custom)) },
        )
    }
    Reveal(visible = custom) {
        var expanded by remember { mutableStateOf(false) }
        val suggestions = remember(refs, base, branches) { baseSuggestions(refs, base, branches) }
        ExposedDropdownMenuBox(
            expanded = expanded && suggestions.isNotEmpty(),
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = base,
                onValueChange = {
                    onBaseChange(it)
                    expanded = true
                },
                singleLine = true,
                label = { Text(stringResource(R.string.sessionlist_worktree_base)) },
                placeholder = { Text(stringResource(R.string.sessionlist_worktree_base_hint)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { ref ->
                    DropdownMenuItem(
                        text = { Text(ref, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                        onClick = {
                            onBaseChange(ref)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

/** Grows in with a spatial spring and leaves faster: the user has already decided by then. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Reveal(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit =
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
    ) {
        content()
    }
}
