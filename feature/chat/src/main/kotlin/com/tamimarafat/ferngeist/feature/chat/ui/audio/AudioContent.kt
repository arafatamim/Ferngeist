package com.tamimarafat.ferngeist.feature.chat.ui.audio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun AudioContentBlock(audio: ContentBlock.Audio) {
    val controller = localAudioPlaybackController.current
    val failed = controller?.hasFailed(audio) == true
    val status = if (failed) R.string.chat_audio_failed else R.string.chat_audio_content
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            AudioPlaybackControl(audio, controller)
            Text(
                text = stringResource(status, audio.mimeType),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AudioPlaybackControl(
    audio: ContentBlock.Audio,
    controller: AudioPlaybackController?,
) {
    if (controller?.isPreparing(audio) == true) {
        CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
        return
    }
    val playing = controller?.isPlaying(audio) == true
    val icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
    val description = if (playing) R.string.chat_audio_pause_desc else R.string.chat_audio_play_desc
    // A failed clip stays tappable: a player error (e.g. the media server dying) can be transient.
    IconButton(enabled = controller != null, onClick = { controller?.toggle(audio) }) {
        Icon(imageVector = icon, contentDescription = stringResource(description))
    }
}
