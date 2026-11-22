package com.tamimarafat.ferngeist.feature.chat.ui.audio

import android.text.format.DateUtils
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.AttachmentCard
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.attachmentCardModifier

/**
 * An audio block as the transcript's attachment card: the play control where a file's type badge
 * would be, the clip's position where its size would be. Tapping the card is the control, so the
 * whole card is one announced target; preparing borrows the card's loading badge, and a player
 * failure its error badge.
 */
@Composable
internal fun AudioContentBlock(audio: ContentBlock.Audio) {
    val controller = localAudioPlaybackController.current
    val playing = controller?.isPlaying(audio) == true
    val position = if (controller?.current == audio) controller.positionMs else 0
    val duration = if (controller?.current == audio) controller.durationMs else 0
    AttachmentCard(
        path = audio.mimeType,
        label = stringResource(R.string.chat_audio_desc),
        size = "${elapsed(position.toLong())} / ${elapsed(duration.toLong())}",
        loading = controller?.isPreparing(audio) == true,
        failed = controller?.hasFailed(audio) == true,
        onOpen = controller?.let { { it.toggle(audio) } },
        onClickLabel =
            stringResource(
                if (playing) R.string.chat_audio_pause_desc else R.string.chat_audio_play_desc,
            ),
        modifier = attachmentCardModifier(aspect = null),
        failureMessage = stringResource(R.string.chat_audio_failed, audio.mimeType),
        badge = {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

private fun elapsed(millis: Long): String = DateUtils.formatElapsedTime(millis / MILLIS_PER_SECOND)

private const val MILLIS_PER_SECOND = 1000L
