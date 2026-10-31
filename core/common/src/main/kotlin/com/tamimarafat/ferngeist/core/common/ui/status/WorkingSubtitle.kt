package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.motion.WorkingEllipsis
import com.tamimarafat.ferngeist.core.model.TurnStatus

/**
 * The one presentation of a session's turn status: a working verb while the turn runs,
 * a plain label for attention, failure and completion, and [idleContent] when unknown.
 * [workingContent] overrides the verb where a caller has something more specific to say.
 * [chatId] picks the verb, so a chat reads the same wherever it shows.
 * [style] must match the idle content's: the crossfade overlaps both, and mismatched baselines
 * read as multi-line to a list item, which then keeps a three-line height.
 * [accent] colours the working verb and finished label; pass the container's content colour where
 * primary would not contrast with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkingSubtitle(
    status: TurnStatus,
    chatId: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    accent: Color = MaterialTheme.colorScheme.primary,
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
                    WorkingStatusText(
                        text = rememberSpinnerVerb(chatId),
                        style = style,
                        color = accent,
                    )
                }
            TurnStatus.NEEDS_ATTENTION -> TurnStatusLabel(active, MaterialTheme.colorScheme.onTertiaryContainer, style)
            TurnStatus.ERROR -> TurnStatusLabel(active, MaterialTheme.colorScheme.error, style)
            TurnStatus.FINISHED -> TurnStatusLabel(active, accent, style)
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

fun ColorScheme.turnStatusContainerColor(
    status: TurnStatus,
    fallback: Color,
): Color =
    when (status) {
        TurnStatus.NEEDS_ATTENTION -> tertiaryContainer
        else -> fallback
    }

fun ColorScheme.turnStatusContentColor(
    status: TurnStatus,
    fallback: Color,
): Color =
    when (status) {
        TurnStatus.NEEDS_ATTENTION -> onTertiaryContainer
        else -> fallback
    }

@Composable
private fun TurnStatusLabel(
    status: TurnStatus,
    color: Color,
    style: TextStyle,
) {
    Text(
        text = turnStatusLabel(status).orEmpty(),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Derived from the chat, not rolled per call site: every row showing a chat must agree on its verb. */
@Composable
fun rememberSpinnerVerb(chatId: String): String {
    val resources = LocalResources.current
    return remember(chatId, resources) {
        val verbs = resources.getStringArray(R.array.chat_spinner_verbs)
        verbs[Math.floorMod(chatId.hashCode(), verbs.size)]
    }
}

@Composable
fun WorkingStatusText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
        )
        WorkingEllipsis(color = color, fontSize = style.fontSize, modifier = Modifier.alignByBaseline())
    }
}
