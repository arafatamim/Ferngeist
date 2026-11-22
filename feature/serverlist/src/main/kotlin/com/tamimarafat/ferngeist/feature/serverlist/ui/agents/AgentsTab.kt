package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.status.CardPresentation
import com.tamimarafat.ferngeist.core.common.ui.status.CardVariant
import com.tamimarafat.ferngeist.core.common.ui.status.cardPresentation
import com.tamimarafat.ferngeist.core.common.ui.status.statusesFor
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.GatewayHealth
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.homeItemMotion
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.tileShape

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
    gatewayCount: Int,
    turnStatuses: Map<String, TurnActivity>,
    listState: LazyListState,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val connections = servers.associate { it.id to ServerConnectionUiState.from(it.id, uiState, liveServerIds) }
    val activity =
        servers.associate { target ->
            target.id to
                cardPresentation(
                    chats = turnStatuses.statusesFor(target.id).values,
                    live = connections.getValue(target.id).isConnected,
                    failed = connections.getValue(target.id).isFailed,
                    direct = target !is LaunchableTarget.GatewayAgent,
                )
        }
    // Computers first, direct agents last.
    val groups =
        servers
            .groupBy { (it as? LaunchableTarget.GatewayAgent)?.gatewaySource }
            .toList()
            .sortedBy { it.first == null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var featuredAvailable = true
        groups.forEach { (gateway, targets) ->
            // Connected is the default, so an unreachable computer is marked and faded.
            val disconnected = gateway != null && gatewayHealth[gateway.id] == GatewayHealth.UNREACHABLE
            val fade = if (disconnected) Modifier.alpha(DISCONNECTED_ALPHA) else Modifier
            if (gateway == null || gatewayCount != 1) {
                item(key = "group:${gateway?.id}") {
                    AgentGroupHeader(gateway, disconnected, Modifier.homeItemMotion(this))
                }
            }
            if (gateway == null) {
                items(targets, key = { "agent:${it.id}" }) { target ->
                    val state = activity.getValue(target.id)
                    DirectAgentRow(
                        target = target,
                        status = state.activity.status,
                        connection = connections.getValue(target.id),
                        tone = state.tone,
                        onClick = { onConnect(target) },
                        onEdit = { onEdit(target) },
                        onDelete = { onDelete(target.id) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this),
                    )
                }
                return@forEach
            }
            // Promoted agents span both columns, sorted by working and unread-finished chat counts.
            val (heroes, resting) = targets.partition { activity.getValue(it.id).variant == CardVariant.HERO }
            heroes
                .sortedWith(
                    compareByDescending<LaunchableTarget> { activity.getValue(it.id).working }
                        .thenByDescending { activity.getValue(it.id).unreadFinished },
                ).forEach { target ->
                    val featured = featuredAvailable
                    featuredAvailable = false
                    item(key = "agent:${target.id}") {
                        val state = activity.getValue(target.id)
                        AgentHeroTile(
                            target = target,
                            status = state.activity.status,
                            working = state.working,
                            connection = connections.getValue(target.id),
                            tone = state.tone,
                            featured = featured,
                            onClick = { onConnect(target) },
                            onEdit = { onEdit(target) },
                            onDelete = { onDelete(target.id) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                            modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this).then(fade),
                        )
                    }
                }
            resting.chunked(2).forEach { pair ->
                item(key = "agents:${pair.joinToString { it.id }}") {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp).homeItemMotion(this).then(fade),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEachIndexed { index, target ->
                            val state = activity.getValue(target.id)
                            AgentTile(
                                target = target,
                                status = state.activity.status,
                                connection = connections.getValue(target.id),
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
        item(key = "summary") { AgentsSummary(activity.values, servers.size, Modifier.homeItemMotion(this)) }
        // ponytail: clearance lets the final card scroll above the bottom controls.
        item(key = "bottom-clearance") { Spacer(modifier = Modifier.height(160.dp)) }
    }
}

@Composable
private fun AgentsSummary(
    activity: Collection<CardPresentation>,
    agentCount: Int,
    modifier: Modifier = Modifier,
) {
    val working = activity.count { it.activity.status == TurnStatus.WORKING }
    val attention = activity.count { it.activity.status == TurnStatus.NEEDS_ATTENTION }
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
