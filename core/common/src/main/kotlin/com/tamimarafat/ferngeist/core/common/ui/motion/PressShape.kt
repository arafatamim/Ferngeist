package com.tamimarafat.ferngeist.core.common.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Expressive press feedback: corners tighten to this share of their radius, the way M3 buttons do. */
private const val PRESSED_CORNER_FRACTION = 0.6f

/**
 * [resting] morphed by the press state on [interactionSource]: the corners spring in while the
 * surface is held and settle back out on release, on the theme's spatial motion. The card's own
 * radius is the only shape it ever relaxes to, so a status change that recolours the card does
 * not move its edges.
 */
@Composable
fun rememberPressShape(
    resting: CornerBasedShape,
    interactionSource: InteractionSource,
): Shape {
    val pressed by interactionSource.collectIsPressedAsState()
    val fraction by animateFloatAsState(
        targetValue = if (pressed) PRESSED_CORNER_FRACTION else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressShape",
    )
    return remember(resting, fraction) { resting.scaledCorners(fraction) }
}

/** [this] with every corner radius multiplied by [fraction]. */
private fun CornerBasedShape.scaledCorners(fraction: Float): Shape =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            fun CornerSize.scaled() = CornerSize(toPx(size, density) * fraction)
            return copy(topStart.scaled(), topEnd.scaled(), bottomEnd.scaled(), bottomStart.scaled())
                .createOutline(size, layoutDirection, density)
        }
    }
