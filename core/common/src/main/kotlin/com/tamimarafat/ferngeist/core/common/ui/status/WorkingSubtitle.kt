package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.Crossfade
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.core.common.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkingSubtitle(
    working: Boolean,
    streamKey: String,
    idleContent: @Composable () -> Unit,
) {
    val description = stringResource(R.string.common_in_progress)
    Crossfade(
        targetState = working,
        modifier = Modifier.semantics { if (working) stateDescription = description },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "workingSubtitle",
    ) { active ->
        if (active) {
            ShimmerStatusText(
                text = stringResource(R.string.common_working_verb, rememberSpinnerVerb(streamKey)),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            idleContent()
        }
    }
}

@Composable
fun rememberSpinnerVerb(streamKey: String): String {
    val resources = LocalResources.current
    return remember(streamKey, resources) { resources.getStringArray(R.array.chat_spinner_verbs).random() }
}

@Composable
fun ShimmerStatusText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val color = MaterialTheme.colorScheme.primary
    var textWidth by remember { mutableFloatStateOf(0f) }
    val brush =
        rememberShimmerTextBrush(
            isActive = textWidth > 0f,
            baseColor = color,
            labelPrefix = "workingText",
            // Blend white over the text color so the softer sweep never exposes the card background.
            highlightColor = lerp(color, Color.White, 0.65f),
            textWidth = textWidth,
        )
    Text(
        text = text,
        style = style.copy(brush = brush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { textWidth = it.getLineRight(0) - it.getLineLeft(0) },
    )
}
