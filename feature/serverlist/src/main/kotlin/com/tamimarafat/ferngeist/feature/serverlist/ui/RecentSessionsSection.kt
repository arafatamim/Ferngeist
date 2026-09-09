package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.RecentSessionTitleSharedBoundsKey
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AgentsBackdrop(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val overscrollRefPx = with(density) { 140.dp.toPx() }
    val sheetOverhang = 120.dp
    // Sheet can still be dragged up over the hero, but the upward band is
    // stiffer than the reveal-direction band (see rubber-band sites), so
    // covering the hero takes progressively more effort the further you pull.
    val maxTopOverscrollPx = with(density) { sheetOverhang.toPx() }
    var topZonePx by rememberSaveable { mutableIntStateOf(0) } // hero + gap above the sheet
    var recentsPx by rememberSaveable { mutableIntStateOf(0) } // hidden recents block height
    val sheetRevealed = rememberSaveable { mutableStateOf(false) }
    var savedSheetOffset by rememberSaveable { mutableFloatStateOf(0f) }
    // Restore the sheet offset across navigation so the sheet is already at its
    // settled position on return; the shared-title transition then targets stable
    // bounds instead of following the sheet's settle animation.
    val sheetOffset = remember { Animatable(savedSheetOffset) } // 0 = covering recents, recentsPx = fully revealed
    // Persist the live offset only when leaving composition (navigation), not per frame.
    DisposableEffect(sheetOffset) {
        onDispose { savedSheetOffset = sheetOffset.value }
    }
    val agentsScrollState = rememberScrollState()
    val canReveal = olderSessions.isNotEmpty()

    LaunchedEffect(recentsPx, sheetRevealed.value, canReveal) {
        syncSheetOffset(sheetOffset, sheetRevealed, recentsPx, canReveal)
    }

    val sheetConnection =
        rememberSheetConnection(
            scope = scope,
            sheetOffset = sheetOffset,
            scrollState = agentsScrollState,
            recentsPx = recentsPx,
            overscrollRefPx = overscrollRefPx,
            maxTopOverscrollPx = maxTopOverscrollPx,
            canReveal = canReveal,
            sheetRevealed = sheetRevealed,
        )
    BackdropLayers(
        heroSession = heroSession,
        olderSessions = olderSessions,
        servers = servers,
        uiState = uiState,
        liveServerIds = liveServerIds,
        onResumeSession = onResumeSession,
        onConnect = onConnect,
        onEdit = onEdit,
        onDelete = onDelete,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        scope = scope,
        density = density,
        sheetOverhang = sheetOverhang,
        canReveal = canReveal,
        sheetConnection = sheetConnection,
        sheetOffset = sheetOffset,
        sheetRevealed = sheetRevealed,
        recentsPx = recentsPx,
        overscrollRefPx = overscrollRefPx,
        maxTopOverscrollPx = maxTopOverscrollPx,
        topZonePx = topZonePx,
        onTopZonePx = { topZonePx = it },
        onRecentsPx = { recentsPx = it },
        scrollState = agentsScrollState,
    )
}

@Composable
private fun BackdropLayers(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scope: CoroutineScope,
    density: androidx.compose.ui.unit.Density,
    sheetOverhang: androidx.compose.ui.unit.Dp,
    canReveal: Boolean,
    sheetConnection: NestedScrollConnection,
    sheetOffset: Animatable<Float, AnimationVector1D>,
    sheetRevealed: MutableState<Boolean>,
    recentsPx: Int,
    overscrollRefPx: Float,
    maxTopOverscrollPx: Float,
    topZonePx: Int,
    onTopZonePx: (Int) -> Unit,
    onRecentsPx: (Int) -> Unit,
    scrollState: ScrollState,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().then(if (canReveal) Modifier.nestedScroll(sheetConnection) else Modifier),
    ) {
        val sheetHeight = maxHeight + sheetOverhang
        val viewportHeightPx = with(density) { maxHeight.toPx() } - topZonePx.toFloat()
        BackLayerContent(
            heroSession = heroSession,
            olderSessions = olderSessions,
            onResumeSession = onResumeSession,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
            onTopZoneSize = onTopZonePx,
            onRecentsSize = onRecentsPx,
        )
        FrontSheetLayer(
            scope = scope,
            sheetOffset = sheetOffset,
            sheetRevealed = sheetRevealed,
            recentsPx = recentsPx,
            overscrollRefPx = overscrollRefPx,
            maxTopOverscrollPx = maxTopOverscrollPx,
            canReveal = canReveal,
            sheetHeight = sheetHeight,
            topZonePx = topZonePx,
            viewportHeightPx = viewportHeightPx,
            scrollState = scrollState,
            servers = servers,
            uiState = uiState,
            liveServerIds = liveServerIds,
            onConnect = onConnect,
            onEdit = onEdit,
            onDelete = onDelete,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }
}

