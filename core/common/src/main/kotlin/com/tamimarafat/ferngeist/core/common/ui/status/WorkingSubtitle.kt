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
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * The one presentation of a session's turn status: a working verb while the turn runs,
 * a plain label for attention, failure and completion, and [idleContent] when unknown.
 * [workingContent] overrides the verb where a caller has something more specific to say.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkingSubtitle(
    status: TurnStatus,
    streamKey: String,
    workingContent: (@Composable () -> Unit)? = null,
    idleContent: @Composable () -> Unit,
) {
    val description = turnStatusLabel(status)
    Crossfade(
        targetState = status,
        modifier = Modifier.semantics { description?.let { stateDescription = it } },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "workingSubtitle",
    ) { active ->
        when (active) {
            TurnStatus.UNKNOWN -> idleContent()
            TurnStatus.WORKING ->
                if (workingContent != null) {
                    workingContent()
                } else {
                    ShimmerStatusText(
                        text = stringResource(R.string.common_working_verb, rememberSpinnerVerb(streamKey)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            TurnStatus.NEEDS_ATTENTION -> TurnStatusLabel(active, MaterialTheme.colorScheme.tertiary)
            TurnStatus.ERROR -> TurnStatusLabel(active, MaterialTheme.colorScheme.error)
            TurnStatus.FINISHED -> TurnStatusLabel(active, MaterialTheme.colorScheme.primary)
        }
    }
}

/** Stable text for every state a label can honestly claim; null makes no claim at all. */
@Composable
fun turnStatusLabel(status: TurnStatus): String? =
    when (status) {
        TurnStatus.NEEDS_ATTENTION -> stringResource(R.string.common_status_needs_attention)
        TurnStatus.ERROR -> stringResource(R.string.common_status_error)
        TurnStatus.WORKING -> stringResource(R.string.common_in_progress)
        TurnStatus.FINISHED -> stringResource(R.string.common_status_finished)
        TurnStatus.UNKNOWN -> null
    }

@Composable
private fun TurnStatusLabel(
    status: TurnStatus,
    color: Color,
) {
    Text(
        text = turnStatusLabel(status).orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
