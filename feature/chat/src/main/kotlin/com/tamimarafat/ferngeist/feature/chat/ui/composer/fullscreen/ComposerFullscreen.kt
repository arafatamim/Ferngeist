package com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen

import android.net.Uri
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.composerContentReceiver
import com.tamimarafat.ferngeist.feature.chat.ui.composerPanelContentPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val composerDragHandleWidth = 64.dp
internal val composerDragHandleHeight = 48.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ComposerDragHandle(
    fullscreen: Boolean,
    dragDistance: MutableFloatState,
    onToggle: () -> Unit,
    // Called when the small composer is dragged down: there is no smaller mode to settle into.
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        stringResource(
            if (fullscreen) R.string.chat_composer_restore_desc else R.string.chat_composer_fullscreen_desc,
        )
    val toggle by rememberUpdatedState(onToggle)
    val dismiss by rememberUpdatedState(onDismiss)
    val settleSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(width = composerDragHandleWidth, height = composerDragHandleHeight)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
                .handCursor()
                .pointerInput(fullscreen, dragDistance) {
                    detectHandleDrag(fullscreen, dragDistance, { settleSpec }, { toggle() }, { dismiss() })
                },
    ) {
        // Keep the hit area at the panel edge while moving the native marker into its upper half.
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.offset(y = -12.dp).clearAndSetSemantics {},
        )
    }
}

/**
 * Dragging the handle toward the other mode grows [dragDistance] and switches mode once it passes a
 * threshold. The small composer has no smaller mode, so a drag down from it closes the composer.
 */
private suspend fun PointerInputScope.detectHandleDrag(
    fullscreen: Boolean,
    dragDistance: MutableFloatState,
    settleSpec: () -> AnimationSpec<Float>,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) = coroutineScope {
    val threshold = 180.dp.toPx()
    // A small panel is a short drag from closed, so dismissing takes less travel than resizing.
    val dismissThreshold = 64.dp.toPx()
    var settling: Job? = null
    var dismissDrag = 0f

    fun settleBack() {
        settling =
            launch {
                animate(dragDistance.floatValue, 0f, animationSpec = settleSpec()) { value, _ ->
                    dragDistance.floatValue = value.coerceAtLeast(0f)
                }
            }
    }
    detectVerticalDragGestures(
        onDragStart = {
            settling?.cancel()
            dismissDrag = 0f
        },
        onVerticalDrag = { _, delta ->
            val previousDismiss = dismissDrag
            dismissDrag = (dismissDrag + delta).coerceAtLeast(0f)
            if (!fullscreen && previousDismiss < dismissThreshold && dismissDrag >= dismissThreshold) onDismiss()
            val towardNextMode = if (fullscreen) delta else -delta
            val previous = dragDistance.floatValue
            val distance = (previous + towardNextMode).coerceAtLeast(0f)
            dragDistance.floatValue = distance
            if (previous < threshold && distance >= threshold) onToggle()
        },
        onDragCancel = ::settleBack,
        onDragEnd = ::settleBack,
    )
}

@Composable
internal fun FullscreenComposerDialog(
    canSendImages: Boolean,
    canSendFiles: Boolean,
    canSendAudio: Boolean,
    onAttachUris: (List<Uri>) -> Unit,
    snackbarHostState: SnackbarHostState,
    dragDistance: FloatState,
    onDismiss: () -> Unit,
    content: @Composable (dropTargetActive: Boolean) -> Unit,
) {
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
