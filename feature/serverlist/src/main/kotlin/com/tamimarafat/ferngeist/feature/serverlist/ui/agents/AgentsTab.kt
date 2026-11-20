package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.status.isActive
import com.tamimarafat.ferngeist.core.common.ui.status.statusesFor
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.GatewayHealth
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState

private const val WORKING_FILTER = "working"
internal const val DISCONNECTED_ALPHA = 0.5f
private const val AGENT_COUNT_THRESHOLD = 6

/**
 * The home sheet's Agents page: agents grouped by the computer they run on, every working or
 * unread-finished, attention or error agent promoted to a full-width hero tile, direct
 * agents last as rows.
 */
@Composable
internal fun AgentsTab(
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String>,
    gatewayHealth: Map<String, GatewayHealth>,
    turnStatuses: Map<String, TurnActivity>,
    listState: LazyListState,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    // null shows everything; otherwise WORKING_FILTER or a gateway id.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val activity =
        servers.associate { target ->
            target.id to
                agentCardPresentation(
                    gatewayAgent = target is LaunchableTarget.GatewayAgent,
                    chats = turnStatuses.statusesFor(target.id).values,
                    connection = ServerConnectionUiState.from(target.id, uiState, liveServerIds),
                )
        }
    // Computers first, direct agents last.
    val groups =
        servers
            .groupBy { (it as? LaunchableTarget.GatewayAgent)?.gatewaySource }
            .toList()
            .sortedBy { it.first == null }
    val shown = filterGroups(groups, filter, activity)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (groups.size > 1) {
            item(key = "filters") {
                AgentFilters(groups.mapNotNull { it.first }, filter, { filter = it }, agentItemMotion())
            }
        }
        shown.forEach { (gateway, targets) ->
            // Connected is the default, so an unreachable computer is marked and faded.
            val disconnected = gateway != null && gatewayHealth[gateway.id] == GatewayHealth.UNREACHABLE
            val fade = if (disconnected) Modifier.alpha(DISCONNECTED_ALPHA) else Modifier
            item(key = "group:${gateway?.id}") { AgentGroupHeader(gateway, disconnected, agentItemMotion()) }
            if (gateway == null) {
                items(targets, key = { "agent:${it.id}" }) { target ->
                    val state = activity.getValue(target.id)
                    DirectAgentRow(
                        target = target,
                        status = state.status,
                        connection = state.connection,
                        tone = state.tone,
                        onClick = { onConnect(target) },
                        onEdit = { onEdit(target) },
                        onDelete = { onDelete(target.id) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(horizontal = 16.dp).then(agentItemMotion()),
                    )
                }
                return@forEach
            }
            // Promoted agents span both columns, sorted by working and unread-finished chat counts.
            val (heroes, resting) = targets.partition { activity.getValue(it.id).variant == AgentCardVariant.HERO }
            heroes
                .sortedWith(
                    compareByDescending<LaunchableTarget> { activity.getValue(it.id).working }
                        .thenByDescending { activity.getValue(it.id).unreadFinished },
                ).forEach { target ->
                    item(key = "agent:${target.id}") {
                        val state = activity.getValue(target.id)
                        AgentHeroTile(
                            target = target,
                            status = state.status,
                            working = state.working,
                            headlineStatus = state.headlineStatus,
                            connection = state.connection,
                            tone = state.tone,
                            onClick = { onConnect(target) },
                            onEdit = { onEdit(target) },
                            onDelete = { onDelete(target.id) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                            modifier = Modifier.padding(horizontal = 16.dp).then(agentItemMotion()).then(fade),
                        )
                    }
                }
            resting.chunked(2).forEach { pair ->
                item(key = "agents:${pair.joinToString { it.id }}") {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp).then(agentItemMotion()).then(fade),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEachIndexed { index, target ->
                            val state = activity.getValue(target.id)
                            AgentTile(
                                target = target,
                                status = state.status,
                                connection = state.connection,
                                tone = state.tone,
                                shape = tileShape(index, pair.size),
                                onClick = { onConnect(target) },
                                onEdit = { onEdit(target) },
                                onDelete = { onDelete(target.id) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedContentScope = animatedContentScope,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "filter-empty") {
                Text(
                    stringResource(R.string.home_agents_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).then(agentItemMotion()),
                )
            }
        }
        item(key = "summary") { AgentsSummary(activity.values, servers.size, agentItemMotion()) }
        // ponytail: clearance lets the final card scroll above the bottom controls.
        item(key = "bottom-clearance") { Spacer(modifier = Modifier.height(160.dp)) }
    }
}

private fun filterGroups(
    groups: List<Pair<GatewaySource?, List<LaunchableTarget>>>,
    filter: String?,
    activity: Map<String, AgentCardPresentation>,
): List<Pair<GatewaySource?, List<LaunchableTarget>>> =
    groups
        .map { (gateway, targets) ->
            gateway to
                targets.filter { target ->
                    when (filter) {
                        null -> true
                        WORKING_FILTER -> activity.getValue(target.id).status.isActive
                        else -> gateway?.id == filter
                    }
                }
        }.filter { it.second.isNotEmpty() }

/** Filtering reflows the list: fade what leaves, glide what stays. */
@Composable
private fun LazyItemScope.agentItemMotion(): Modifier {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    return Modifier.animateItem(
        fadeInSpec = effects,
        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        fadeOutSpec = effects,
    )
}

@Composable
private fun AgentsSummary(
    activity: Collection<AgentCardPresentation>,
    agentCount: Int,
    modifier: Modifier = Modifier,
) {
    val working = activity.count { it.status == TurnStatus.WORKING }
    val attention = activity.count { it.status == TurnStatus.NEEDS_ATTENTION }
    val parts =
        listOfNotNull(
            pluralStringResource(R.plurals.home_agents_working_count, working, working).takeIf { working > 0 },
            pluralStringResource(R.plurals.home_agents_attention_count, attention, attention).takeIf { attention > 0 },
        )
    if (parts.isEmpty() && agentCount <= AGENT_COUNT_THRESHOLD) return
    Text(
        parts.joinToString(" · ").ifEmpty { pluralStringResource(R.plurals.home_agents_count, agentCount, agentCount) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun AgentFilters(
    gateways: List<GatewaySource>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.home_agents_all)) },
        )
        FilterChip(
            selected = selected == WORKING_FILTER,
            onClick = { onSelect(WORKING_FILTER) },
            label = { Text(stringResource(R.string.home_agents_working)) },
            leadingIcon = {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            },
        )
        gateways.forEach { gateway ->
            FilterChip(
                selected = selected == gateway.id,
                onClick = { onSelect(gateway.id) },
                label = { Text(gateway.name) },
            )
        }
    }
}
