package com.tamimarafat.ferngeist.feature.sessionlist.ui.filters

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.edgeFadeMask
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.input.mouseDragScroll
import com.tamimarafat.ferngeist.feature.sessionlist.R

/** One active filter, ready to render: what the chip says and what its X drops. */
private class Chip(
    val key: String,
    val label: String?,
    val description: String,
    val onClear: () -> Unit,
    val leadingIcon: (@Composable () -> Unit)? = null,
)

/**
 * The active filters as chips: tap one to edit the filters, tap its X to drop that filter alone.
 *
 * @param project the project filter to show, or null when the list spans every project.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActiveFilterChips(
    agentName: String?,
    agentIconUrl: String?,
    scopeName: String?,
    project: ProjectFilter?,
    onEdit: () -> Unit,
    onClearAgent: () -> Unit,
    onClearScope: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    // A blank icon is a gateway sending no logo, not a URL to fetch.
    val agentLogo = agentIconUrl?.takeIf { it.isNotBlank() }
    val listState = rememberLazyListState()
    val chips =
        listOfNotNull(
            project?.let { filter ->
                Chip(
                    key = "project",
                    label = filter.name,
                    description = stringResource(R.string.sessionlist_filter_project_selected, filter.name),
                    onClear = filter.onClear,
                    leadingIcon = { ChipIcon(Icons.Outlined.FolderOpen) },
                )
            },
            agentName?.let { name ->
                Chip(
                    key = "agent",
                    // The logo is the chip: the name only appears when the agent has none.
                    label = name.takeIf { agentLogo == null },
                    description = stringResource(R.string.sessionlist_filter_agent_selected, name),
                    onClear = onClearAgent,
                    leadingIcon = agentLogo?.let { url -> { AgentFilterLogo(url) } },
                )
            },
            scopeName?.let { name ->
                Chip(
                    key = "scope",
                    label = name,
                    description = stringResource(R.string.sessionlist_filter_worktree_selected, name),
                    onClear = onClearScope,
                    leadingIcon = { ChipIcon(Icons.Outlined.AccountTree) },
                )
            },
        )
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
        items(chips, key = { it.key }) { chip ->
            ActiveFilterChip(
                modifier =
                    Modifier.animateItem(
                        fadeInSpec = motion.fastEffectsSpec(),
                        placementSpec = motion.fastSpatialSpec(),
                        fadeOutSpec = motion.fastEffectsSpec(),
                    ),
                label = chip.label,
                description = chip.description,
                leadingIcon = chip.leadingIcon,
                onEdit = onEdit,
                onClear = chip.onClear,
            )
        }
    }
}

@Composable
private fun ChipIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
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
