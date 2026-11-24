package com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen

import android.net.Uri
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.composerContentReceiver
import com.tamimarafat.ferngeist.feature.chat.ui.composerPanelContentPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.composerPanelDrag(
    fullscreen: Boolean,
    dragDistance: MutableFloatState,
    onModeChange: (ComposerMode) -> Unit,
): Modifier {
    val changeMode by rememberUpdatedState(onModeChange)
    val settleSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val drag =
        remember(fullscreen, dragDistance, density) {
            ComposerPanelDrag(
                fullscreen = fullscreen,
                dragDistance = dragDistance,
                density = density,
                scope = scope,
                settleSpec = { settleSpec },
                onModeChange = { changeMode(it) },
            )
        }
    return nestedScroll(drag)
        .pointerInput(drag) {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    // Scroll wheels also dispatch UserInput, so only hand off an active pointer drag.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        drag.pointerPressed = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        drag.pointerPressed = false
                    }
                }
                detectVerticalDragGestures(
                    onDragStart = { drag.start() },
                    onVerticalDrag = { _, delta -> drag.moveBy(delta) },
                    onDragCancel = drag::settleBack,
                    onDragEnd = drag::settleBack,
                )
            }
        }
}

/**
 * Dragging toward another mode updates [dragDistance] and switches mode at its threshold.
 * A negative distance shrinks the inline panel before it closes.
 */
private class ComposerPanelDrag(
    private val fullscreen: Boolean,
    private val dragDistance: MutableFloatState,
    density: Density,
    private val scope: CoroutineScope,
    private val settleSpec: () -> AnimationSpec<Float>,
    private val onModeChange: (ComposerMode) -> Unit,
) : NestedScrollConnection {
    private val threshold = with(density) { 180.dp.toPx() }

    // A small panel is a short drag from closed, so dismissing takes less travel than resizing.
    private val minimumDrag = if (fullscreen) 0f else with(density) { -64.dp.toPx() }

    var pointerPressed = false
    private var nestedDrag = false
    private var settling: Job? = null

    fun start() {
        settling?.cancel()
    }

    fun moveBy(delta: Float) {
        val towardNextMode = if (fullscreen) delta else -delta
        val previous = dragDistance.floatValue
        val distance = (previous + towardNextMode).coerceAtLeast(minimumDrag)
        dragDistance.floatValue = distance
        when {
            !fullscreen && previous > minimumDrag && distance == minimumDrag -> onModeChange(ComposerMode.COLLAPSED)
            previous < threshold && distance >= threshold -> {
                onModeChange(if (fullscreen) ComposerMode.EXPANDED else ComposerMode.FULLSCREEN)
            }
        }
    }

    fun settleBack() {
        settling =
            scope.launch {
                val start = dragDistance.floatValue
                animate(start, 0f, animationSpec = settleSpec()) { value, _ ->
                    dragDistance.floatValue = value.coerceIn(minOf(start, 0f), maxOf(start, 0f))
                }
            }
    }

    // Let the editor scroll first. Its leftover drag can resize the panel when the draft fits or reaches an edge.
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (!pointerPressed || available.y == 0f || source != NestedScrollSource.UserInput) return Offset.Zero
        if (!nestedDrag) {
            start()
            nestedDrag = true
        }
        moveBy(available.y)
        return Offset(x = 0f, y = available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (nestedDrag) {
            nestedDrag = false
            settleBack()
        }
        return Velocity.Zero
    }
}

@Composable
internal fun FullscreenComposerDialog(
    canSendImages: Boolean,
    canSendFiles: Boolean,
    canSendAudio: Boolean,
    onAttachUris: (List<Uri>) -> Unit,
    snackbarHostState: SnackbarHostState,
    dragDistance: MutableFloatState,
    onModeChange: (ComposerMode) -> Unit,
    content: @Composable (dropTargetActive: Boolean) -> Unit,
) {
    val onDismiss = { onModeChange(ComposerMode.COLLAPSED) }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        val backProgress = rememberComposerBackProgress(onClose = onDismiss)
        var dropTargetActive by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val nothingToAttach = stringResource(R.string.chat_attach_nothing_found)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dragOffset = with(LocalDensity.current) { dragDistance.floatValue.toDp() }.coerceAtMost(maxHeight / 2)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = size.height * backProgress.value }
                    .padding(top = dragOffset)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .composerPanelDrag(fullscreen = true, dragDistance, onModeChange)
                    // Only the controls are inset. The panel itself covers the complete window at rest.
                    .safeDrawingPadding()
                    .composerContentReceiver(
                        canSendImages = canSendImages,
                        canSendFiles = canSendFiles,
                        canSendAudio = canSendAudio,
                        onUris = onAttachUris,
                        onDragHoverChange = { dropTargetActive = it },
                        onNothingToAttach = { scope.launch { snackbarHostState.showSnackbar(nothingToAttach) } },
                    ).padding(composerPanelContentPadding),
            ) {
                content(dropTargetActive)
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 80.dp),
                )
            }
        }
    }
}
