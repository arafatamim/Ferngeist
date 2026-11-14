package com.tamimarafat.ferngeist.feature.chat.ui.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentclientprotocol.model.ContentBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One chat screen's audio: a single player, prepared on the first tap, holding audio focus while
 * it plays. The controller outlives the message that started it, so scrolling a clip away does
 * not cut it off; leaving the chat releases it.
 */
@Stable
internal class AudioPlaybackController(
    context: Context,
    private val decodeDispatcher: CoroutineDispatcher,
) {
    private var playing by mutableStateOf<ContentBlock.Audio?>(null)
    private var preparing by mutableStateOf<ContentBlock.Audio?>(null)
    private val failures = mutableStateMapOf<ContentBlock.Audio, Unit>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var current: ContentBlock.Audio? = null
    private var prepared = false
    private var prepareJob: Job? = null
    private var hasFocus = false

    fun isPlaying(audio: ContentBlock.Audio): Boolean = playing == audio

    fun isPreparing(audio: ContentBlock.Audio): Boolean = preparing == audio

    fun hasFailed(audio: ContentBlock.Audio): Boolean = audio in failures

    fun toggle(audio: ContentBlock.Audio) {
        when {
            playing == audio -> pause()
            current == audio && prepared -> start(audio)
            else -> prepareAndPlay(audio)
        }
    }

    fun pause() {
        if (preparing != null) {
            prepareJob?.cancel()
            releasePlayer()
        } else {
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
        }
        playing = null
        abandonFocus()
    }

    fun release() {
        prepareJob?.cancel()
        releasePlayer()
        abandonFocus()
        scope.cancel()
    }

    private fun prepareAndPlay(audio: ContentBlock.Audio) {
        prepareJob?.cancel()
        releasePlayer()
        abandonFocus()
        current = audio
        preparing = audio
        failures.remove(audio)
        prepareJob =
            scope.launch {
                val bytes = decode(audio)
                if (current != audio) return@launch
                if (bytes == null) fail(audio) else createPlayer(audio, bytes)
            }
    }

    private suspend fun decode(audio: ContentBlock.Audio): ByteArray? =
        runCatching {
            withContext(decodeDispatcher) { Base64.decode(audio.data, Base64.DEFAULT) }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    private fun createPlayer(
        audio: ContentBlock.Audio,
        bytes: ByteArray,
    ) {
        runCatching {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setAudioAttributes(attributes)
            mediaPlayer.setDataSource(ByteArrayMediaDataSource(bytes))
            mediaPlayer.setOnPreparedListener {
                if (player === it) {
                    prepared = true
                    preparing = null
                    start(audio)
                }
            }
            mediaPlayer.setOnCompletionListener { if (player === it) finish() }
            mediaPlayer.setOnErrorListener { failedPlayer, _, _ ->
                if (player === failedPlayer) fail(audio)
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure { fail(audio) }
    }

    private fun start(audio: ContentBlock.Audio) {
        val mediaPlayer = player ?: return fail(audio)
        preparing = null
        // Denied focus (a call in progress) is transient: the clip stays prepared for the next tap.
        if (!requestFocus()) return
        runCatching {
            mediaPlayer.start()
            playing = audio
        }.onFailure { fail(audio) }
    }

    private fun finish() {
        abandonFocus()
        releasePlayer()
    }

    private fun fail(audio: ContentBlock.Audio) {
        failures[audio] = Unit
        finish()
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return false
        if (!hasFocus) {
            hasFocus = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return hasFocus
    }

    private fun abandonFocus() {
        if (hasFocus) audioManager?.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun releasePlayer() {
        val old = player
        player = null
        prepared = false
        current = null
        playing = null
        preparing = null
        old?.release()
    }
}

internal val localAudioPlaybackController = staticCompositionLocalOf<AudioPlaybackController?> { null }

@Composable
internal fun rememberAudioPlaybackController(
    sessionId: String,
    decodeDispatcher: CoroutineDispatcher,
): AudioPlaybackController {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller =
        remember(context, sessionId, decodeDispatcher) {
            AudioPlaybackController(context.applicationContext, decodeDispatcher)
        }
    DisposableEffect(controller, lifecycle, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) controller.pause()
            }
        lifecycle.addObserver(observer)
        // Unplugged headphones would otherwise reroute the clip to the speaker.
        val noisy =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = controller.pause()
            }
        ContextCompat.registerReceiver(
            context,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(noisy)
            lifecycle.removeObserver(observer)
            controller.release()
        }
    }
    return controller
}

private class ByteArrayMediaDataSource(
    private val bytes: ByteArray,
) : MediaDataSource() {
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}
