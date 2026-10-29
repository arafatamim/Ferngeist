package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.projectRecencyOrder
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.branch
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.path
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.worktreeScopeOptions
import com.tamimarafat.ferngeist.feature.sessionlist.ui.ScopeRow
import com.tamimarafat.ferngeist.feature.sessionlist.ui.scopeSupportingText
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
import kotlinx.coroutines.launch

private const val MAX_QUICK_CHAT_PROJECTS = 6

internal fun quickChatProjects(projects: List<ProjectSummary>): List<ProjectSummary> =
    projects.sortedWith(projectRecencyOrder).take(MAX_QUICK_CHAT_PROJECTS)

/**
 * The home's quick chat: the project sheet, the worktree sheet and the agent sheet stacked in one.
 * Picking a project or a checkout only moves the selection; picking an agent starts the chat there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuickChatSheet(
    projects: List<ProjectSummary>,
    agents: List<LaunchableTarget.GatewayAgent>,
    statuses: Map<String, GatewayProjectLine.Agent>,
    onStart: (LaunchableTarget.GatewayAgent, cwd: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()
    val choices = remember(projects) { quickChatProjects(projects) }
    var projectKey by remember {
        mutableStateOf(choices.firstOrNull()?.let { it.gateway.id to it.repo })
    }
    val project = choices.firstOrNull { (it.gateway.id to it.repo) == projectKey }
    var scope by remember(projectKey) { mutableStateOf<WorktreeScope>(WorktreeScope.Main) }
    // An agent can only start a chat on its own gateway's disk.
    val bound = agents.filter { it.gatewaySource.id == project?.gateway?.id }
    val default = project?.let { defaultAgent(it.chats, scope, bound) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            SheetTitle(stringResource(R.string.sessionlist_scope_projects))
            ProjectChoices(choices, project) { projectKey = it.gateway.id to it.repo }
            project?.takeIf { it.checkout != null }?.let { shown ->
                Spacer(Modifier.height(12.dp))
                SheetTitle(projectName(shown.repo))
                CheckoutChoices(shown, scope) { scope = it }
            }
            if (project != null) {
                Spacer(Modifier.height(12.dp))
                SheetTitle(stringResource(R.string.sessionlist_project_pick_agent))
                AgentRows(
                    agents = agentsInSheetOrder(default, bound),
                    firstLastUsed = default != null && project.chats.any { it.target.id == default.id },
                    statuses = statuses,
                ) { agent ->
                    val cwd = (scope as? WorktreeScope.Worktree)?.path ?: project.repo
                    // Slide the sheet away first; the chat then opens on a settled screen.
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onStart(agent, cwd) }
                }
            }
        }
    }
}

@Composable
private fun ProjectChoices(
    projects: List<ProjectSummary>,
    selected: ProjectSummary?,
    onSelect: (ProjectSummary) -> Unit,
) {
    // The gateway tells two projects apart only when there is more than one.
    val showGateway = projects.distinctBy { it.gateway.id }.size > 1
    projects.forEachIndexed { index, project ->
        ScopeRow(
            selected = project == selected,
            label = projectName(project.repo),
            supporting = project.gateway.name.takeIf { showGateway },
            icon = Icons.Outlined.FolderOpen,
            shapes = ListItemDefaults.segmentedShapes(index, projects.size),
            onClick = { onSelect(project) },
        )
    }
}

@Composable
private fun CheckoutChoices(
    project: ProjectSummary,
    scope: WorktreeScope,
    onSelect: (WorktreeScope) -> Unit,
) {
    val options = worktreeScopeOptions(project.repo, project.worktrees(), project.chats.map { it.summary })
    val count = 1 + options.size
    ScopeRow(
        selected = scope == WorktreeScope.Main,
        label = stringResource(R.string.sessionlist_scope_main),
        supporting = null,
        icon = Icons.Outlined.FolderOpen,
        shapes = ListItemDefaults.segmentedShapes(0, count),
        onClick = { onSelect(WorktreeScope.Main) },
    )
    options.forEachIndexed { index, option ->
        ScopeRow(
            selected = scope is WorktreeScope.Worktree && isSameCwd(scope.path, option.path),
            label = option.branch,
            supporting = scopeSupportingText(option),
            icon = Icons.Outlined.AccountTree,
            shapes = ListItemDefaults.segmentedShapes(index + 1, count),
            onClick = { onSelect(WorktreeScope.Worktree(option.path)) },
            labelOverflow = TextOverflow.StartEllipsis,
        )
    }
}
