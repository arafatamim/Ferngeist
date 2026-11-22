package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.status.CardPresentation
import com.tamimarafat.ferngeist.core.common.ui.status.CardVariant
import com.tamimarafat.ferngeist.core.common.ui.status.cardPresentation
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.GatewayHealth
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ui.agents.AgentGroupHeader
import com.tamimarafat.ferngeist.feature.serverlist.ui.agents.DISCONNECTED_ALPHA
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.homeItemMotion
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.tileShape

/**
 * The home sheet's Projects page, laid out like the Agents page: every project with a working,
 * unread-finished, attention or error chat is promoted to a full-width hero tile, the rest sit
 * in a two-up grid, and the overflow hides behind a more row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ProjectsTab(
    projects: List<ProjectSummary>,
    turnStatuses: Map<String, TurnActivity>,
    connectedChatKeys: Set<String>,
    gatewayHealth: Map<String, GatewayHealth>,
    gatewayCount: Int,
    now: Long,
    listState: LazyListState,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    var expandedDevices by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val ordered = remember(projects, turnStatuses) { projectsByActivity(projects, turnStatuses) }
    val collapsed = collapsedProjects(ordered, now, turnStatuses)
    val presentations =
        remember(projects, turnStatuses, connectedChatKeys) {
            projects.associateWith { project ->
                cardPresentation(project.chatActivities(turnStatuses), project.hasLiveChat(connectedChatKeys))
            }
        }
    val gateways = remember(projects) { projects.map { it.gateway }.distinctBy { it.id } }
    val collapsedSet = collapsed.toSet()
    val hidden = ordered - collapsedSet
    val featured =
        ordered.firstOrNull { project ->
            presentations.getValue(project).variant == CardVariant.HERO &&
                (project in collapsedSet || project.gateway.id in expandedDevices)
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val context =
            ProjectGroupContext(
                gatewayHealth to gatewayCount,
                presentations,
                featured,
                { project -> project.connectedAgents(connectedChatKeys, turnStatuses) },
                onOpenProject,
                sharedTransitionScope to animatedContentScope,
            )
        ordered.groupBy { it.gateway.id }.forEach { (gatewayId, deviceProjects) ->
            projectDeviceGroup(deviceProjects, collapsedSet, gatewayId in expandedDevices, context) {
                expandedDevices =
                    if (gatewayId in expandedDevices) expandedDevices - gatewayId else expandedDevices + gatewayId
            }
        }
        if (hidden.isNotEmpty()) {
            item(key = "summary") {
                ProjectsSummary(projects, gateways.size, Modifier.homeItemMotion(this))
            }
        }
        if (projects.isEmpty()) {
            item(key = "projects-empty") { ProjectsEmpty(Modifier.animateItem()) }
        }
        // ponytail: clearance lets the final card scroll above the bottom controls.
        item(key = "bottom-clearance") { Spacer(modifier = Modifier.height(160.dp)) }
    }
}

private class ProjectGroupContext(
    val gateways: Pair<Map<String, GatewayHealth>, Int>,
    val presentations: Map<ProjectSummary, CardPresentation>,
    val featured: ProjectSummary?,
    val connectedAgents: (ProjectSummary) -> List<ConnectedAgent>,
    val onOpenProject: (ProjectSummary) -> Unit,
    val transitions: Pair<SharedTransitionScope, AnimatedContentScope>,
)

@OptIn(ExperimentalSharedTransitionApi::class)
private fun LazyListScope.projectDeviceGroup(
    allDeviceProjects: List<ProjectSummary>,
    collapsedSet: Set<ProjectSummary>,
    expanded: Boolean,
    context: ProjectGroupContext,
    onToggle: () -> Unit,
) {
    val gatewayId = allDeviceProjects.first().gateway.id
    val deviceProjects = if (expanded) allDeviceProjects else allDeviceProjects.filter { it in collapsedSet }
    val hiddenProjects = allDeviceProjects.filter { it !in collapsedSet }
    val disconnected = context.gateways.first[gatewayId] == GatewayHealth.UNREACHABLE
    val fade = if (disconnected) Modifier.alpha(DISCONNECTED_ALPHA) else Modifier
    if (context.gateways.second != 1) {
        item(key = "device:$gatewayId") {
            AgentGroupHeader(allDeviceProjects.first().gateway, disconnected, Modifier.homeItemMotion(this))
        }
    }
    val (heroProjects, restingProjects) =
        deviceProjects.partition { context.presentations.getValue(it).variant == CardVariant.HERO }
    projectRows(heroProjects, context.presentations) { project, presentation ->
        ProjectHeroTile(
            project = project,
            presentation = presentation,
            showGateway = false,
            featured = project == context.featured,
            connectedAgents = context.connectedAgents(project),
            onClick = { context.onOpenProject(project) },
            sharedTransitionScope = context.transitions.first,
            animatedContentScope = context.transitions.second,
            modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this).then(fade),
        )
    }
    projectTileRows(restingProjects, context.presentations, fade) { project, presentation, shape, modifier ->
        ProjectTile(
            project = project,
            presentation = presentation,
            showGateway = false,
            shape = shape,
            onClick = { context.onOpenProject(project) },
            sharedTransitionScope = context.transitions.first,
            animatedContentScope = context.transitions.second,
            modifier = modifier,
        )
    }
    if (hiddenProjects.isNotEmpty()) {
        item(key = "more:$gatewayId") {
            MoreProjectsRow(
                hidden = hiddenProjects,
                expanded = expanded,
                onToggle = onToggle,
                sharedTransitionScope = context.transitions.first,
                animatedContentScope = context.transitions.second,
                modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this).then(fade),
            )
        }
    }
}

/** Full-width hero rows: one promoted project per item. */
private fun LazyListScope.projectRows(
    projects: List<ProjectSummary>,
    presentations: Map<ProjectSummary, CardPresentation>,
    content: @Composable LazyItemScope.(ProjectSummary, CardPresentation) -> Unit,
) {
    projects.forEach { project ->
        item(key = "project:${project.gateway.id}/${project.repo}") {
            content(project, presentations.getValue(project))
        }
    }
}

/** The two-up grid: a pair of resting projects per item, their inner corners pinched together. */
private fun LazyListScope.projectTileRows(
    projects: List<ProjectSummary>,
    presentations: Map<ProjectSummary, CardPresentation>,
    fade: Modifier,
    content: @Composable LazyItemScope.(ProjectSummary, CardPresentation, RoundedCornerShape, Modifier) -> Unit,
) {
    projects.chunked(2).forEach { pair ->
        item(key = "projects:${pair.joinToString { "${it.gateway.id}/${it.repo}" }}") {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this).then(fade),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEachIndexed { index, project ->
                    content(project, presentations.getValue(project), tileShape(index, pair.size), Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The scope the collapsed view hides, so the more row never has to say it alone. Shown only
 * while there is overflow: once everything is visible the counts describe what is on screen.
 */
@Composable
private fun ProjectsSummary(
    projects: List<ProjectSummary>,
    gatewayCount: Int,
    modifier: Modifier = Modifier,
) {
    val chats = projects.sumOf { it.chats.size }
    Text(
        pluralStringResource(R.plurals.home_projects_summary_chats, chats, chats) +
            " · " +
            pluralStringResource(R.plurals.home_projects_summary_devices, gatewayCount, gatewayCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun ProjectsEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.home_projects_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.home_projects_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
