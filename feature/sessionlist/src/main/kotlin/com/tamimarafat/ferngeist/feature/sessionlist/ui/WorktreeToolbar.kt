package com.tamimarafat.ferngeist.feature.sessionlist.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SelectableDropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.core.model.pathSegments
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.worktree.worktreeAge
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

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

private val SELECTOR_MAX_WIDTH = 104.dp

/** A label's cap when it has the toolbar to itself. */
private val SELECTOR_WIDE_MAX_WIDTH = 200.dp

private const val MAX_RECENT_DIRECTORIES = 5

/** A directory's last path segment, which is what the selector, menu and cards call it. */
internal fun directoryName(path: String): String = pathSegments(path).lastOrNull() ?: path.trim()

/**
 * Where New chat starts: a directory and, inside a repo on a gateway, a worktree of it.
 *
 * The FAB is the hero, so the toolbar is vibrant like it; the selectors beside it name the place
 * the FAB applies to. The worktree selector only exists once the directory names a repo on a
 * gateway with worktrees, and picking another directory re-lists it. Scrolling down collapses the
 * toolbar to just the FAB, which stays reachable, and scrolling up brings the selectors back.
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
    onOtherDirectory: () -> Unit,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewChat: () -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (worktreeId: String) -> Unit,
) {
    var directoryMenu by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    val scopeName = scopeLabel(scope, options)
    val narrowed = worktreesEnabled && scope != WorktreeScope.All
    val newChat =
        if (worktreesEnabled && scope is WorktreeScope.Worktree) {
            stringResource(R.string.sessionlist_new_chat_in, scopeName)
        } else {
            stringResource(R.string.sessionlist_new_session_desc)
        }
    HorizontalFloatingToolbar(
        expanded = expanded,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(newChat) } },
                state = rememberTooltipState(),
            ) {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = onNewChat,
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = newChat)
                }
            }
        },
        content = {
            // One non-bouncy width animation for the whole row: several springs resizing their own
            // labels at once fought each other, and the bounce shook the toolbar's leading edge.
            Row(
                modifier = Modifier.animateContentSize(MotionScheme.standard().defaultSpatialSpec()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    SelectorButton(
                        icon = Icons.Outlined.FolderOpen,
                        label =
                            repo?.takeIf { it.isNotBlank() }?.let(::directoryName)
                                ?: stringResource(R.string.sessionlist_scope_all_directories),
                        // Two names do not fit beside the FAB; once a worktree is picked it is the one that matters.
                        showLabel = !narrowed,
                        // Alone in the toolbar, it can take the room the worktree selector would use.
                        maxWidth = if (worktreesEnabled) SELECTOR_MAX_WIDTH else SELECTOR_WIDE_MAX_WIDTH,
                        focusable = expanded,
                        onClick = { directoryMenu = true },
                    )
                    DirectoryMenu(
                        expanded = directoryMenu,
                        repo = repo,
                        projects = projects,
                        onDismiss = { directoryMenu = false },
                        onSelectRepo = {
                            directoryMenu = false
                            onSelectRepo(it)
                        },
                        onOtherDirectory = {
                            directoryMenu = false
                            onOtherDirectory()
                        },
                    )
                }
                if (worktreesEnabled) {
                    SelectorButton(
                        icon = Icons.Outlined.AccountTree,
                        label = scopeName,
                        maxWidth = if (narrowed) SELECTOR_WIDE_MAX_WIDTH else SELECTOR_MAX_WIDTH,
                        focusable = expanded,
                        onClick = { scopeMenu = true },
                        showArrow = false,
                    )
                    if (scopeMenu) {
                        WorktreeSheet(
                            repo = repo.orEmpty(),
                            scope = scope,
                            options = options,
                            onDismiss = { scopeMenu = false },
                            onSelectScope = {
                                scopeMenu = false
                                onSelectScope(it)
                            },
                            onNewWorktree = {
                                scopeMenu = false
                                onNewWorktree()
                            },
                            onRemoveWorktree = {
                                scopeMenu = false
                                onRemoveWorktree(it)
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorButton(
    icon: ImageVector,
    label: String,
    focusable: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean = true,
    showArrow: Boolean = true,
    maxWidth: Dp = SELECTOR_MAX_WIDTH,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        enableUserInput = !showLabel,
    ) {
        TextButton(
            onClick = onClick,
            // Keyboard focus must not land on a selector the collapsed toolbar has hidden.
            modifier = Modifier.handCursor().focusProperties { canFocus = focusable },
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
        ) {
            Icon(
                icon,
                contentDescription = label.takeUnless { showLabel },
                modifier = Modifier.size(18.dp),
            )
            if (showLabel) SelectorLabel(label, maxWidth)
            if (showArrow) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
}

@Composable
private fun SelectorLabel(
    label: String,
    maxWidth: Dp,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (fadeIn(effects) + slideInVertically { it / 2 })
                .togetherWith(fadeOut(effects) + slideOutVertically { -it / 2 })
                // The row around the selectors animates the width; resizing here too would fight it.
                .using(SizeTransform { _, _ -> snap() })
        },
        label = "SelectorLabel",
    ) { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.widthIn(max = maxWidth).padding(start = 6.dp),
        )
    }
}

/** Every directory, the five most recent projects, and a way to type another. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DirectoryMenu(
    expanded: Boolean,
    repo: String?,
    projects: List<String>,
    onDismiss: () -> Unit,
    onSelectRepo: (String) -> Unit,
    onOtherDirectory: () -> Unit,
) {
    // The picked directory stays listed even when it has fallen out of the recent ones.
    val directories =
        (projects.take(MAX_RECENT_DIRECTORIES) + listOfNotNull(repo?.takeIf { it.isNotBlank() }))
            .distinctBy(::cwdKey)
    // Names alone, unless two projects share one; then the path tells them apart.
    val sharedNames =
        directories
            .groupingBy(::directoryName)
            .eachCount()
            .filterValues { it > 1 }
            .keys
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            val itemCount = directories.size + 2
            ScopeItem(
                selected = repo.isNullOrBlank(),
                label = stringResource(R.string.sessionlist_scope_all_directories),
                supporting = null,
                icon = Icons.Outlined.Forum,
                shapeIndex = 0 to itemCount,
                onClick = { onSelectRepo("") },
            )
            directories.forEachIndexed { index, path ->
                ScopeItem(
                    selected = repo != null && isSameCwd(repo, path),
                    label = directoryName(path),
                    supporting = path.takeIf { directoryName(it) in sharedNames },
                    icon = Icons.Outlined.FolderOpen,
                    shapeIndex = (index + 1) to itemCount,
                    onClick = { onSelectRepo(path) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_scope_other_directory)) },
                onClick = onOtherDirectory,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                    )
                },
            )
        }
    }
}

/**
 * The repo's chats, its main checkout and each worktree with a remove button, then `New worktree…`.
 * A sheet rather than a menu: worktree rows carry a status line and a button, which a menu's
 * 280dp cap squeezed out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorktreeSheet(
    repo: String,
    scope: WorktreeScope,
    options: List<ScopeOption>,
    onDismiss: () -> Unit,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (worktreeId: String) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()
    // Slide the sheet away first; the action then runs on a settled screen.
    val thenClose: (() -> Unit) -> Unit = { action ->
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
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
                text = directoryName(repo),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            val count = 2 + options.size
            ScopeRow(
                selected = scope == WorktreeScope.All,
                label = stringResource(R.string.sessionlist_scope_all),
                supporting = null,
                icon = Icons.Outlined.Forum,
                shapes = ListItemDefaults.segmentedShapes(0, count),
                onClick = { thenClose { onSelectScope(WorktreeScope.All) } },
            )
            ScopeRow(
                selected = scope == WorktreeScope.Main,
                label = stringResource(R.string.sessionlist_scope_main),
                supporting = null,
                icon = Icons.Outlined.FolderOpen,
                shapes = ListItemDefaults.segmentedShapes(1, count),
                onClick = { thenClose { onSelectScope(WorktreeScope.Main) } },
            )
            options.forEachIndexed { index, option ->
                ScopeRow(
                    selected = scope is WorktreeScope.Worktree && isSameCwd(scope.path, option.path),
                    label = option.branch,
                    supporting = scopeSupportingText(option),
                    icon = Icons.Outlined.AccountTree,
                    shapes = ListItemDefaults.segmentedShapes(index + 2, count),
                    onClick = { thenClose { onSelectScope(WorktreeScope.Worktree(option.path)) } },
                    trailing = {
                        IconButton(
                            onClick = { thenClose { onRemoveWorktree(option.worktree.id) } },
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
                onClick = { thenClose(onNewWorktree) },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                modifier = Modifier.handCursor(),
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
            ) {
                Text(stringResource(R.string.sessionlist_scope_new_worktree))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeRow(
    selected: Boolean,
    label: String,
    supporting: String?,
    icon: ImageVector,
    shapes: ListItemShapes,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
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
        Text(label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeItem(
    selected: Boolean,
    label: String,
    supporting: String?,
    icon: ImageVector,
    shapeIndex: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    SelectableDropdownMenuItem(
        selected = selected,
        onClick = onClick,
        text = { Text(label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingText = supporting?.let { { Text(it) } },
        shapes = MenuDefaults.itemShape(shapeIndex.first, shapeIndex.second),
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize))
        },
        selectedLeadingIcon = {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize))
        },
    )
}

/** `↑2 · uncommitted · 3 sessions · 2 days ago`. */
@Composable
private fun scopeSupportingText(option: ScopeOption): String? {
    val worktree = option.worktree
    // Worktrees the gateway did not make may carry no or a zero time; those show no age.
    val locale = LocalConfiguration.current.locales[0]
    val createdMillis = runCatching { Instant.parse(worktree.createdAt).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }
    return buildList {
        worktree.ahead?.takeIf { it > 0 }?.let { add(stringResource(R.string.sessionlist_worktree_ahead, it)) }
        if (worktree.dirty == true) add(stringResource(R.string.sessionlist_worktree_dirty))
        if (option.chats > 0) add(pluralStringResource(R.plurals.sessionlist_session_count, option.chats, option.chats))
        createdMillis?.let { add(narrowAge(Duration.ofMillis(System.currentTimeMillis() - it), locale)) }
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
