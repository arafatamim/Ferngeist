package com.tamimarafat.ferngeist.core.common.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.status.turnStatusContentColor
import com.tamimarafat.ferngeist.core.common.ui.time.compactRelativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberFinishedAgo
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberWorkingElapsed
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus

@Composable
fun ChatCard(
    updatedAt: Long?,
    status: TurnActivity,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    // Matches the working subtitle's accent, so the timer and the verb read as one signal.
    workingColor: Color = MaterialTheme.colorScheme.primary,
    // Off when the content places [ChatCardStamp] itself, as a [StatusCardBody] does.
    trailingStamp: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(16.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            if (trailingStamp) ChatCardStamp(updatedAt, status, workingColor)
        }
    }
}

/** The one trailing stamp every chat and project card shows, so they all speak the same states alike. */
@Composable
fun ChatCardStamp(
    updatedAt: Long?,
    status: TurnActivity,
    workingColor: Color = MaterialTheme.colorScheme.primary,
    color: Color =
        MaterialTheme.colorScheme.turnStatusContentColor(
            status.status,
            MaterialTheme.colorScheme.onSurfaceVariant,
        ),
) {
    // A waiting request names itself; a running turn counts up and an unread finish says how long
    // ago; otherwise a relative stamp reads without a full date and time.
    val needsYou = stringResource(R.string.common_status_needs_you)
    val working = rememberWorkingElapsed(status)
    val finished = rememberFinishedAgo(status)
    val stamp =
        needsYou.takeIf { status.status == TurnStatus.NEEDS_ATTENTION }
            ?: working
            ?: finished
            ?: updatedAt?.let { compactRelativeTime(it, rememberPresentationTime()) }
    stamp?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = if (it == working) workingColor else color,
        )
    }
}
