package com.tamimarafat.ferngeist.core.common.ui.status

import android.icu.text.MeasureFormat
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.motion.WorkingEllipsis
import com.tamimarafat.ferngeist.core.common.ui.time.compactDuration
import com.tamimarafat.ferngeist.core.common.ui.time.elapsedOf
import com.tamimarafat.ferngeist.core.common.ui.time.formatElapsed
import com.tamimarafat.ferngeist.core.common.ui.time.spokenDuration
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import kotlinx.coroutines.delay
import kotlin.time.ComparableTimeMark

private const val STATUS_TICK_MILLIS = 1000L

/**
 * The one presentation of a session's turn status: a working verb while the turn runs,
 * a plain label for attention, failure and completion, and [idleContent] when unknown.
 * [workingContent] overrides the verb where a caller has something more specific to say.
 * A running turn counts elapsed seconds; a finished one shows a coarse age ("Finished 3m ago").
 * [chatId] picks the verb, so a chat reads the same wherever it shows.
 * [style] must match the idle content's: the crossfade overlaps both, and mismatched baselines
 * read as multi-line to a list item, which then keeps a three-line height.
 * [accent] colours the working verb and finished label; pass the container's content colour where
 * primary would not contrast with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkingSubtitle(
    status: TurnActivity,
    chatId: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    accent: Color = MaterialTheme.colorScheme.primary,
    workingContent: (@Composable (elapsed: String?) -> Unit)? = null,
    idleContent: @Composable () -> Unit,
) {
    val age =
        rememberTurnAge(
            status.since.takeIf {
                status.status == TurnStatus.WORKING ||
                    status.status == TurnStatus.FINISHED
            },
        )
    val elapsed = age?.takeIf { status.status == TurnStatus.WORKING }?.let(::elapsedOf)
    val compact = elapsed?.let { formatElapsed(it, MeasureFormat.FormatWidth.NARROW) }
    val spoken = elapsed?.let { formatElapsed(it, MeasureFormat.FormatWidth.WIDE) }
    val finishedAge = age?.takeIf { status.status == TurnStatus.FINISHED }
    val finished = finishedAge?.let { finishedStatusLabel(it) }
    val description =
        when (status.status) {
            TurnStatus.WORKING -> workingStatusLabel(turnStatusLabel(status.status).orEmpty(), spoken)
            TurnStatus.FINISHED ->
                finishedAge?.let { finishedStatusDescription(it) } ?: turnStatusLabel(status.status)
            else -> turnStatusLabel(status.status)
        }
    Crossfade(
        targetState = status.status,
        modifier = Modifier.semantics { description?.let { stateDescription = it } },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "workingSubtitle",
    ) { active ->
        TurnStatusContent(
            active = active,
            chatId = chatId,
            style = style,
            accent = accent,
            workingContent = workingContent,
            compact = compact,
            finished = finished,
            idleContent = idleContent,
        )
    }
}

@Composable
private fun TurnStatusContent(
    active: TurnStatus,
    chatId: String,
    style: TextStyle,
    accent: Color,
    workingContent: (@Composable (elapsed: String?) -> Unit)?,
    compact: String?,
    finished: String?,
    idleContent: @Composable () -> Unit,
) {
    when (active) {
        TurnStatus.UNKNOWN -> idleContent()
        TurnStatus.WORKING ->
            if (workingContent != null) {
                workingContent(compact)
            } else {
                WorkingStatusText(
                    text = workingStatusLabel(rememberSpinnerVerb(chatId), compact),
                    style = style,
                    color = accent,
                )
            }
        TurnStatus.NEEDS_ATTENTION ->
            TurnStatusLabel(
                turnStatusLabel(active).orEmpty(),
                MaterialTheme.colorScheme.onTertiaryContainer,
                style,
            )
        TurnStatus.ERROR ->
            TurnStatusLabel(
                turnStatusLabel(active).orEmpty(),
                MaterialTheme.colorScheme.error,
                style,
            )
        TurnStatus.FINISHED ->
            TurnStatusLabel(
                finished ?: turnStatusLabel(active).orEmpty(),
                accent,
                style,
            )
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
    text: String,
    color: Color,
    style: TextStyle,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Only composed, started surfaces tick; a resume samples the original mark immediately. */
@Composable
private fun rememberTurnAge(since: ComparableTimeMark?): Long? {
    if (since == null) return null
    val owner = LocalLifecycleOwner.current
    var age by remember(since) { mutableLongStateOf(since.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)) }
    LaunchedEffect(since, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                age = since.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
                delay(STATUS_TICK_MILLIS - age % STATUS_TICK_MILLIS)
            }
        }
    }
    return age
}

@Composable
fun workingStatusLabel(
    verb: String,
    elapsed: String?,
): String = if (elapsed == null) verb else stringResource(R.string.common_status_working_for, verb, elapsed)

/** A coarse duration in whole units; null under a minute, where the label says "just now". */
private fun coarseAge(ageMs: Long): CoarseAge? =
    when {
        ageMs < DateUtils.MINUTE_IN_MILLIS ->
            null
        ageMs < DateUtils.HOUR_IN_MILLIS ->
            CoarseAge((ageMs / DateUtils.MINUTE_IN_MILLIS).toInt(), MeasureUnit.MINUTE)
        ageMs < DateUtils.DAY_IN_MILLIS ->
            CoarseAge((ageMs / DateUtils.HOUR_IN_MILLIS).toInt(), MeasureUnit.HOUR)
        else ->
            CoarseAge((ageMs / DateUtils.DAY_IN_MILLIS).toInt(), MeasureUnit.DAY)
    }

private data class CoarseAge(
    val count: Int,
    val unit: MeasureUnit,
)

@Composable
private fun finishedStatusLabel(ageMs: Long): String {
    val age = coarseAge(ageMs) ?: return stringResource(R.string.common_status_finished_now)
    return stringResource(R.string.common_status_finished_ago, compactDuration(age.count, age.unit))
}

@Composable
private fun finishedStatusDescription(ageMs: Long): String {
    val age = coarseAge(ageMs) ?: return stringResource(R.string.common_status_finished_now)
    return stringResource(R.string.common_status_finished_ago, spokenDuration(age.count, age.unit))
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