@Composable
private fun FrontSheetLayer(
    scope: CoroutineScope,
    sheetOffset: Animatable<Float, AnimationVector1D>,
    sheetRevealed: MutableState<Boolean>,
    recentsPx: Int,
    overscrollRefPx: Float,
    maxTopOverscrollPx: Float,
    canReveal: Boolean,
    sheetHeight: androidx.compose.ui.unit.Dp,
    topZonePx: Int,
    viewportHeightPx: Float,
    scrollState: ScrollState,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    AgentsBackdropSheet(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .offset { IntOffset(0, topZonePx + sheetOffset.value.roundToInt()) },
        headerDragModifier =
            if (canReveal) {
                Modifier.sheetDrag(
                    scope = scope,
                    sheetOffset = sheetOffset,
                    sheetRevealed = sheetRevealed,
                    recentsPx = recentsPx,
                    overscrollRefPx = overscrollRefPx,
                    maxTopOverscrollPx = maxTopOverscrollPx,
                )
            } else {
                Modifier
            },
        servers = servers,
        uiState = uiState,
        liveServerIds = liveServerIds,
        onConnect = onConnect,
        onEdit = onEdit,
        onDelete = onDelete,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        scrollState = scrollState,
        viewportHeightPx = viewportHeightPx,
    )
}

/** Per-gesture scratch state for the sheet's nested-scroll connection. */
private class SheetGestureState {
    var startScrollValue = 0
    var sheetDragged = false
    var active = false
}

/**
 * Nested-scroll connection that lets list-edge overscroll drive the sheet.
 * A drag up on a revealed sheet collapses it first (in onPreScroll) before the
 * list scrolls; leftover scroll at the list's top/bottom edge (onPostScroll)
 * moves the sheet with a two-sided rubber-band.
 */
@Composable
private fun rememberSheetConnection(
    scope: CoroutineScope,
    sheetOffset: Animatable<Float, AnimationVector1D>,
    scrollState: ScrollState,
    recentsPx: Int,
    overscrollRefPx: Float,
    maxTopOverscrollPx: Float,
    canReveal: Boolean,
    sheetRevealed: MutableState<Boolean>,
): NestedScrollConnection {
    val gesture = remember { SheetGestureState() }
    return object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source == NestedScrollSource.UserInput && !gesture.active) {
                gesture.active = true
                gesture.startScrollValue = scrollState.value
                gesture.sheetDragged = false
            }
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (!canReveal || recentsPx == 0) return Offset.Zero
            val delta = available.y
            // Drag up while the sheet is revealed (recents visible): collapse the
            // sheet first, before the inner list is allowed to scroll.
            if (delta < 0f && sheetOffset.value > 0f) {
                val current = sheetOffset.value
                // Never overshoot past the collapsed edge (offset 0).
                val applied = delta.coerceAtLeast(-current)
                val next = (current + applied).coerceAtLeast(0f)
                val consumedY = next - current
                if (consumedY != 0f) {
                    gesture.sheetDragged = true
                    scope.launch { sheetOffset.snapTo(next) }
                }
                return Offset(0f, consumedY)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val delta = available.y
            val noScroll = delta == 0f || recentsPx == 0
            if (noScroll || !canReveal || source != NestedScrollSource.UserInput) {
                return Offset.Zero
            }
            val startedAtTop = gesture.startScrollValue == 0
            val startedAtBottom = gesture.startScrollValue == scrollState.maxValue
            val wrongDirection = (delta > 0f && !startedAtTop) || (delta < 0f && !startedAtBottom)
            if (wrongDirection) {
                return Offset.Zero
            }
            val max = recentsPx.toFloat()
            val current = sheetOffset.value
            val pushingOut =
                (current <= 0f && delta < 0f) ||
                    (current >= max && delta > 0f)
            val applied =
                if (pushingOut) {
                    val overshoot =
                        if (current <= 0f) -current else current - max
                    // Pulling up past the collapsed edge covers the hero: use a
                    // much stiffer band than the reveal-direction rubber band,
                    // so covering the hero takes progressively more effort
                    // (~3.6x finger travel at 60dp of coverage) while the
                    // recents reveal stays loose. 0.2x the travel cap keeps
                    // the resistance proportional to the allowed rise.
                    val ref =
                        if (current <= 0f) maxTopOverscrollPx * 0.2f else overscrollRefPx
                    delta * (1f / (1f + overshoot / ref))
                } else {
                    delta
                }
            val next = (current + applied).coerceAtLeast(-maxTopOverscrollPx)
            val consumedY = next - current
            if (consumedY != 0f) {
                gesture.sheetDragged = true
                scope.launch { sheetOffset.snapTo(next) }
            }
            return Offset(0f, consumedY)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            gesture.active = false
            if (gesture.sheetDragged) {
                gesture.sheetDragged = false
                val target =
                    if (sheetOffset.value > recentsPx / 2f || available.y > 1500f) {
                        recentsPx.toFloat()
                    } else {
                        0f
                    }
                sheetRevealed.value = target > 0f
                scope.launch {
                    sheetOffset.animateTo(
                        target,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    )
                }
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity,
        ): Velocity = Velocity.Zero
    }
}

