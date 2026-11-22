package com.tamimarafat.ferngeist.feature.chat.ui.composer.fullscreen

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberComposerBackProgress(
    enabled: Boolean = true,
    onClose: () -> Unit,
): State<Float> {
    val progress = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val close by rememberUpdatedState(onClose)
    val canClose by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) {
        if (enabled) progress.snapTo(0f)
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            if (progress.value > 0f) progress.animateTo(1f, settleSpec)
            if (canClose) close()
        } catch (cancelled: CancellationException) {
            // The dispatcher cancels its gesture coroutine, so the recovery runs in the panel's scope.
            scope.launch { progress.animateTo(0f, settleSpec) }
            throw cancelled
        }
    }
    return progress.asState()
}
