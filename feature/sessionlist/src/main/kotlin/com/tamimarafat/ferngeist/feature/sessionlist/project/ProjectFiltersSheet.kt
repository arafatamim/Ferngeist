package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.edgeFadeMask
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.input.mouseDragScroll
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.ScopeOption
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.ui.WorktreeRows
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProjectFiltersSheet(
    show: Boolean,
    agents: List<LaunchableTarget.GatewayAgent>,
    selectedAgentId: String?,
    scope: WorktreeScope,
    options: List<ScopeOption>,
    worktreesEnabled: Boolean,
    onSelectAgent: (String?) -> Unit,
    onSelectScope: (WorktreeScope) -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()
    val thenClose: (() -> Unit) -> Unit = { action ->
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
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
            SheetTitle(stringResource(R.string.sessionlist_filters))
            Text(stringResource(R.string.sessionlist_filter_agent), style = MaterialTheme.typography.titleSmall)
            SegmentedListItem(
                selected = selectedAgentId == null,
                onClick = { onSelectAgent(null) },
                shapes = ListItemDefaults.segmentedShapes(0, agents.size + 1),
                modifier = Modifier.handCursor(),
                leadingContent = {
                    Icon(
                        if (selectedAgentId == null) Icons.Filled.Check else Icons.Outlined.Forum,
                        contentDescription = null,
                    )
                },
            ) { Text(stringResource(R.string.sessionlist_all_agents)) }
            agents.forEachIndexed { index, agent ->
                SegmentedListItem(
                    selected = agent.id == selectedAgentId,
                    onClick = { onSelectAgent(agent.id) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, agents.size + 1),
                    modifier = Modifier.handCursor(),
                    leadingContent = { AgentIconBadge(agent, fallback = Icons.Rounded.SmartToy) },
                ) { Text(agent.name) }
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
internal fun ProjectFilterChips(
    agentName: String?,
    agentIconUrl: String?,
    scopeName: String?,
    onEdit: () -> Unit,
    onClearAgent: () -> Unit,
    onClearScope: () -> Unit,
    projectName: String? = null,
    onClearProject: () -> Unit = {},
) {
    val motion = MaterialTheme.motionScheme
    // A blank icon is a gateway sending no logo, not a URL to fetch.
    val agentLogo = agentIconUrl?.takeIf { it.isNotBlank() }
    val listState = rememberLazyListState()
    // LazyRow, not Row + horizontalScroll: inside SharedTransitionLayout the app bar's title slot never
    // redraws a placement-only scroll offset, so the chips stretched in place instead of scrolling.
    LazyRow(
        modifier =
            Modifier
                // Keep the measured row height alive while its last lazy item disappears.
                .animateContentSize(motion.fastSpatialSpec(), alignment = Alignment.TopStart)
                .mouseDragScroll(listState)
                // Erase, don't paint: the app bar's container tints as the page scrolls, so a
                // surface-colored overlay would leave a light patch at the edge.
                .edgeFadeMask(listState, orientation = Orientation.Horizontal),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        projectName?.let { name ->
            item(key = "project") {
                ActiveFilterChip(
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = motion.fastEffectsSpec(),
                            placementSpec = motion.fastSpatialSpec(),
                            fadeOutSpec = motion.fastEffectsSpec(),
                        ),
                    label = name,
                    description = stringResource(R.string.sessionlist_filter_project_selected, name),
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onEdit = onEdit,
                    onClear = onClearProject,
                )
            }
        }
        agentName?.let { name ->
            item(key = "agent") {
                ActiveFilterChip(
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = motion.fastEffectsSpec(),
                            placementSpec = motion.fastSpatialSpec(),
                            fadeOutSpec = motion.fastEffectsSpec(),
                        ),
                    // The logo is the chip: the name only appears when the agent has none.
                    label = name.takeIf { agentLogo == null },
                    description = stringResource(R.string.sessionlist_filter_agent_selected, name),
                    leadingIcon = agentLogo?.let { url -> { AgentFilterLogo(url) } },
                    onEdit = onEdit,
                    onClear = onClearAgent,
                )
            }
        }
        scopeName?.let { name ->
            item(key = "scope") {
                ActiveFilterChip(
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = motion.fastEffectsSpec(),
                            placementSpec = motion.fastSpatialSpec(),
                            fadeOutSpec = motion.fastEffectsSpec(),
                        ),
                    label = name,
                    description = stringResource(R.string.sessionlist_filter_worktree_selected, name),
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onEdit = onEdit,
                    onClear = onClearScope,
                )
            }
        }
    }
}

@Composable
private fun AgentFilterLogo(iconUrl: String) {
    // No container: the chip is the avatar slot, so a second rounded frame would nest two shapes.
    AgentIconBadge(
        iconUrl = iconUrl,
        fallback = Icons.Rounded.SmartToy,
        size = 18.dp,
        containerSize = null,
    )
}

@Composable
private fun ActiveFilterChip(
    label: String?,
    description: String,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    InputChip(
        selected = true,
        onClick = onEdit,
        modifier = modifier.handCursor().semantics { contentDescription = description },
        leadingIcon = leadingIcon,
        label = { label?.let { Text(it, modifier = Modifier.clearAndSetSemantics {}) } },
        trailingIcon = {
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp).handCursor()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.sessionlist_filter_clear, description),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}
