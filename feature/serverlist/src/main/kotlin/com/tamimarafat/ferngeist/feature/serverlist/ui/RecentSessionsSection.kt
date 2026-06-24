package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.RecentSessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AgentsBackdrop(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Reference distance for rubber-band falloff: larger = looser overscroll.
    val overscrollRefPx = with(density) { 140.dp.toPx() }
    // Upward overscroll travel is capped, and the sheet is extended below the screen
    // by this same amount, so dragging up can never lift the sheet's bottom edge off
    // the screen and expose the darker back layer underneath.
    val sheetOverhang = 120.dp
    val maxTopOverscrollPx = with(density) { sheetOverhang.toPx() }
    var topZonePx by remember { mutableIntStateOf(0) } // hero + gap above the sheet
    var recentsPx by remember { mutableIntStateOf(0) } // hidden recents block height
    val sheetOffset = remember { Animatable(0f) } // 0 = covering recents, recentsPx = fully revealed
    val canReveal = olderSessions.isNotEmpty()

    LaunchedEffect(recentsPx) {
        if (sheetOffset.value > recentsPx) sheetOffset.snapTo(recentsPx.toFloat())
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetHeight = maxHeight + sheetOverhang
        // BACK LAYER — hero stays visible; recents sit hidden behind the sheet until revealed.
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.onSizeChanged { topZonePx = it.height }) {
                heroSession?.let { hero ->
                    ContinueSessionCard(
                        session = hero,
                        onClick = { onResumeSession(hero) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { recentsPx = it.height },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                olderSessions.forEach { session ->
                    RecentSessionCard(
                        session = session,
                        onClick = { onResumeSession(session) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // FRONT LAYER — the draggable "Your agents" sheet.
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .offset { IntOffset(0, topZonePx + sheetOffset.value.roundToInt()) }
                    .then(
                        if (canReveal) {
                            Modifier.draggable(
                                orientation = Orientation.Vertical,
                                state =
                                    rememberDraggableState { delta ->
                                        scope.launch {
                                            val max = recentsPx.toFloat()
                                            val current = sheetOffset.value
                                            // Past an edge and pushing further out: rubber-band with
                                            // progressively stronger resistance. Otherwise track 1:1.
                                            val pushingOut =
                                                (current <= 0f && delta < 0f) ||
                                                    (current >= max && delta > 0f)
                                            val applied =
                                                if (pushingOut) {
                                                    val overshoot =
                                                        if (current <= 0f) -current else current - max
                                                    delta * (1f / (1f + overshoot / overscrollRefPx))
                                                } else {
                                                    delta
                                                }
                                            // Clamp upward travel so the sheet's bottom
                                            // never lifts past its overhang.
                                            sheetOffset.snapTo(
                                                (current + applied).coerceAtLeast(-maxTopOverscrollPx),
                                            )
                                        }
                                    },
                                onDragStopped = { velocity ->
                                    val target =
                                        if (sheetOffset.value > recentsPx / 2f || velocity > 1500f) {
                                            recentsPx.toFloat()
                                        } else {
                                            0f
                                        }
                                    scope.launch {
                                        // Critically damped: the elastic feel lives in the drag-time
                                        // rubber-band; the return settles cleanly to the edge with no
                                        // overshoot, so it never springs back into the overscroll zone.
                                        sheetOffset.animateTo(
                                            target,
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
            // Lightest tone: the foreground sheet sits above the darker background.
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            // Elevated via tonal color, deliberately without a drop shadow.
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(
                    title = stringResource(R.string.serverlist_section_agents),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                servers.forEach { server ->
                    ServerCard(
                        server = server,
                        uiState = uiState,
                        onClick = { onConnect(server) },
                        onEdit = { onEdit(server) },
                        onDelete = { onDelete(server.id) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

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

    // Accent pops in with a bouncy spring on first composition.
    val pop = remember { Animatable(0f) }
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spatialSpec,
        )
    }
    // ...then rotates slowly and continuously to keep drawing the eye to it.
    val infinite = rememberInfiniteTransition(label = "cloverSpin")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Restart),
        label = "cloverRotation",
    )

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(corner),
        interactionSource = interactionSource,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Rotating clover behind the (upright) icon.
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                rotationZ = rotation
                                scaleX = pop.value
                                scaleY = pop.value
                            }
                            .clip(MaterialShapes.Clover4Leaf.toShape())
                            .background(MaterialTheme.colorScheme.primary),
                )
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = stringResource(R.string.serverlist_continue_resume_desc),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier =
                        Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                scaleX = pop.value
                                scaleY = pop.value
                            },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                with(sharedTransitionScope) {
                    Text(
                        text = session.title,
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
                Text(
                    text = session.target.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun RecentSessionCard(
    session: RecentSession,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                with(sharedTransitionScope) {
                    Text(
                        text = session.title,
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
                Text(
                    text = session.target.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ServerSubtitle(
                    server = session.target,
                    hasSavedAuthMethod = false,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
