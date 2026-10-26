package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.ProjectSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.ProjectTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.status.InProgressIndicator
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.serverlist.R

/** Live projects lead in both views; each group keeps newest known activity first. */
internal fun projectsByActivity(
    projects: List<ProjectSummary>,
    streamingChatKeys: Set<String>,
): List<ProjectSummary> {
    val (live, idle) = projects.partition { it.isLive(streamingChatKeys) }
    return live.sortedByDescending { it.updatedAt } + idle.sortedByDescending { it.updatedAt }
}

/**
 * The home's Projects rows, each opening its project; quiet by design so the hero stays the
 * screen's single emphasized element. From activity-ordered projects, keep live ones or known
 * activity strictly within 48 hours, capped at five.
 */
internal fun collapsedProjects(
    projects: List<ProjectSummary>,
    now: Long,
    streamingChatKeys: Set<String>,
): List<ProjectSummary> =
    projects
        .filter {
            it.isLive(streamingChatKeys) ||
                it.updatedAt?.let { updated -> now - updated < RECENT_PROJECT_WINDOW_MS } == true
        }.take(MAX_VISIBLE_PROJECTS)

private fun ProjectSummary.isLive(streamingChatKeys: Set<String>): Boolean =
    chats.any { "${it.target.id}/${it.summary.id}" in streamingChatKeys }

private const val RECENT_PROJECT_WINDOW_MS = 48L * 60 * 60 * 1000
private const val MAX_VISIBLE_PROJECTS = 5

/**
 * The home's Projects as one segmented group: the recent ones, then a last segment that shows the rest.
 * One group keeps the segmented gap between rows instead of the home list's 12dp item spacing.
 */
@Composable
internal fun ProjectsGroup(
    projects: List<ProjectSummary>,
    streamingChatKeys: Set<String>,
    now: Long,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ordered = remember(projects, streamingChatKeys) { projectsByActivity(projects, streamingChatKeys) }
    val collapsed = collapsedProjects(ordered, now, streamingChatKeys)
    val shown = if (expanded) ordered else collapsed
    val hidden = projects.size - collapsed.size
    val count = shown.size + if (hidden > 0) 1 else 0
    val showGateway = projects.distinctBy { it.gateway.id }.size > 1
    Column(
        modifier = modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        shown.forEachIndexed { index, project ->
            key(project.gateway.id, project.repo) {
                ProjectItem(
                    project = project,
                    index = index,
                    count = count,
                    showGateway = showGateway,
                    live = project.isLive(streamingChatKeys),
                    now = now,
                    onOpenProject = onOpenProject,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            }
        }
        if (hidden > 0) {
            ProjectsToggle(expanded, hidden, index = shown.size, count = count) { expanded = !expanded }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ProjectItem(
    project: ProjectSummary,
    index: Int,
    count: Int,
    showGateway: Boolean,
    live: Boolean,
    now: Long,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = { onOpenProject(project) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        // The row is what the project screen grows out of, as an agent card is for its session list.
        modifier =
            with(sharedTransitionScope) {
                modifier.sharedBounds(
                    sharedContentState =
                        rememberSharedContentState(ProjectSharedBoundsKey(project.gateway.id, project.repo)),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                )
            }.handCursor(),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        overlineContent = if (showGateway) ({ Text(project.gateway.name) }) else null,
        supportingContent = { Text(projectCounts(project)) },
        trailingContent = { ProjectTrailing(project, live, now) },
    ) {
        with(sharedTransitionScope) {
            Text(
                text = projectName(project.repo),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.sharedBounds(
                        sharedContentState =
                            rememberSharedContentState(
                                ProjectTitleSharedBoundsKey(project.gateway.id, project.repo),
                            ),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    ),
            )
        }
    }
}

/** The group's last segment, so it reads as part of the list it grows instead of a loose link. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProjectsToggle(
    expanded: Boolean,
    hidden: Int,
    index: Int,
    count: Int,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "projectsToggle",
    )
    SegmentedListItem(
        onClick = onToggle,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = Modifier.handCursor(),
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                leadingContentColor = MaterialTheme.colorScheme.primary,
                trailingContentColor = MaterialTheme.colorScheme.primary,
            ),
        leadingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.rotate(rotation)) },
        trailingContent = if (expanded) null else ({ Text("+$hidden", style = MaterialTheme.typography.labelLarge) }),
    ) {
        Text(
            stringResource(if (expanded) R.string.serverlist_show_less else R.string.serverlist_show_more),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun projectCounts(project: ProjectSummary): String {
    val chats = pluralStringResource(R.plurals.serverlist_project_chats, project.chats.size, project.chats.size)
    val worktrees = project.checkout?.worktrees?.size ?: 0
    if (worktrees == 0) return chats
    return chats + " · " + pluralStringResource(R.plurals.serverlist_project_worktrees, worktrees, worktrees)
}

@Composable
private fun ProjectTrailing(
    project: ProjectSummary,
    live: Boolean,
    now: Long,
) {
    Crossfade(
        targetState = live,
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "projectActivity",
    ) { working ->
        if (working) {
            InProgressIndicator()
        } else {
            project.updatedAt?.let { updatedAt ->
                Text(
                    text = relativeTime(updatedAt, now),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
