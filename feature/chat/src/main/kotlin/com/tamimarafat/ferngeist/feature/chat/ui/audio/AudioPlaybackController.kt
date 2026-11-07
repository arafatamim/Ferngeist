package com.tamimarafat.ferngeist.feature.chat.ui.audio

import android.content.Context
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
import com.agentclientprotocol.model.ContentBlock
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
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var playingKey by mutableStateOf<String?>(null)
        private set
    var preparingKey by mutableStateOf<String?>(null)
        private set

    private val failures = mutableStateMapOf<String, Unit>()
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
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) pause()
            }.build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var currentKey: String? = null
    private var preparedKey: String? = null
    private var prepareJob: Job? = null
    private var hasFocus = false

    fun isPlaying(key: String): Boolean = playingKey == key

    fun isPreparing(key: String): Boolean = preparingKey == key

    fun hasFailed(key: String): Boolean = key in failures

    fun toggle(
        key: String,
        audio: ContentBlock.Audio,
    ) {
        when {
            playingKey == key -> pause()
            currentKey == key && preparedKey == key -> start(key)
            else -> prepareAndPlay(key, audio)
        }
    }

    fun release() {
        prepareJob?.cancel()
        releasePlayer()
        abandonFocus()
        scope.cancel()
        playingKey = null
        preparingKey = null
    }

    private fun prepareAndPlay(
        key: String,
        audio: ContentBlock.Audio,
    ) {
        prepareJob?.cancel()
        releasePlayer()
        currentKey = key
        preparingKey = key
        prepareJob =
            scope.launch {
                val bytes = decode(audio)
                if (currentKey != key) return@launch
                if (bytes == null) {
                    fail(key)
                    return@launch
                }
                player = createPlayer(key, bytes)
            }
    }

    private suspend fun decode(audio: ContentBlock.Audio): ByteArray? =
        runCatching {
            withContext(decodeDispatcher) { Base64.decode(audio.data, Base64.DEFAULT) }
        }.getOrNull()

    private fun createPlayer(
        key: String,
        bytes: ByteArray,
    ): MediaPlayer? {
        val mediaPlayer = MediaPlayer()
        return runCatching {
            mediaPlayer.setAudioAttributes(attributes)
            mediaPlayer.setDataSource(ByteArrayMediaDataSource(bytes))
            mediaPlayer.setOnPreparedListener {
                if (currentKey == key) {
                    preparedKey = key
                    preparingKey = null
                    start(key)
                }
            }
            mediaPlayer.setOnCompletionListener {
                if (currentKey == key) finish()
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                if (currentKey == key) fail(key)
                true
            }
            mediaPlayer.prepareAsync()
            mediaPlayer
        }.getOrElse {
            mediaPlayer.release()
            fail(key)
            null
        }
    }

    private fun start(key: String) {
        val mediaPlayer = player ?: return fail(key)
        runCatching {
            requestFocus()
            mediaPlayer.start()
            playingKey = key
            preparingKey = null
        }.onFailure { fail(key) }
    }

    private fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        playingKey = null
        abandonFocus()
    }

    private fun finish() {
        playingKey = null
        preparingKey = null
        preparedKey = null
        currentKey = null
        abandonFocus()
        releasePlayer()
    }

    private fun fail(key: String) {
        failures[key] = Unit
        playingKey = null
        preparingKey = null
        preparedKey = null
        currentKey = null
        abandonFocus()
        releasePlayer()
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (hasFocus) return
        hasFocus = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (!hasFocus) return
        manager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}

internal val localAudioPlaybackController = staticCompositionLocalOf<AudioPlaybackController?> { null }

@Composable
internal fun rememberAudioPlaybackController(): AudioPlaybackController {
    val context = LocalContext.current
    val controller = remember(context) { AudioPlaybackController(context.applicationContext) }
    DisposableEffect(controller) {
        onDispose(controller::release)
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
