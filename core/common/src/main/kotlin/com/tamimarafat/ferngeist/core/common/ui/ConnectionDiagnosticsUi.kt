package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val TOKENS_PER_K = 1_000L
private const val TOKENS_PER_M = 1_000_000L
private const val TOKENS_PER_B = 1_000_000_000L

/** Maps a chat connection state to a localized label. */
@Composable
fun connectionStateLabel(state: ChatConnectionState): String =
    when (state) {
        is ChatConnectionState.Connecting -> stringResource(R.string.common_connecting)
        is ChatConnectionState.Connected -> stringResource(R.string.common_connected)
        is ChatConnectionState.Failed -> stringResource(R.string.common_failed)
        is ChatConnectionState.Disconnected -> stringResource(R.string.common_disconnected)
    }

/**
 * Formats token counts with compact suffixes (k / M / B) for display in constrained UI.
 *
 * Examples: 1 500 → "2k", 2 500 000 → "3M", negatives use absolute value.
 */
internal fun formatCompactTokens(
    tokens: Int,
    locale: Locale,
): String {
    val absolute = kotlin.math.abs(tokens.toLong())
    return when {
        absolute >= TOKENS_PER_B -> {
            val value = (tokens / TOKENS_PER_B.toDouble()).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}B"
        }
        absolute >= TOKENS_PER_M -> {
            val value = (tokens / TOKENS_PER_M.toDouble()).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}M"
        }
        absolute >= TOKENS_PER_K -> {
            val value = (tokens / TOKENS_PER_K.toDouble()).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}k"
        }
        else -> NumberFormat.getIntegerInstance(locale).format(tokens)
    }
}
