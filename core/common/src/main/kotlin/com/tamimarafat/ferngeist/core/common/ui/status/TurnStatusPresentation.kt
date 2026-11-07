package com.tamimarafat.ferngeist.core.common.ui.status

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.TurnStatus

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

/** Derived from the chat, not rolled per call site: every row showing a chat must agree on its verb. */
@Composable
fun rememberSpinnerVerb(chatId: String): String {
    val resources = LocalResources.current
    return remember(chatId, resources) {
        val verbs = resources.getStringArray(R.array.chat_spinner_verbs)
        verbs[Math.floorMod(chatId.hashCode(), verbs.size)]
    }
}
