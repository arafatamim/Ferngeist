package com.tamimarafat.ferngeist.feature.sessionlist.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.core.model.pathSegments
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.branch
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.path
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.worktreeAge
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.tamimarafat.ferngeist.core.common.R as CommonR

/** The label for [scope]: what the selector, the app bar subtitle and the empty state call it. */
@Composable
internal fun scopeLabel(
    scope: WorktreeScope,
    options: List<ScopeOption>,
): String =
    when (scope) {
        WorktreeScope.All -> stringResource(R.string.sessionlist_scope_all)
        WorktreeScope.Main -> stringResource(R.string.sessionlist_scope_main)
        is WorktreeScope.Worktree ->
            options.firstOrNull { isSameCwd(it.path, scope.path) }?.branch
                ?: pathSegments(scope.path).lastOrNull().orEmpty()
    }

private const val MAX_RECENT_PROJECTS = 5
private const val DISABLED_ACTION_ALPHA = 0.38f

@Composable
internal fun FiltersButton(
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val label =
        if (count == 0) {
            stringResource(R.string.sessionlist_filters)
        } else {
            pluralStringResource(R.plurals.sessionlist_filters_applied, count, count)
        }
    TextButton(
        onClick = onClick,
        modifier = Modifier.handCursor().focusProperties { canFocus = expanded },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Icon(Icons.Outlined.FilterList, contentDescription = null)
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where New chat starts: a project and, inside a repo on a gateway, a worktree of it.
 *
 * The FAB is the hero; the filter sheet chooses where the new chat starts.
 * Scrolling down collapses the toolbar to the FAB, and scrolling up restores the filter button.
 *
 * @param worktreesEnabled true when the filter names a repo on a gateway with worktrees.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorktreeToolbar(
    expanded: Boolean,
    repo: String?,
    projects: List<String>,
    worktreesEnabled: Boolean,
    scope: WorktreeScope,
    options: List<ScopeOption>,
    onSelectRepo: (String) -> Unit,
    onOtherProject: (() -> Unit)?,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewChat: () -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (worktreeId: String) -> Unit,
    newChatLabel: String? = null,
    newChatEnabled: Boolean = true,
    leadingSelector: (@Composable () -> Unit)? = null,
) {
    val scopeName = scopeLabel(scope, options)
    var showFilters by remember { mutableStateOf(false) }
    val newChat =
        when {
            newChatLabel != null -> newChatLabel
            worktreesEnabled && scope is WorktreeScope.Worktree ->
                stringResource(R.string.sessionlist_new_chat_in, scopeName)
            else -> stringResource(R.string.sessionlist_new_session_desc)
        }
    HorizontalFloatingToolbar(
        expanded = expanded,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = { NewChatButton(newChat = newChat, onNewChat = onNewChat, enabled = newChatEnabled) },
        content = {
            if (leadingSelector != null) {
                leadingSelector()
            } else {
                FiltersButton(
                    expanded = expanded,
                    count = (if (!repo.isNullOrBlank()) 1 else 0) + (if (scope != WorktreeScope.All) 1 else 0),
                    onClick = { showFilters = true },
                )
            }
        },
    )
    if (showFilters) {
        AgentFiltersSheet(
            repo = repo,
            projects = projects,
            worktreesEnabled = worktreesEnabled,
            scope = scope,
            options = options,
            onDismiss = { showFilters = false },
            onSelectProject = onSelectRepo,
            onOtherProject = onOtherProject,
            onSelectScope = onSelectScope,
            onNewWorktree = onNewWorktree,
            onRemoveWorktree = onRemoveWorktree,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewChatButton(
    newChat: String,
    onNewChat: () -> Unit,
    enabled: Boolean,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(newChat) } },
        state = rememberTooltipState(),
    ) {
        FloatingToolbarDefaults.VibrantFloatingActionButton(
            onClick = { if (enabled) onNewChat() },
            modifier =
                Modifier
                    .handCursor()
                    .alpha(if (enabled) 1f else DISABLED_ACTION_ALPHA)
                    .semantics { if (!enabled) disabled() },
        ) {
            Icon(Icons.Filled.Add, contentDescription = newChat)
        }
    }
}

/** Project and worktree filters; keep the selected project alongside the most recent ones. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentFiltersSheet(
    repo: String?,
    projects: List<String>,
    worktreesEnabled: Boolean,
    scope: WorktreeScope,
    options: List<ScopeOption>,
    onDismiss: () -> Unit,
    onSelectProject: (String) -> Unit,
    onOtherProject: (() -> Unit)?,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (String) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()
    // Slide the sheet away first; the action then runs on a settled screen.
    val thenClose: (() -> Unit) -> Unit = { action ->
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    // The picked project stays listed even when it has fallen out of the recent ones.
    val listed =
        if (onOtherProject == null) {
            projects
        } else {
            (projects.take(MAX_RECENT_PROJECTS) + listOfNotNull(repo?.takeIf { it.isNotBlank() }))
                .distinctBy(::cwdKey)
        }
    // Names alone, unless two projects share one; then the path tells them apart.
    val sharedNames =
        listed
            .groupingBy(::projectName)
            .eachCount()
            .filterValues { it > 1 }
            .keys
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            Text(
                text = stringResource(R.string.sessionlist_filters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            Text(stringResource(R.string.sessionlist_scope_projects), style = MaterialTheme.typography.titleSmall)
            val offset = if (onOtherProject == null) 0 else 1
            val count = listed.size + offset
            if (onOtherProject != null) {
                ScopeRow(
                    selected = repo.isNullOrBlank(),
                    label = stringResource(R.string.sessionlist_scope_all_projects),
                    supporting = null,
                    icon = Icons.Outlined.Forum,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    onClick = { onSelectProject("") },
                )
            }
            listed.forEachIndexed { index, path ->
                ScopeRow(
                    selected = repo != null && isSameCwd(repo, path),
                    label = projectName(path),
                    supporting = path.takeIf { projectName(it) in sharedNames },
                    icon = Icons.Outlined.FolderOpen,
                    shapes = ListItemDefaults.segmentedShapes(index + offset, count),
                    onClick = { onSelectProject(path) },
                )
            }
            onOtherProject?.let { other ->
                Spacer(Modifier.height(12.dp))
                SegmentedListItem(
                    onClick = { thenClose(other) },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    modifier = Modifier.handCursor(),
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                ) {
                    Text(stringResource(R.string.sessionlist_scope_other_project))
                }
            }
            if (worktreesEnabled) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.sessionlist_filter_worktree), style = MaterialTheme.typography.titleSmall)
                WorktreeRows(
                    scope = scope,
                    options = options,
                    onSelectScope = onSelectScope,
                    onNewWorktree = { thenClose(onNewWorktree) },
                    onRemoveWorktree = { id -> thenClose { onRemoveWorktree(id) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorktreeRows(
    scope: WorktreeScope,
    options: List<ScopeOption>,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (String) -> Unit,
) {
    val count = 2 + options.size
    ScopeRow(
        selected = scope == WorktreeScope.All,
        label = stringResource(R.string.sessionlist_scope_all),
        supporting = null,
        icon = Icons.Outlined.Forum,
        shapes = ListItemDefaults.segmentedShapes(0, count),
        onClick = { onSelectScope(WorktreeScope.All) },
    )
    ScopeRow(
        selected = scope == WorktreeScope.Main,
        label = stringResource(R.string.sessionlist_scope_main),
        supporting = null,
        icon = Icons.Outlined.FolderOpen,
        shapes = ListItemDefaults.segmentedShapes(1, count),
        onClick = { onSelectScope(WorktreeScope.Main) },
    )
    options.forEachIndexed { index, option ->
        ScopeRow(
            selected = scope is WorktreeScope.Worktree && isSameCwd(scope.path, option.path),
            label = option.branch,
            supporting = scopeSupportingText(option),
            icon = Icons.Outlined.AccountTree,
            shapes = ListItemDefaults.segmentedShapes(index + 2, count),
            onClick = { onSelectScope(WorktreeScope.Worktree(option.path)) },
            labelOverflow = TextOverflow.StartEllipsis,
            trailing = {
                IconButton(
                    onClick = { onRemoveWorktree(option.worktree.id) },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.sessionlist_action_remove_worktree),
                    )
                }
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    SegmentedListItem(
        onClick = onNewWorktree,
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        modifier = Modifier.handCursor(),
        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
    ) {
        Text(stringResource(R.string.sessionlist_scope_new_worktree))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScopeRow(
    selected: Boolean,
    label: String,
    supporting: String?,
    icon: ImageVector,
    shapes: ListItemShapes,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    labelOverflow: TextOverflow = TextOverflow.MiddleEllipsis,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.handCursor(),
        leadingContent = { Icon(if (selected) Icons.Filled.Check else icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing,
    ) {
        Text(label, maxLines = 1, overflow = labelOverflow)
    }
}

/** `↑2 · uncommitted · 3 sessions · 2 days ago`. */
@Composable
internal fun scopeSupportingText(option: ScopeOption): String? {
    val worktree = option.worktree
    // Worktrees the gateway did not make may carry no or a zero time; those show no age.
    val locale = LocalConfiguration.current.locales[0]
    val now = rememberPresentationTime()
    val createdMillis = runCatching { Instant.parse(worktree.createdAt).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }
    return buildList {
        worktree.ahead?.takeIf { it > 0 }?.let { add(stringResource(CommonR.string.common_worktree_ahead, it)) }
        if (worktree.dirty == true) add(stringResource(CommonR.string.common_worktree_dirty))
        if (option.chats > 0) add(pluralStringResource(R.plurals.sessionlist_session_count, option.chats, option.chats))
        createdMillis?.let {
            val age = Duration.ofMillis(now - it)
            add(if (age.toMinutes() < 1) relativeTime(it, now) else narrowAge(age, locale))
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** `2d`, `3h`: the narrow form the row's status line has room for. */
private fun narrowAge(
    age: Duration,
    locale: java.util.Locale,
): String {
    val (count, unit) = worktreeAge(age)
    val measureUnit =
        when (unit) {
            ChronoUnit.YEARS -> MeasureUnit.YEAR
            ChronoUnit.MONTHS -> MeasureUnit.MONTH
            ChronoUnit.WEEKS -> MeasureUnit.WEEK
            ChronoUnit.DAYS -> MeasureUnit.DAY
            ChronoUnit.HOURS -> MeasureUnit.HOUR
            else -> MeasureUnit.MINUTE
        }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW).format(Measure(count, measureUnit))
}