/** Snaps/animates the sheet offset to match the revealed state. */
private suspend fun syncSheetOffset(
    sheetOffset: Animatable<Float, AnimationVector1D>,
    sheetRevealed: MutableState<Boolean>,
    recentsPx: Int,
    canReveal: Boolean,
) {
    when {
        !canReveal -> sheetRevealed.value = false
        // Restored from navigation/saveable: offset is 0 but should be revealed.
        // Snap so the shared-title transition sees final bounds, not a settle.
        sheetRevealed.value && recentsPx > 0 && sheetOffset.value == 0f ->
            sheetOffset.snapTo(recentsPx.toFloat())
        sheetRevealed.value && recentsPx > 0 ->
            sheetOffset.animateTo(
                recentsPx.toFloat(),
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        !sheetRevealed.value && sheetOffset.value > 0f ->
            sheetOffset.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        sheetOffset.value > recentsPx -> sheetOffset.snapTo(recentsPx.toFloat())
    }
}

/**
 * Drag modifier for the front sheet: rubber-bands past the edges and settles
 * to the revealed/hidden edge on release.
 */
@Composable
private fun Modifier.sheetDrag(
    scope: CoroutineScope,
    sheetOffset: Animatable<Float, AnimationVector1D>,
    sheetRevealed: MutableState<Boolean>,
    recentsPx: Int,
    overscrollRefPx: Float,
    maxTopOverscrollPx: Float,
): Modifier =
    this.draggable(
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
                            // Same stiffer upward band as the nested-scroll
                            // connection: covering the hero is resisted.
                            val ref =
                                if (current <= 0f) maxTopOverscrollPx * 0.2f else overscrollRefPx
                            delta * (1f / (1f + overshoot / ref))
                        } else {
                            delta
                        }
                    // Clamp upward travel so the sheet's bottom never lifts past
                    // its overhang.
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
            sheetRevealed.value = (target > 0f)
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

@Composable
private fun AgentsBackdropSheet(
    modifier: Modifier,
    headerDragModifier: Modifier,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scrollState: ScrollState,
    viewportHeightPx: Float,
) {
    val viewportDp = with(LocalDensity.current) { viewportHeightPx.toDp() }
    Surface(
        modifier = modifier,
        // Lightest tone: the foreground sheet sits above the darker background.
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // Elevated via tonal color, deliberately without a drop shadow.
        shadowElevation = 0.dp,
    ) {
        FrontLayerContent(
            servers = servers,
            uiState = uiState,
            liveServerIds = liveServerIds,
            onConnect = onConnect,
            onEdit = onEdit,
            onDelete = onDelete,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
            scrollState = scrollState,
            viewportDp = viewportDp,
            headerDragModifier = headerDragModifier,
        )
    }
}

@Composable
private fun BackLayerContent(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    onResumeSession: (RecentSession) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    onTopZoneSize: (Int) -> Unit,
    onRecentsSize: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.onSizeChanged { onTopZoneSize(it.height) }) {
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
                    .onSizeChanged { onRecentsSize(it.height) },
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
}

@Suppress("ModifierParameter")
@Composable
private fun FrontLayerContent(
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scrollState: ScrollState,
    viewportDp: androidx.compose.ui.unit.Dp,
    headerDragModifier: Modifier,
) {
    // Header pinned; only cards scroll. Outer is capped to viewport so
    // inner actually overflows (maxValue > 0); scroll to the very end first,
    // then a further up-swipe collapses the sheet back toward 0.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(viewportDp)
                .padding(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().then(headerDragModifier),
        ) {
            SectionHeader(
                title = stringResource(R.string.serverlist_section_agents),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                servers.forEach { server ->
                    ServerCard(
                        server = server,
                        uiState = uiState,
                        liveServerIds = liveServerIds,
                        onClick = { onConnect(server) },
                        onEdit = { onEdit(server) },
                        onDelete = { onDelete(server.id) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedContentScope = animatedContentScope,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                // ponytail: bottom clearance so the last card scrolls clear of the screen
                // bottom; sheet viewport extends below the visible sheet via the overhang.
                Spacer(modifier = Modifier.height(160.dp))
            }
            EdgeFade(scrollState = scrollState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLowest)
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
        ContinueSessionCardContent(
            session = session,
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
    rotation: Float,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RotatingCloverBadge(
    rotation: Float,
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
                        rotationZ = rotation
                        scaleX = pop
                        scaleY = pop
                    }.clip(MaterialShapes.Clover4Leaf.toShape())
                    .background(MaterialTheme.colorScheme.primary),
        )
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier =
                Modifier
                    .size(26.dp)
                    .graphicsLayer {
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
}

@Composable
private fun RecentSessionCardRow(
    session: RecentSession,
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
        RecentSessionCardRow(
            session = session,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }
}
