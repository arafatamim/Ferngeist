package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.handCursor
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
