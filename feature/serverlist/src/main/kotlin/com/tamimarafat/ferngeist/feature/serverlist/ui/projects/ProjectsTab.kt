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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.homeItemMotion
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.tileShape
import com.tamimarafat.ferngeist.feature.serverlist.ui.home.HomeFilterChips

private const val LIVE_FILTER = "live"

/**
 * The home sheet's Projects page, laid out like the Agents page: every project with a working,
 * unread-finished, attention or error chat is promoted to a full-width hero tile, the rest sit
 * in a two-up grid, and the overflow hides behind a more row. A chosen filter shows every
 * match; only the default view collapses.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ProjectsTab(
    projects: List<ProjectSummary>,
    turnStatuses: Map<String, TurnActivity>,
    connectedChatKeys: Set<String>,
    now: Long,
    listState: LazyListState,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    // null shows everything; otherwise LIVE_FILTER or a gateway id.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ordered = remember(projects, turnStatuses) { projectsByActivity(projects, turnStatuses) }
    val collapsed = collapsedProjects(ordered, now, turnStatuses)
    val presentations =
        remember(projects, turnStatuses, connectedChatKeys) {
            projects.associateWith { projectCardPresentation(it, turnStatuses, connectedChatKeys) }
        }
    val gateways = remember(projects) { projects.map { it.gateway }.distinctBy { it.id } }
    val shown = shownProjects(ordered, collapsed, expanded, filter, turnStatuses)
    val hidden = ordered - collapsed.toSet()
    val (heroProjects, restingProjects) =
        shown.partition { presentations.getValue(it).variant == ProjectCardVariant.HERO }
    val showGateway = gateways.size > 1

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (gateways.size > 1) {
            item(key = "filters") {
                HomeFilterChips(
                    liveValue = LIVE_FILTER,
                    liveLabel = stringResource(R.string.home_projects_live),
                    gateways = gateways,
                    selected = filter,
                    onSelect = { filter = it },
                    modifier = Modifier.homeItemMotion(this),
                )
            }
        }
        projectRows(heroProjects, presentations) { project, presentation ->
            ProjectHeroTile(
                project = project,
                presentation = presentation,
                showGateway = showGateway,
                connectedChatKeys = connectedChatKeys,
                turnStatuses = turnStatuses,
                onClick = { onOpenProject(project) },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this),
            )
        }
        projectTileRows(restingProjects, presentations) { project, presentation, shape, modifier ->
            ProjectTile(
                project = project,
                presentation = presentation,
                showGateway = showGateway,
                shape = shape,
                onClick = { onOpenProject(project) },
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                modifier = modifier,
            )
        }
        if (filter == null && hidden.isNotEmpty()) {
            item(key = "more") {
                MoreProjectsRow(
                    hidden = hidden,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this),
                )
            }
            item(key = "summary") {
                ProjectsSummary(projects, gateways.size, Modifier.homeItemMotion(this))
            }
        }
        if (shown.isEmpty()) {
            item(key = if (projects.isEmpty()) "projects-empty" else "filter-empty") {
                ProjectsEmptyItem(noProjects = projects.isEmpty(), Modifier.animateItem())
            }
        }
        // ponytail: clearance lets the final card scroll above the bottom controls.
        item(key = "bottom-clearance") { Spacer(modifier = Modifier.height(160.dp)) }
    }
}

/** A chosen filter shows every match; only the default view collapses to the recent five. */
private fun shownProjects(
    ordered: List<ProjectSummary>,
    collapsed: List<ProjectSummary>,
    expanded: Boolean,
    filter: String?,
    turnStatuses: Map<String, TurnActivity>,
): List<ProjectSummary> =
    when (filter) {
        null -> if (expanded) ordered else collapsed
        LIVE_FILTER -> ordered.filter { it.hasActiveTurn(turnStatuses) }
        else -> ordered.filter { it.gateway.id == filter }
    }

/** Full-width hero rows: one promoted project per item. */
private fun LazyListScope.projectRows(
    projects: List<ProjectSummary>,
    presentations: Map<ProjectSummary, ProjectCardPresentation>,
    content: @Composable LazyItemScope.(ProjectSummary, ProjectCardPresentation) -> Unit,
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
    presentations: Map<ProjectSummary, ProjectCardPresentation>,
    content: @Composable LazyItemScope.(ProjectSummary, ProjectCardPresentation, RoundedCornerShape, Modifier) -> Unit,
) {
    projects.chunked(2).forEach { pair ->
        item(key = "projects:${pair.joinToString { "${it.gateway.id}/${it.repo}" }}") {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this),
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

/** No projects at all reads differently from a filter that matches none. */
@Composable
private fun ProjectsEmptyItem(
    noProjects: Boolean,
    modifier: Modifier = Modifier,
) {
    if (noProjects) {
        ProjectsEmpty(modifier)
    } else {
        Text(
            stringResource(R.string.home_projects_filter_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
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
