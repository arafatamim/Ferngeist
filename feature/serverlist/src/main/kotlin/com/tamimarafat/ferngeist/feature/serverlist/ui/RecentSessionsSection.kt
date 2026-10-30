package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.RecentSessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.status.ActivityIconContainer
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingSubtitle
import com.tamimarafat.ferngeist.core.common.ui.status.rememberActivityRotation
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusContainerColor
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusContentColor
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusLabel
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.iconUrl
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
internal fun ContinueSessionCard(
    session: RecentSession,
    status: TurnStatus,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The hero changes shape when pressed: corners spring in toward a squarer shape.
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 28.dp,
        // Spatial motion: overshoot on press, clean settle on release.
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "continueCorner",
    )

    // Accent pops in with a bouncy spring on first composition, from a visible size: a
    // badge growing out of nothing reads as an elevation change, not an arrival.
    val pop = remember { Animatable(BADGE_ENTRY_SCALE) }
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spatialSpec,
        )
    }
    // The quarter turn settles on entry; activity rotation returns to rest when idle.
    val activityRotation = rememberActivityRotation(periodMillis = 4_000, active = status == TurnStatus.WORKING)
    val rotation =
        remember {
            derivedStateOf { activityRotation.value + (1f - pop.value) / (1f - BADGE_ENTRY_SCALE) * BADGE_ENTRY_TURN }
        }

    val description = turnStatusLabel(status)
    val shape = RoundedCornerShape(corner)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { description?.let { stateDescription = it } }
                // clip() before clickable so the ripple is bounded by the morphing
                // shape instead of spilling into a full-width rectangle. Same wiring
                // as ServerCard: Card(onClick=) gives no control over the indication,
                // and its built-in ripple never showed on this card.
                .clip(shape)
                .handCursor()
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                ),
        shape = shape,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.turnStatusContainerColor(
                        status,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                contentColor =
                    MaterialTheme.colorScheme.turnStatusContentColor(
                        status,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ),
    ) {
        ContinueSessionCardContent(
            session = session,
            status = status,
            rotation = rotation,
            pop = pop.value,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
private fun ContinueSessionCardContent(
    session: RecentSession,
    status: TurnStatus,
    rotation: State<Float>,
    pop: Float,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RotatingCloverBadge(
            session = session,
            status = status,
            rotation = rotation,
            pop = pop,
            contentDescription = stringResource(R.string.serverlist_continue_resume_desc),
        )
        Column(modifier = Modifier.weight(1f)) {
            SharedSessionTitle(
                session = session,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
            // The clover holds a real logo, so the name would be redundant. An agent
            // on the fallback glyph gets its name here, where it carries information.
            FolderNamePrefix(
                cwd = session.cwd,
                tint =
                    MaterialTheme.colorScheme.turnStatusContentColor(
                        status,
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    ),
                after = session.target.name.takeIf { session.target.iconUrl == null },
            )
            WorkingSubtitle(
                status = status,
                streamKey = "${session.serverId}/${session.sessionId}",
                accent = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                // Same device-icon treatment as ServerSubtitle: gateway-sourced agents
                // get the icon, manual targets keep the bare host.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.target is LaunchableTarget.GatewayAgent) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = stringResource(R.string.serverlist_card_from_gateway),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        text = targetDeviceLabel(session.target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val BADGE_ENTRY_SCALE = 0.6f

/** Degrees the clover turns while it settles from [BADGE_ENTRY_SCALE]. */
private const val BADGE_ENTRY_TURN = -90f

@Composable
private fun targetDeviceLabel(target: LaunchableTarget): String =
    when (target) {
        is LaunchableTarget.GatewayAgent -> target.gatewaySource.name
        is LaunchableTarget.Manual ->
            stringResource(
                R.string.serverlist_gateway_url_format,
                target.server.scheme,
                target.server.host,
            )
    }

/**
 * Just the leaf directory name — the full path is noise at card width, and the
 * folder is what identifies the working copy. Null when no cwd is recorded.
 */
internal fun workingFolderName(cwd: String?): String? =
    cwd
        ?.trim()
        ?.trimEnd('/', '\\')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() && !it.matches(driveRoot) }

private val driveRoot = Regex("""[A-Za-z]:""")

/** Separator between the folder and the agent name on the same line. */
private const val BULLET_SEPARATOR = "•"

/**
 * The folder line: a folder icon and leaf name, optionally followed by [after] as
 * "folder - name".
 *
 * [after] carries the agent name only when the badge cannot. A registry logo already
 * names the agent, so repeating it in text is the redundancy the badge removed. An
 * agent with no registry icon falls back to a generic glyph, which names nothing, so
 * the name goes here instead.
 */
@Composable
private fun FolderNamePrefix(
    cwd: String?,
    tint: Color,
    after: String? = null,
) {
    val folder = workingFolderName(cwd)
    if (folder == null && after == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 6.dp),
    ) {
        if (folder != null) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
            Text(
                text = folder,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (folder != null && after != null) {
            Text(
                text = BULLET_SEPARATOR,
                style = MaterialTheme.typography.bodySmall,
                color = tint.copy(alpha = 0.6f),
            )
        }
        if (after != null) {
            Text(
                text = after,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RotatingCloverBadge(
    session: RecentSession,
    status: TurnStatus,
    rotation: State<Float>,
    pop: Float,
    contentDescription: String,
) {
    Box(contentAlignment = Alignment.Center) {
        // Rotating clover behind the (upright) icon.
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        rotationZ = rotation.value
                        scaleX = pop
                        scaleY = pop
                    }.clip(MaterialShapes.Clover4Leaf.toShape())
                    .background(
                        if (status == TurnStatus.NEEDS_ATTENTION) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
        )
        // The agent's logo riding the clover: the clover is the hero's animated
        // accent, so the mark sits in its shape rather than being framed again.
        AgentIconBadge(
            iconUrl = session.target.iconUrl,
            fallback = Icons.Rounded.History,
            size = 22.dp,
            containerSize = null,
            tint =
                if (status == TurnStatus.NEEDS_ATTENTION) {
                    MaterialTheme.colorScheme.onTertiary
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            contentDescription = contentDescription,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedSessionTitle(
    session: RecentSession,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    with(sharedTransitionScope) {
        Text(
            text = sessionTitleOrNull(session.title) ?: stringResource(R.string.serverlist_untitled_session),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.sharedBounds(
                    sharedContentState =
                        rememberSharedContentState(
                            key = RecentSessionTitleSharedBoundsKey(session.sessionId),
                        ),
                    animatedVisibilityScope = animatedContentScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                ),
        )
    }
}

@Composable
private fun RecentSessionCardRow(
    session: RecentSession,
    status: TurnStatus,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityIconContainer(status = status, size = 28.dp) {
            AgentIconBadge(
                target = session.target,
                fallback = Icons.Default.History,
                size = 18.dp,
                containerSize = null,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            with(sharedTransitionScope) {
                Text(
                    text = sessionTitleOrNull(session.title) ?: stringResource(R.string.serverlist_untitled_session),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.sharedBounds(
                            sharedContentState =
                                rememberSharedContentState(
                                    key = RecentSessionTitleSharedBoundsKey(session.sessionId),
                                ),
                            animatedVisibilityScope = animatedContentScope,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                        ),
                )
            }
            // A registry logo names the agent, so the name would be redundant. An
            // agent without one falls back to a generic glyph that names nothing,
            // so the name goes on the folder line instead.
            FolderNamePrefix(
                cwd = session.cwd,
                tint =
                    MaterialTheme.colorScheme.turnStatusContentColor(
                        status,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                after = session.target.name.takeIf { session.target.iconUrl == null },
            )
            WorkingSubtitle(
                status = status,
                streamKey = "${session.serverId}/${session.sessionId}",
            ) {
                ServerSubtitle(
                    server = session.target,
                    hasSavedAuthMethod = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun RecentSessionCard(
    session: RecentSession,
    status: TurnStatus,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().handCursor(),
        colors =
            CardDefaults.cardColors(
                // One tonal step above the Low backdrop, opaque so onSurface stays its pair.
                containerColor =
                    MaterialTheme.colorScheme.turnStatusContainerColor(
                        status,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentColor =
                    MaterialTheme.colorScheme.turnStatusContentColor(
                        status,
                        MaterialTheme.colorScheme.onSurface,
                    ),
            ),
    ) {
        RecentSessionCardRow(
            session = session,
            status = status,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }
}
