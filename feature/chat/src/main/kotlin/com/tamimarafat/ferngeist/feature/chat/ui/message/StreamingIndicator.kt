package com.tamimarafat.ferngeist.feature.chat.ui.message

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.motion.WorkingEllipsis
import com.tamimarafat.ferngeist.core.common.ui.status.rememberShimmerTextBrush
import com.tamimarafat.ferngeist.core.common.ui.status.rememberSpinnerVerb
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
    val textBrush = rememberShimmerTextBrush(isActive = true, baseColor = baseColor, labelPrefix = "spinnerVerb")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(
            polygons = polygons,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // The dots are the ellipsis: they hop in, rest and fade on the verb's baseline.
        Row {
            Text(
                text = spinnerVerb,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        brush = textBrush,
                    ),
                modifier = Modifier.alignByBaseline(),
            )
            WorkingEllipsis(
                color = baseColor,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

internal fun pickLoadingPolygons(seedKey: String) =
    loadingShapes.shuffled(Random(seedKey.hashCode())).take(LOADING_POLYGON_COUNT)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Streaming indicator", showBackground = true)
@Composable
private fun StreamingIndicatorPreview() {
    MaterialExpressiveTheme {
        Surface {
            StreamingIndicator(streamKey = "preview", modifier = Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Streaming indicator · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StreamingIndicatorDarkPreview() {
    MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
        Surface {
            StreamingIndicator(streamKey = "preview", modifier = Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Streaming indicator · 200% font", showBackground = true, fontScale = 2f)
@Composable
private fun StreamingIndicatorLargeFontPreview() {
    MaterialExpressiveTheme {
        Surface {
            StreamingIndicator(streamKey = "preview", modifier = Modifier.padding(16.dp))
        }
    }
}
