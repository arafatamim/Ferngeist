package com.tamimarafat.ferngeist.feature.serverlist.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentLogo
import com.tamimarafat.ferngeist.core.common.ui.AgentMark
import com.tamimarafat.ferngeist.core.common.ui.agentIconTransition
import com.tamimarafat.ferngeist.core.common.ui.chat.StatusWaveLine
import com.tamimarafat.ferngeist.core.common.ui.chat.rememberStatusLineState
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.CardTone
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.HeroBody
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.StatusLabelLine
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.cardCornerRadius
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.containerColor
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.contentColor
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.headline
import com.tamimarafat.ferngeist.feature.serverlist.ui.cards.waveLineColor

private const val CONNECTING_MARK_SCALE = 1.25f
private const val HERO_CONNECTING_MARK_SCALE = 1.6f

/** A promoted agent: a full-width tile with its most important activity. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentHeroTile(
    target: LaunchableTarget,
    status: TurnStatus,
    working: Int,
    connection: ServerConnectionUiState,
    tone: CardTone,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    AgentSurface(
        target = target,
        shape = RoundedCornerShape(cardCornerRadius),
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
        HeroBody(
            statusRow = {
                // An unread finished turn is the promotion itself, so it needs no label of its own.
                val headline = status.headline
                StatusLabelLine(
                    label =
                        when {
                            headline == TurnStatus.WORKING ->
                                pluralStringResource(R.plurals.home_agents_chats_working, working, working)
                            headline != null -> turnStatusLabel(headline)
                            else -> null
                        },
                    status = status,
                )
            },
            nameRow = {
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
                    AgentBadge(target, connection.isConnecting, connection.isConnected, status, size = 44, hero = true)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(20.dp),
        )
    }
}

/** A gateway agent in the two-up grid: status on top, name at the foot. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentTile(
    target: LaunchableTarget,
    status: TurnStatus,
    connection: ServerConnectionUiState,
    tone: CardTone,
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
                Box(Modifier.weight(1f).padding(end = 12.dp)) {
                    StatusWaveLine(rememberStatusLineState(status), color = waveLineColor)
                }
                AgentBadge(target, connection.isConnecting, connection.isConnected, status, size = 36)
            }
            AgentName(
                target = target,
                style = MaterialTheme.typography.titleMediumEmphasized,
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
    tone: CardTone,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    AgentSurface(
        target = target,
        shape = RoundedCornerShape(cardCornerRadius),
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
            AgentBadge(target, connection.isConnecting, connection.isConnected, status, size = 40)
            Column(Modifier.weight(1f)) {
                AgentName(
                    target = target,
                    style = MaterialTheme.typography.titleMedium,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
                ServerSubtitle(target, hasSavedAuthMethod = target.preferredAuthMethodId?.isNotBlank() == true)
            }
            StatusWaveLine(
                rememberStatusLineState(status),
                Modifier.widthIn(max = 56.dp),
                color = waveLineColor,
            )
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
    connecting: Boolean,
    connected: Boolean,
    status: TurnStatus,
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AgentLogo(
                    target = target,
                    fallback = Icons.Default.SmartToy,
                    modifier = Modifier.scale(if (hero) HERO_CONNECTING_MARK_SCALE else CONNECTING_MARK_SCALE),
                    loading = true,
                )
            }
        } else {
            AgentMark(target, size = size.dp, active = connected, status = status)
        }
    }
}
