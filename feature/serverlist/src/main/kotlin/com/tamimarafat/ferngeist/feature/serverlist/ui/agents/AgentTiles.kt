package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.agentIconTransition
import com.tamimarafat.ferngeist.core.common.ui.chat.StatusWaveLine
import com.tamimarafat.ferngeist.core.common.ui.chat.rememberStatusLineState
import com.tamimarafat.ferngeist.core.common.ui.status.ActivityIconContainer
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.R

private const val CONNECTING_MARK_SCALE = 1.25f
private const val HERO_CONNECTING_MARK_SCALE = 1.6f
private val tileOuterCorner = 28.dp
private val tileInnerCorner = 8.dp

/** A promoted agent: a full-width tile with its most important activity. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentHeroTile(
    target: LaunchableTarget,
    status: TurnStatus,
    working: Int,
    headlineStatus: TurnStatus?,
    connection: ServerConnectionUiState,
    tone: AgentCardTone,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    AgentSurface(
        target = target,
        shape = RoundedCornerShape(32.dp),
        color = tone.containerColor,
        contentColor = tone.contentColor,
        enabled = !connection.isConnecting,
        onClick = onClick,
        onEdit = onEdit,
        onDelete = onDelete,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        var promoted by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { promoted = true }
        val progress by animateFloatAsState(
            targetValue = if (promoted) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "agentHeroPositions",
        )
        Layout(
            modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(20.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // An unread finished turn is the promotion itself, so it needs no label of its own.
                    val label =
                        when {
                            headlineStatus == TurnStatus.WORKING ->
                                pluralStringResource(R.plurals.home_agents_chats_working, working, working)
                            headlineStatus != null -> turnStatusLabel(headlineStatus)
                            else -> null
                        }
                    label?.let { Text(it, style = MaterialTheme.typography.labelLargeEmphasized) }
                    StatusWaveLine(
                        state = rememberStatusLineState(status),
                        modifier = Modifier.weight(1f, fill = false),
                        startPadding = if (label != null) 12.dp else 0.dp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        AgentName(
                            target = target,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                            maxLines = 2,
                        )
                    }
                    AgentBadge(target, status, connection.isConnecting, size = 44, hero = true)
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val statusRow = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
            val nameRow = measurables[1].measure(loose.copy(minWidth = constraints.maxWidth))
            val height = constraints.constrainHeight(statusRow.height + nameRow.height)
            layout(constraints.maxWidth, height) {
                statusRow.placeRelative(0, lerp(0, height - statusRow.height, progress))
                nameRow.placeRelative(0, lerp(height - nameRow.height, 0, progress))
            }
        }
    }
}

/** Shape for tile [index] of a two-up row of [count]: the inner bottom corners pinch toward each other. */
internal fun tileShape(
    index: Int,
    count: Int,
): RoundedCornerShape =
    when {
        count == 1 -> RoundedCornerShape(tileOuterCorner)
        index == 0 -> RoundedCornerShape(tileOuterCorner, tileOuterCorner, tileInnerCorner, tileOuterCorner)
        else -> RoundedCornerShape(tileOuterCorner, tileOuterCorner, tileOuterCorner, tileInnerCorner)
    }

/** A gateway agent in the two-up grid: status on top, name at the foot. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentTile(
    target: LaunchableTarget,
    status: TurnStatus,
    connection: ServerConnectionUiState,
    tone: AgentCardTone,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    AgentSurface(
        target = target,
        shape = shape,
        color = tone.containerColor,
        contentColor = tone.contentColor,
        enabled = !connection.isConnecting,
        onClick = onClick,
        onEdit = onEdit,
        onDelete = onDelete,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The Box keeps the badge at the end while the line is hidden and gives it room to grow into.
                Box(Modifier.weight(1f).padding(end = 12.dp)) { StatusWaveLine(rememberStatusLineState(status)) }
                AgentBadge(target, status, connection.isConnecting, size = 36)
            }
            AgentName(
                target = target,
                style = MaterialTheme.typography.titleLargeEmphasized,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
                maxLines = 2,
            )
        }
    }
}

/** A direct agent: a compact row, since it has no computer to group under. */
@Composable
internal fun DirectAgentRow(
    target: LaunchableTarget,
    status: TurnStatus,
    connection: ServerConnectionUiState,
    tone: AgentCardTone,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    AgentSurface(
        target = target,
        shape = RoundedCornerShape(20.dp),
        color = tone.containerColor,
        contentColor = tone.contentColor,
        enabled = !connection.isConnecting,
        onClick = onClick,
        onEdit = onEdit,
        onDelete = onDelete,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AgentBadge(target, status, connection.isConnecting, size = 40)
            Column(Modifier.weight(1f)) {
                AgentName(
                    target = target,
                    style = MaterialTheme.typography.titleMedium,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
                ServerSubtitle(target, hasSavedAuthMethod = target.preferredAuthMethodId?.isNotBlank() == true)
            }
            StatusWaveLine(rememberStatusLineState(status), Modifier.widthIn(max = 56.dp))
        }
    }
}

/** Section title for one computer, or for the direct agents. */
@Composable
internal fun AgentGroupHeader(
    gateway: GatewaySource?,
    disconnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (gateway == null) Icons.Outlined.Link else Icons.Outlined.Computer,
            contentDescription = null,
            modifier = Modifier.size(18.dp).alpha(if (disconnected) DISCONNECTED_ALPHA else 1f),
            tint = colors.onSurfaceVariant,
        )
        Text(
            gateway?.name ?: stringResource(R.string.home_agents_direct),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (disconnected) DISCONNECTED_ALPHA else 1f),
        )
        if (disconnected) {
            Text(
                stringResource(R.string.home_agents_offline),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentBadge(
    target: LaunchableTarget,
    status: TurnStatus,
    connecting: Boolean,
    size: Int,
    hero: Boolean = false,
) {
    AnimatedContent(
        targetState = connecting,
        modifier = Modifier.size(size.dp),
        transitionSpec = { agentIconTransition() },
        label = "agentConnectionBadge",
    ) { isConnecting ->
        if (isConnecting) {
            AgentIconBadge(
                target = target,
                fallback = Icons.Default.SmartToy,
                modifier = Modifier.scale(if (hero) HERO_CONNECTING_MARK_SCALE else CONNECTING_MARK_SCALE),
                containerSize = size.dp,
                loading = true,
            )
        } else if (hero && status in setOf(TurnStatus.WORKING, TurnStatus.FINISHED)) {
            // The wide card keeps its activity badge still, with no extra frame around its icon.
            Box(
                Modifier
                    .size(size.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AgentIconBadge(
                    target,
                    fallback = Icons.Default.SmartToy,
                    size = 22.dp,
                    containerSize = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            ActivityIconContainer(size = size.dp, status = status) {
                AgentIconBadge(target = target, fallback = Icons.Default.SmartToy, containerSize = null)
            }
        }
    }
}
