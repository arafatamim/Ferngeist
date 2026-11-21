package com.tamimarafat.ferngeist.feature.serverlist.ui.cards

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tamimarafat.ferngeist.core.model.TurnStatus

/** The tones every home card can take; a card's tone and its on-colour always pair. */
internal enum class CardTone { PRIMARY, ATTENTION, CONNECTED, FAILED, DEFAULT }

/**
 * The tone a card takes. What its turn asks for comes first: a wait or a failure is the thing to
 * act on, so it tints the card whether or not the link that would carry the answer is up. Only
 * then does the connection speak, and a live hero outranks a plain live tile.
 */
internal fun cardTone(
    status: TurnStatus,
    hero: Boolean,
    live: Boolean,
    failed: Boolean,
): CardTone =
    when {
        status == TurnStatus.ERROR -> CardTone.FAILED
        status == TurnStatus.NEEDS_ATTENTION -> CardTone.ATTENTION
        hero && live -> CardTone.PRIMARY
        live -> CardTone.CONNECTED
        failed -> CardTone.FAILED
        else -> CardTone.DEFAULT
    }

// Opaque roles pair each card tone with its on-colour; primary stays with the hero.
internal val CardTone.containerColor: Color
    @Composable
    get() =
        when (this) {
            CardTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
            CardTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
            CardTone.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
            CardTone.FAILED -> MaterialTheme.colorScheme.errorContainer
            CardTone.DEFAULT -> MaterialTheme.colorScheme.surfaceContainer
        }

/**
 * A card's ink for the turn's wave line: the card's own content colour, softened, because the wave is
 * an indicator beside the label rather than a second line of text. Full ink measured around 12:1.
 */
internal val waveLineColor: Color
    @Composable
    get() = LocalContentColor.current.copy(alpha = WAVE_LINE_ALPHA)

private const val WAVE_LINE_ALPHA = 0.45f

internal val CardTone.contentColor: Color
    @Composable
    get() =
        when (this) {
            CardTone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
            CardTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
            CardTone.CONNECTED -> MaterialTheme.colorScheme.onSecondaryContainer
            CardTone.FAILED -> MaterialTheme.colorScheme.onErrorContainer
            CardTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
        }
