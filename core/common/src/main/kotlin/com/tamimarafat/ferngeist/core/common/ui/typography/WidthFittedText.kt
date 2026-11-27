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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tamimarafat.ferngeist.core.common.ui.motion.rememberReduceMotion

/**
 * One line set in the first of [styles] that fits. A condensed title expands to use the space
 * left between width steps, and titles beyond the last style ellipsize. A new text crossfades
 * while the bounds spring to its size.
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
    val origin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 0f else 1f
    BoxWithConstraints {
        val maxWidth = constraints.maxWidth
        val (style, measuredWidth) =
            remember(text, styles, maxWidth, measurer) {
                styles
                    .asSequence()
                    .map { it to measurer.measure(text, it, softWrap = false).size.width }
                    .firstOrNull { (_, width) -> width <= maxWidth } ?: (styles.last() to maxWidth)
            }
        val fillWidth = style != styles.first() && measuredWidth in 1..<maxWidth
        val width = if (fillWidth) maxWidth else measuredWidth.coerceAtMost(maxWidth)
        val scaleX = rememberWidthStepScale(style, width)
        Text(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.layout { measurable, constraints ->
                    // Keep line breaking at the measured font width so filling the gap cannot introduce an ellipsis.
                    val placeable = measurable.measure(constraints)
                    val fittedWidth = if (fillWidth) constraints.maxWidth else placeable.width
                    layout(fittedWidth, placeable.height) {
                        placeable.placeRelativeWithLayer(0, 0) {
                            this.scaleX = scaleX.value * fittedWidth / placeable.width.coerceAtLeast(1)
                            transformOrigin = TransformOrigin(origin, VERTICAL_CENTER)
                        }
                    }
                },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberWidthStepScale(
    style: TextStyle,
    width: Int,
): State<Float> {
    val reduceMotion = rememberReduceMotion()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val scaleX = remember { Animatable(1f) }
    var drawnWidth by remember { mutableIntStateOf(0) }
    var drawnStyle by remember { mutableStateOf(style) }
    // A new width step morphs from the last width drawn. Scaling a layer avoids loading a typeface
    // per frame. Within a step the fitted width and ellipsis follow the available space directly.
    LaunchedEffect(style, width, reduceMotion) {
        val from = drawnWidth * scaleX.value
        val stepped = style != drawnStyle
        drawnWidth = width
        drawnStyle = style
        if (reduceMotion || width == 0 || from == 0f) {
            scaleX.snapTo(1f)
            return@LaunchedEffect
        }
        if (stepped) scaleX.snapTo(from / width)
        if (scaleX.value != 1f) scaleX.animateTo(1f, spring)
    }
    return scaleX.asState()
}

private const val VERTICAL_CENTER = 0.5f
