package com.tamimarafat.ferngeist.feature.serverlist.ui.projects

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.model.projectName
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * The home's Projects rows. Inert until the project screen lands; quiet by design so the hero
 * stays the screen's single emphasized element.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProjectsSection(
    projects: List<ProjectSummary>,
    liveServerIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val showGateway = projects.map { it.gateway.id }.distinct().size > 1
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        projects.forEachIndexed { index, project ->
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, projects.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                overlineContent = if (showGateway) ({ Text(project.gateway.name) }) else null,
                supportingContent = { Text(projectCounts(project)) },
                // shortcut: "an agent of the project holds a live chat" stands in for "a chat in it
                // streams"; switch to a per-chat signal once the hub exposes one as a flow.
                trailingContent = {
                    ProjectTrailing(project, live = project.chats.any { it.summary.serverId in liveServerIds })
                },
            ) {
                Text(
                    text = projectName(project.repo),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun projectCounts(project: ProjectSummary): String {
    val chats = pluralStringResource(R.plurals.serverlist_project_chats, project.chats.size, project.chats.size)
    val worktrees = project.checkout?.worktrees?.size ?: return chats
    return chats + " · " + pluralStringResource(R.plurals.serverlist_project_worktrees, worktrees, worktrees)
}

@Composable
private fun ProjectTrailing(
    project: ProjectSummary,
    live: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
        }
        project.updatedAt?.let { updatedAt ->
            val now = System.currentTimeMillis()
            Text(
                text = DateUtils.getRelativeTimeSpanString(updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
