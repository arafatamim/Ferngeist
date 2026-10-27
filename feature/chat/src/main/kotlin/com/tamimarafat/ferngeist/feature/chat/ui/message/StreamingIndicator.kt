package com.tamimarafat.ferngeist.feature.chat.ui.message

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.status.rememberShimmerTextBrush
import com.tamimarafat.ferngeist.core.common.ui.status.rememberSpinnerVerb
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.LOADING_POLYGON_COUNT
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
    val spinnerVerb = rememberSpinnerVerb(streamKey)
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
