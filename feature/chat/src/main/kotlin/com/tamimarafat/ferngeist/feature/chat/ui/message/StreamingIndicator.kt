package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.LOADING_POLYGON_COUNT
import com.tamimarafat.ferngeist.feature.chat.ui.SHIMMER_END_OFFSET
import com.tamimarafat.ferngeist.feature.chat.ui.SHIMMER_START_OFFSET
import com.tamimarafat.ferngeist.feature.chat.ui.loadingShapes
import com.tamimarafat.ferngeist.feature.chat.ui.message.FileAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.message.ImageAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.message.PlanBubble
import kotlin.random.Random

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StreamingIndicator(
    streamKey: String,
    modifier: Modifier = Modifier,
    baseColor: Color = LocalContentColor.current.copy(alpha = 0.8f),
) {
    val resources = LocalResources.current
    val spinnerVerb =
        remember(streamKey) {
            val verbs = resources.getStringArray(R.array.chat_spinner_verbs)
            verbs[Random.nextInt(verbs.size)]
        }
    val polygons = remember(streamKey) { pickLoadingPolygons(streamKey) }
    val textBrush =
        rememberShimmerTextBrush(
            isActive = true,
            baseColor = baseColor,
            labelPrefix = "spinnerVerb",
        )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(
            polygons = polygons,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_streaming_indicator, spinnerVerb),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    brush = textBrush,
                ),
        )
    }
}

internal fun pickLoadingPolygons(seedKey: String) =
    loadingShapes.shuffled(Random(seedKey.hashCode())).take(LOADING_POLYGON_COUNT)

@Composable
internal fun rememberShimmerTextBrush(
    isActive: Boolean,
    baseColor: Color,
    labelPrefix: String,
): Brush {
    val shimmerTransition = rememberInfiniteTransition(label = "${labelPrefix}Shimmer")
    val shimmerOffset =
        if (isActive) {
            shimmerTransition
                .animateFloat(
                    initialValue = SHIMMER_START_OFFSET,
                    targetValue = SHIMMER_END_OFFSET,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis = 1400, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    label = "${labelPrefix}ShimmerOffset",
                ).value
        } else {
            0f
        }

    return if (isActive) {
        Brush.linearGradient(
            colors =
                listOf(
                    baseColor.copy(alpha = 0.45f),
                    baseColor.copy(alpha = 0.95f),
                    baseColor.copy(alpha = 0.45f),
                ),
            start = Offset(shimmerOffset - SHIMMER_START_OFFSET, 0f),
            end = Offset(shimmerOffset, 0f),
        )
    } else {
        SolidColor(baseColor)
    }
}
