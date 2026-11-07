package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * The background carries the turn's state while the agent's identity stays upright and in
 * place. The shape names the state and morphs between states: a turning cookie while working,
 * a very sunny shape, coloured, for a request or a failure, a circle otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActivityIconContainer(
    size: Dp,
    modifier: Modifier = Modifier,
    // Defaults to the resting circle for cards whose status line carries the turn instead.
    status: TurnStatus = TurnStatus.UNKNOWN,
    // A veil of the card's own content colour, so the rest backdrop separates from whatever tone
    // the card has; a fixed role (it was surfaceContainerHighest) vanished on cards of that role.
    idleColor: Color = LocalContentColor.current.copy(alpha = IDLE_VEIL_ALPHA),
    content: @Composable () -> Unit,
) {
    val rotation = rememberActivityRotation(periodMillis = 12_000, active = status == TurnStatus.WORKING)
    val color by animateColorAsState(
        targetValue = status.backdropColor(idleColor),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "activityBackdropColor",
    )
    // The shape is the only visual signal of the turn, so it carries the spoken one too.
    val description = turnStatusLabel(status)
    Box(
        modifier.size(size).semantics { description?.let { stateDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation.value }
                .background(color, rememberMorphedShape(status.polygon())),
        )
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(
                LocalContentColor provides status.contentColor(),
                content = content,
            )
        }
    }
}

// Material's pressed/dragged state-layer strength: visible on any tone, still quiet.
private const val IDLE_VEIL_ALPHA = 0.12f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun TurnStatus.polygon(): RoundedPolygon =
    when (this) {
        TurnStatus.WORKING -> MaterialShapes.Cookie9Sided
        TurnStatus.NEEDS_ATTENTION, TurnStatus.ERROR -> MaterialShapes.VerySunny
        TurnStatus.FINISHED, TurnStatus.UNKNOWN -> MaterialShapes.Circle
    }

@Composable
private fun TurnStatus.backdropColor(idle: Color) =
    when (this) {
        TurnStatus.WORKING -> MaterialTheme.colorScheme.primary
        TurnStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
        TurnStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> idle
    }

@Composable
private fun TurnStatus.contentColor() =
    when (this) {
        TurnStatus.WORKING -> MaterialTheme.colorScheme.onPrimary
        TurnStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.onTertiary
        TurnStatus.ERROR -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * Each new target morphs from the last settled one on a spatial spring.
 * shortcut: a change mid-morph restarts from the last settled shape, not the half-way one;
 * upgrade if state flips faster than the spring settles.
 */
@Composable
private fun rememberMorphedShape(target: RoundedPolygon): Shape {
    var settled by remember { mutableStateOf(target) }
    val morph = remember(target) { Morph(settled, target) }
    val progress = remember(target) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(target) {
        progress.animateTo(1f, spec)
        settled = target
    }
    return MorphShape(morph, progress.value)
}

private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress).asComposePath()
        // Fitted to its own bounds, so no polygon's native coordinate range matters.
        val bounds = path.getBounds()
        path.translate(Offset(-bounds.left, -bounds.top))
        path.transform(Matrix().apply { scale(size.width / bounds.width, size.height / bounds.height) })
        return Outline.Generic(path)
    }
}
