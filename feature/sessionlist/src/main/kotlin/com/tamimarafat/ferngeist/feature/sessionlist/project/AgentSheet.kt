package com.tamimarafat.ferngeist.feature.sessionlist.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.sessionlist.R
import com.tamimarafat.ferngeist.feature.sessionlist.ui.selectorMaxWidth
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
import kotlinx.coroutines.launch

/**
 * The agents a new chat can start with, in [agentsInSheetOrder]; the first is marked
 * "Last used here" when [firstLastUsed].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentSheet(
    agents: List<LaunchableTarget.GatewayAgent>,
    firstLastUsed: Boolean,
    statuses: Map<String, GatewayProjectLine.Agent>,
    onDismiss: () -> Unit,
    onPick: (LaunchableTarget.GatewayAgent) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val coroutineScope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            SheetTitle(stringResource(R.string.sessionlist_project_pick_agent))
            // Slide the sheet away first; the chat then opens on a settled screen.
            AgentRows(agents, firstLastUsed, statuses) { agent ->
                coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onPick(agent) }
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
    )
}

/** One segmented group of [agents]; an agent its gateway cannot launch stays listed, disabled. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentRows(
    agents: List<LaunchableTarget.GatewayAgent>,
    firstLastUsed: Boolean,
    statuses: Map<String, GatewayProjectLine.Agent>,
    onPick: (LaunchableTarget.GatewayAgent) -> Unit,
) {
    agents.forEachIndexed { index, agent ->
        SegmentedListItem(
            onClick = { onPick(agent) },
            enabled = statuses[agent.id]?.launchAvailable != false,
            shapes = ListItemDefaults.segmentedShapes(index, agents.size),
            modifier = Modifier.handCursor(),
            leadingContent = { AgentIconBadge(agent, fallback = Icons.Rounded.SmartToy) },
            supportingContent = {
                val status = statuses[agent.id]
                val reason =
                    when (status?.status) {
                        "unsupported" -> stringResource(R.string.sessionlist_agent_listing_unsupported)
                        "authRequired" -> stringResource(R.string.sessionlist_agent_auth_required)
                        "failed" -> status.error ?: stringResource(R.string.sessionlist_agent_listing_failed)
                        else ->
                            if (index == 0 &&
                                firstLastUsed
                            ) {
                                stringResource(R.string.sessionlist_project_last_used)
                            } else {
                                null
                            }
                    }
                reason?.let { Text(it) }
            },
        ) {
            Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The project toolbar's agent filter: each agent by its icon, "All agents" by the chats glyph. */
@Composable
internal fun AgentFilter(
    agents: List<LaunchableTarget.GatewayAgent>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = agents.firstOrNull { it.id == selectedId }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.handCursor(),
            // The vibrant toolbar's container is primaryContainer; primary is not a guaranteed pair.
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
        ) {
            if (selected == null) {
                Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
            } else {
                // Bare, like the selector glyphs beside it; a framed badge would sit heavier than they do.
                AgentIconBadge(selected, fallback = Icons.Rounded.SmartToy, size = 18.dp, containerSize = null)
            }
            Text(
                text = selected?.name ?: stringResource(R.string.sessionlist_all_agents),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = selectorMaxWidth).padding(start = 6.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_all_agents)) },
                leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    leadingIcon = { AgentIconBadge(agent, fallback = Icons.Rounded.SmartToy) },
                    onClick = {
                        expanded = false
                        onSelect(agent.id)
                    },
                )
            }
        }
    }
}
