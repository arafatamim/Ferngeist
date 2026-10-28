package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectChat
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.ProjectSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.ProjectTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingSubtitle
import com.tamimarafat.ferngeist.core.common.ui.status.isActive
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnStatus
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ui.status.ActiveChatsSubtitle

/** Live projects lead in both views; each group keeps newest known activity first. */
internal fun projectsByActivity(
    projects: List<ProjectSummary>,
    turnStatuses: Map<String, TurnStatus>,
): List<ProjectSummary> {
    val (live, idle) = projects.partition { it.hasActiveTurn(turnStatuses) }
    return live.sortedByDescending { it.updatedAt } + idle.sortedByDescending { it.updatedAt }
}

/**
 * The home's Projects rows, each opening its project; quiet by design so the hero stays the
 * screen's single emphasized element. From activity-ordered projects, keep in-flight ones or known
 * activity strictly within 48 hours, capped at five.
 */
internal fun collapsedProjects(
    projects: List<ProjectSummary>,
    now: Long,
    turnStatuses: Map<String, TurnStatus>,
): List<ProjectSummary> =
    projects
        .filter {
            it.hasActiveTurn(turnStatuses) ||
                it.updatedAt?.let { updated -> now - updated < RECENT_PROJECT_WINDOW_MS } == true
        }.take(MAX_VISIBLE_PROJECTS)

internal fun ProjectSummary.hasLiveChat(chatKeys: Set<String>): Boolean = chats.any { it.chatKey in chatKeys }

/** The highest-priority status among this project's chats, matched by full chat identity. */
internal fun ProjectSummary.turnStatus(turnStatuses: Map<String, TurnStatus>): TurnStatus =
    chats.map { turnStatuses[it.chatKey] ?: TurnStatus.UNKNOWN }.aggregateTurnStatus()

/** In flight means working or awaiting attention, never a terminal outcome. */
internal fun ProjectSummary.hasActiveTurn(turnStatuses: Map<String, TurnStatus>): Boolean =
    chats.any { turnStatuses[it.chatKey]?.isActive == true }

internal fun ProjectSummary.activeChatCount(turnStatuses: Map<String, TurnStatus>): Int =
    chats.count { turnStatuses[it.chatKey] == TurnStatus.WORKING }

private val ProjectChat.chatKey: String get() = "${target.id}/${summary.id}"

private const val RECENT_PROJECT_WINDOW_MS = 48L * 60 * 60 * 1000
private const val MAX_VISIBLE_PROJECTS = 5

/**
 * The home's Projects as one segmented group: the recent ones, then a last segment that shows the rest.
 * One group keeps the segmented gap between rows instead of the home list's 12dp item spacing.
 */
@Composable
internal fun ProjectsGroup(
    projects: List<ProjectSummary>,
    turnStatuses: Map<String, TurnStatus>,
    connectedChatKeys: Set<String>,
    now: Long,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ordered = remember(projects, turnStatuses) { projectsByActivity(projects, turnStatuses) }
    val collapsed = collapsedProjects(ordered, now, turnStatuses)
    val shown = if (expanded) ordered else collapsed
    val hidden = projects.size - collapsed.size
    val count = shown.size + if (hidden > 0) 1 else 0
    val showGateway = projects.distinctBy { it.gateway.id }.size > 1
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.geometry.Rect>()
    val boundsTransform = remember(spatialSpec) { BoundsTransform { _, _ -> spatialSpec } }
    LookaheadScope {
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
                        status = project.turnStatus(turnStatuses),
                        activeChats = project.activeChatCount(turnStatuses),
                        isConnected = project.hasLiveChat(connectedChatKeys),
                        now = now,
                        onOpenProject = onOpenProject,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier =
                            Modifier.animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = boundsTransform,
                            ),
                    )
                }
            }
            if (hidden > 0) {
                ProjectsToggle(expanded, hidden, index = shown.size, count = count) { expanded = !expanded }
            }
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
    status: TurnStatus,
    activeChats: Int,
    isConnected: Boolean,
    now: Long,
    onOpenProject: (ProjectSummary) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    val idleSubtitle = if (activeChats == 0) projectCounts(project) else ""
    val containerColor by animateColorAsState(
        targetValue =
            if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "projectContainerColor",
    )
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
        colors = ListItemDefaults.segmentedColors(containerColor = containerColor),
        overlineContent = if (showGateway) ({ Text(project.gateway.name) }) else null,
        supportingContent = {
            WorkingSubtitle(
                status = status,
                streamKey = "${project.gateway.id}/${project.repo}",
                workingContent = if (activeChats > 0) ({ ActiveChatsSubtitle(activeChats) }) else null,
            ) {
                Text(idleSubtitle)
            }
        },
        trailingContent = {
            project.updatedAt?.let { updatedAt ->
                Text(text = relativeTime(updatedAt, now), style = MaterialTheme.typography.labelMedium)
            }
        },
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
