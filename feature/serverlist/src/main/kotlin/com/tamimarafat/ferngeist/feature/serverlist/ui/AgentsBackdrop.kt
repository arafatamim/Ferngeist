package com.tamimarafat.ferngeist.feature.serverlist.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectSummary
import com.tamimarafat.ferngeist.core.common.ui.EdgeFade
import com.tamimarafat.ferngeist.core.common.ui.status.statusesFor
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.aggregateTurnActivity
import com.tamimarafat.ferngeist.feature.serverlist.R
import com.tamimarafat.ferngeist.feature.serverlist.RecentSession
import com.tamimarafat.ferngeist.feature.serverlist.ServerListUiState
import com.tamimarafat.ferngeist.feature.serverlist.ui.projects.ProjectsGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PROJECT_SLIDE_DIVISOR = 4

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AgentsBackdrop(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    connectedChatKeys: Set<String> = emptySet(),
    turnStatuses: Map<String, TurnActivity> = emptyMap(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    projects: List<ProjectSummary> = emptyList(),
    onOpenProject: (ProjectSummary) -> Unit = {},
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
    val canReveal = olderSessions.isNotEmpty()
    val sheetRevealed = rememberSaveable { mutableStateOf(false) }
    // Stored as a fraction of the recents block (0f..1f) rather than raw pixels, so
    // a fold or rotate restores the same proportional position instead of a stale
    // pixel offset from the previous viewport.
    var savedSheetFraction by rememberSaveable { mutableFloatStateOf(0f) }
    // Restore the sheet offset across navigation so the sheet is already at its
    // settled position on return; the shared-title transition then targets stable
    // bounds instead of following the sheet's settle animation.
    // Seeded from 0f on purpose: recentsPx is 0 on the first composition, so
    // remember could never re-derive the offset from the (then zero) height.
    val sheetOffset = remember { Animatable(0f) } // 0 = covering recents, recentsPx = fully revealed
    var sheetFractionRestored by remember { mutableStateOf(false) }
    // Persist the live offset only when leaving composition (navigation), not per frame.
    DisposableEffect(sheetOffset, recentsPx) {
        onDispose {
            savedSheetFraction =
                if (recentsPx > 0) (sheetOffset.value / recentsPx).coerceIn(0f, 1f) else 0f
        }
    }
    val agentsLazyListState = rememberLazyListState()

    // Restore the user's sheet position once the recents block has a height.
    LaunchedEffect(recentsPx, sheetFractionRestored) {
        if (!sheetFractionRestored && recentsPx > 0) {
            sheetOffset.snapTo((savedSheetFraction * recentsPx).coerceIn(0f, recentsPx.toFloat()))
            sheetFractionRestored = true
        }
    }

    LaunchedEffect(recentsPx, sheetRevealed.value, canReveal, sheetFractionRestored) {
        // Hold off until the restore above has landed (or when there is no height
        // to restore into), so the two effects cannot fight over the offset.
        if (sheetFractionRestored || recentsPx == 0) {
            syncSheetOffset(sheetOffset, sheetRevealed, recentsPx, canReveal)
        }
    }

    val sheetConnection =
        rememberSheetConnection(
            scope = scope,
            sheetOffset = sheetOffset,
            scrollState = agentsLazyListState,
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
        connectedChatKeys = connectedChatKeys,
        turnStatuses = turnStatuses,
        refreshing = refreshing,
        onRefresh = onRefresh,
        projects = projects,
        onOpenProject = onOpenProject,
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
        scrollState = agentsLazyListState,
    )
}

@Composable
private fun BackdropLayers(
    heroSession: RecentSession?,
    olderSessions: List<RecentSession>,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    connectedChatKeys: Set<String> = emptySet(),
    turnStatuses: Map<String, TurnActivity> = emptyMap(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    projects: List<ProjectSummary> = emptyList(),
    onOpenProject: (ProjectSummary) -> Unit = {},
    onResumeSession: (RecentSession) -> Unit,
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scope: CoroutineScope,
    density: Density,
    sheetOverhang: Dp,
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
    scrollState: LazyListState,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().then(if (canReveal) Modifier.nestedScroll(sheetConnection) else Modifier),
    ) {
        val sheetHeight = maxHeight + sheetOverhang
        val viewportHeightPx = with(density) { maxHeight.toPx() } - topZonePx.toFloat()
        BackLayerContent(
            turnStatuses = turnStatuses,
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
            connectedChatKeys = connectedChatKeys,
            turnStatuses = turnStatuses,
            refreshing = refreshing,
            onRefresh = onRefresh,
            projects = projects,
            onOpenProject = onOpenProject,
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
    sheetHeight: Dp,
    topZonePx: Int,
    viewportHeightPx: Float,
    scrollState: LazyListState,
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    connectedChatKeys: Set<String> = emptySet(),
    turnStatuses: Map<String, TurnActivity> = emptyMap(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    projects: List<ProjectSummary> = emptyList(),
    onOpenProject: (ProjectSummary) -> Unit = {},
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    // Full-bleed container: the sheet surface is capped and centered on wide windows, but
    // the drag/scroll envelope (the outer BoxWithConstraints' nestedScroll, and the offset
    // math above) still spans the whole viewport.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AgentsBackdropSheet(
            canRefresh = !canReveal || sheetRevealed.value,
            modifier =
                Modifier
                    // Cap must be outside `fillMaxWidth`, which otherwise passes fixed
                    // width constraints that `widthIn` cannot shrink.
                    .widthIn(max = 640.dp)
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
            connectedChatKeys = connectedChatKeys,
            turnStatuses = turnStatuses,
            refreshing = refreshing,
            onRefresh = onRefresh,
            projects = projects,
            onOpenProject = onOpenProject,
            onConnect = onConnect,
            onEdit = onEdit,
            onDelete = onDelete,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
            scrollState = scrollState,
            viewportHeightPx = viewportHeightPx,
        )
    }
}

/**
 * Resistance multiplier for the sheet rubber band: 1:1 inside the range, progressively
 * stiffer past an edge.
 *
 * Shared by the nested-scroll connection and the header drag, which must agree or the
 * sheet feels different depending on which one the gesture happens to start on.
 *
 * Pulling up past the collapsed edge covers the hero and is deliberately stiffer than
 * the reveal direction, so covering the hero takes progressively more effort while the
 * recents reveal stays loose. [HERO_RISE_BAND_RATIO] scales that band with the travel
 * cap, keeping the resistance proportional to however far the sheet is allowed to rise.
 */
private fun rubberBandDelta(
    current: Float,
    delta: Float,
    max: Float,
    revealRefPx: Float,
    maxTopOverscrollPx: Float,
): Float {
    val pushingOut =
        (current <= 0f && delta < 0f) ||
            (current >= max && delta > 0f)
    if (!pushingOut) return delta
    val overshoot = if (current <= 0f) -current else current - max
    val ref = if (current <= 0f) maxTopOverscrollPx * HERO_RISE_BAND_RATIO else revealRefPx
    return delta * (1f / (1f + overshoot / ref))
}

/** How much stiffer covering the hero is than revealing the recents. */
private const val HERO_RISE_BAND_RATIO = 0.2f

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
    scrollState: LazyListState,
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
                gesture.startScrollValue =
                    when {
                        !scrollState.canScrollBackward -> 0
                        !scrollState.canScrollForward -> 2
                        else -> 1
                    }
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
            val startedAtBottom = gesture.startScrollValue == 2 || !scrollState.canScrollForward
            val wrongDirection = (delta > 0f && !startedAtTop) || (delta < 0f && !startedAtBottom)
            if (wrongDirection) {
                return Offset.Zero
            }
            val max = recentsPx.toFloat()
            val current = sheetOffset.value
            val next =
                (current + rubberBandDelta(current, delta, max, overscrollRefPx, maxTopOverscrollPx))
                    .coerceAtLeast(-maxTopOverscrollPx)
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
                    // Clamp upward travel so the sheet's bottom never lifts past
                    // its overhang.
                    sheetOffset.snapTo(
                        (current + rubberBandDelta(current, delta, max, overscrollRefPx, maxTopOverscrollPx))
                            .coerceAtLeast(-maxTopOverscrollPx),
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
    connectedChatKeys: Set<String> = emptySet(),
    turnStatuses: Map<String, TurnActivity> = emptyMap(),
    refreshing: Boolean = false,
    canRefresh: Boolean,
    onRefresh: () -> Unit = {},
    projects: List<ProjectSummary> = emptyList(),
    onOpenProject: (ProjectSummary) -> Unit = {},
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scrollState: LazyListState,
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
            canRefresh = canRefresh,
            servers = servers,
            uiState = uiState,
            liveServerIds = liveServerIds,
            connectedChatKeys = connectedChatKeys,
            turnStatuses = turnStatuses,
            refreshing = refreshing,
            onRefresh = onRefresh,
            projects = projects,
            onOpenProject = onOpenProject,
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
    turnStatuses: Map<String, TurnActivity>,
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
                    status = turnStatuses["${hero.serverId}/${hero.sessionId}"] ?: TurnActivity(),
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
                    status = turnStatuses["${session.serverId}/${session.sessionId}"] ?: TurnActivity(),
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
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
private fun FrontLayerContent(
    servers: List<LaunchableTarget>,
    uiState: ServerListUiState,
    liveServerIds: Set<String> = emptySet(),
    connectedChatKeys: Set<String> = emptySet(),
    turnStatuses: Map<String, TurnActivity> = emptyMap(),
    refreshing: Boolean = false,
    canRefresh: Boolean,
    onRefresh: () -> Unit = {},
    projects: List<ProjectSummary> = emptyList(),
    onOpenProject: (ProjectSummary) -> Unit = {},
    onConnect: (LaunchableTarget) -> Unit,
    onEdit: (LaunchableTarget) -> Unit,
    onDelete: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
    scrollState: LazyListState,
    viewportDp: Dp,
    headerDragModifier: Modifier,
) {
    val showProjects = projects.isNotEmpty()
    val projectVisibility = remember { MutableTransitionState(false) }.apply { targetState = showProjects }
    val agentHeaderVisibility = remember { MutableTransitionState(false) }.apply { targetState = showProjects }
    var lastProjects by remember { mutableStateOf(projects) }
    SideEffect { if (showProjects) lastProjects = projects }
    val displayedProjects = if (showProjects) projects else lastProjects
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val projectsAtTop by remember(scrollState, showProjects) {
        derivedStateOf {
            val first =
                scrollState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == scrollState.firstVisibleItemIndex }
            showProjects && (first?.key == "projects" || first == null && scrollState.firstVisibleItemIndex == 0)
        }
    }
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
        // With projects, they lead the scroll and the pinned header names them; agents follow.
        Box(
            modifier = Modifier.fillMaxWidth().then(headerDragModifier),
        ) {
            Crossfade(
                targetState = projectsAtTop,
                animationSpec = effects,
                label = "home section title",
            ) { hasProjects ->
                SectionHeader(
                    title =
                        stringResource(
                            if (hasProjects) {
                                R.string.serverlist_section_projects
                            } else {
                                R.string.serverlist_section_agents
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val now = rememberPresentationTime(refreshing)
            val motion = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            val refreshState = rememberPullToRefreshState()
            Box(
                Modifier.pullToRefresh(
                    isRefreshing = refreshing,
                    state = refreshState,
                    enabled = canRefresh,
                    onRefresh = onRefresh,
                ),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = scrollState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    animatedProjectSection("projects", projectVisibility, scrollState) {
                        ProjectsGroup(
                            projects = displayedProjects,
                            turnStatuses = turnStatuses,
                            connectedChatKeys = connectedChatKeys,
                            now = now,
                            onOpenProject = onOpenProject,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    animatedProjectSection("agents-header", agentHeaderVisibility, scrollState) {
                        SectionHeader(
                            title = stringResource(R.string.serverlist_section_agents),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        )
                    }
                    items(servers, key = { "agent:${it.id}" }) { server ->
                        val chatStatuses = turnStatuses.statusesFor(server.id)
                        ServerCard(
                            server = server,
                            status = chatStatuses.values.aggregateTurnActivity(),
                            // shortcut: no recency here, so the lead chat is the lowest id; order by
                            // updatedAt if the agent row should match the hero's verb.
                            workingChatIds =
                                chatStatuses.filterValues { it.status == TurnStatus.WORKING }.keys.sorted(),
                            uiState = uiState,
                            liveServerIds = liveServerIds,
                            onClick = { onConnect(server) },
                            onEdit = { onEdit(server) },
                            onDelete = { onDelete(server.id) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                            modifier = Modifier.padding(horizontal = 16.dp).animateItem(placementSpec = motion),
                        )
                    }
                    // ponytail: clearance lets the final card scroll above the bottom controls.
                    item(key = "bottom-clearance") { Spacer(modifier = Modifier.height(160.dp)) }
                }
                AgentsRefreshIndicator(
                    show = projects.isEmpty(),
                    refreshing = refreshing,
                    state = refreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            EdgeFade(listState = scrollState, fadeColor = MaterialTheme.colorScheme.surfaceContainerLowest)
        }
    }
}

private fun LazyListScope.animatedProjectSection(
    key: String,
    state: MutableTransitionState<Boolean>,
    scrollState: LazyListState,
    content: @Composable () -> Unit,
) {
    if (state.targetState || state.currentState && scrollState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
        item(key = key) { ProjectSectionVisibility(state, content) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProjectSectionVisibility(
    state: MutableTransitionState<Boolean>,
    content: @Composable () -> Unit,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(effects) + expandVertically(spatial) + slideInVertically(slide) { -it / PROJECT_SLIDE_DIVISOR },
        exit = fadeOut(effects) + shrinkVertically(spatial) + slideOutVertically(slide) { -it / PROJECT_SLIDE_DIVISOR },
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsRefreshIndicator(
    show: Boolean,
    refreshing: Boolean,
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    modifier: Modifier = Modifier,
) {
    if (show) {
        PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing, modifier = modifier)
    }
}
