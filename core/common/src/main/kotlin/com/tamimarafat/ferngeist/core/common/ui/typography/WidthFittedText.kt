package com.tamimarafat.ferngeist.core.common.ui.typography

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tamimarafat.ferngeist.core.common.ui.motion.rememberReduceMotion

/**
 * One line set in the first of [styles] that fits, so a long title narrows along the width axis
 * instead of wrapping or shrinking. Past the last style it ellipsizes. A new text crossfades while
 * the bounds spring to its size.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WidthFittedText(
    text: String,
    styles: List<TextStyle>,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        transitionSpec = {
            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using
                SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec<IntSize>() }
        },
        label = "widthFittedText",
    ) { shown ->
        FittedLine(shown, styles)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FittedLine(
    text: String,
    styles: List<TextStyle>,
) {
    val measurer = rememberTextMeasurer()
    val reduceMotion = rememberReduceMotion()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val origin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 0f else 1f
    BoxWithConstraints {
        val maxWidth = constraints.maxWidth
        val (style, width) =
            remember(text, styles, maxWidth) {
                val widths = styles.map { measurer.measure(text, it, softWrap = false).size.width }
                val index = widths.indexOfFirst { it <= maxWidth }.takeIf { it >= 0 } ?: styles.lastIndex
                styles[index] to widths[index].coerceAtMost(maxWidth)
            }
        val scaleX = remember { Animatable(1f) }
        var drawnWidth by remember { mutableIntStateOf(0) }
        var drawnStyle by remember { mutableStateOf(style) }
        // A new width step morphs from the width last drawn, so a resize reads as the line
        // condensing rather than jumping. A layer scale stands in for the axis, which would load a
        // typeface per frame. Within one step only the ellipsis moves, which must not squash.
        LaunchedEffect(style, width) {
            val from = drawnWidth * scaleX.value
            val stepped = style != drawnStyle
            drawnWidth = width
            drawnStyle = style
            val ratio = if (width > 0) from / width else 0f
            if (!stepped || reduceMotion || ratio == 0f) return@LaunchedEffect
            scaleX.snapTo(ratio)
            scaleX.animateTo(1f, spring)
        }
        Text(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.graphicsLayer {
                    this.scaleX = scaleX.value
                    transformOrigin = TransformOrigin(origin, VERTICAL_CENTER)
                },
        )
    }
}

private const val VERTICAL_CENTER = 0.5f
