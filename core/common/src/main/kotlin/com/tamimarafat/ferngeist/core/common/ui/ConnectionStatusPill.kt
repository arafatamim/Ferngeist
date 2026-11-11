package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import java.text.NumberFormat

/**
 * Compact connection status button with a tooltip for usage/cost details.
 *
 * The pill renders a state icon (spinner, dot, or ring) and uses the tooltip to
 * surface token/cost metrics when provided.
 */
@ExperimentalMaterial3Api
@Composable
fun ConnectionStatusPill(
    modifier: Modifier = Modifier,
    connectionState: ChatConnectionState,
    totalTokens: Long? = null,
    contextWindowTokens: Long? = null,
    costAmount: Double? = null,
    costCurrency: String? = null,
    onClick: () -> Unit,
) {
    val connectionLabel = connectionStateLabel(connectionState)
    val connectionStatusDesc = stringResource(R.string.common_connection_status_desc)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(title = { Text(stringResource(R.string.common_connection_status, connectionLabel)) }) {
                ConnectionUsageTooltipBody(
                    totalTokens = totalTokens,
                    contextWindowTokens = contextWindowTokens,
                    costAmount = costAmount,
                    costCurrency = costCurrency,
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        FilledTonalButton(
            onClick = onClick,
            shape = CircleShape,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(10.dp),
            modifier =
                modifier
                    .size(40.dp)
                    .handCursor()
                    .semantics {
                        contentDescription = connectionStatusDesc
                        stateDescription = connectionLabel
                    },
        ) {
            ConnectionStateIcon(
                connectionState = connectionState,
                totalTokens = totalTokens,
                contextWindowTokens = contextWindowTokens,
            )
        }
    }
}

@Composable
private fun ConnectionUsageTooltipBody(
    totalTokens: Long?,
    contextWindowTokens: Long?,
    costAmount: Double?,
    costCurrency: String?,
) {
    if (totalTokens != null && contextWindowTokens != null && contextWindowTokens > 0) {
        val formattedUsed = formatCompactTokens(totalTokens, LocalLocale.current.platformLocale)
        val formattedWindow = formatCompactTokens(contextWindowTokens, LocalLocale.current.platformLocale)
        Column {
            Text(stringResource(R.string.common_context_used, formattedUsed, formattedWindow))
            costAmount?.let { amount ->
                val costFmt =
                    NumberFormat.getCurrencyInstance(LocalLocale.current.platformLocale).apply {
                        costCurrency?.let {
                            runCatching { currency = java.util.Currency.getInstance(it) }
                        }
                        maximumFractionDigits = 2
                    }
                Text(stringResource(R.string.common_cost_amount, costFmt.format(amount)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionStateIcon(
    connectionState: ChatConnectionState,
    totalTokens: Long?,
    contextWindowTokens: Long?,
) {
    // Keyed on the icon kind (not the full state) so token-count updates
    // don't replay the transition.
    val iconKind = statusIconKind(connectionState, totalTokens, contextWindowTokens)
    AnimatedContent(
        targetState = iconKind,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 300)) +
                scaleIn(
                    animationSpec = tween(durationMillis = 300),
                    initialScale = 0.6f,
                ) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 200)) +
                scaleOut(
                    animationSpec = tween(durationMillis = 200),
                    targetScale = 0.6f,
                )
        },
        label = "StatusIcon",
    ) { kind ->
        when (kind) {
            StatusIconKind.CONNECTING -> ConnectingSunnyIcon()
            StatusIconKind.USAGE_RING ->
                UsageRingIcon(
                    totalTokens = totalTokens ?: 0,
                    contextWindowTokens = contextWindowTokens ?: 1,
                )
            StatusIconKind.CONNECTED -> StatusDot(color = MaterialTheme.colorScheme.primary)
            StatusIconKind.FAILED -> StatusDot(color = MaterialTheme.colorScheme.error)
            StatusIconKind.DISCONNECTED -> StatusDot(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Icon variants of [ConnectionStateIcon], used as the transition key. */
private enum class StatusIconKind {
    CONNECTING,
    USAGE_RING,
    CONNECTED,
    FAILED,
    DISCONNECTED,
}

private fun statusIconKind(
    connectionState: ChatConnectionState,
    totalTokens: Long?,
    contextWindowTokens: Long?,
): StatusIconKind =
    when (connectionState) {
        is ChatConnectionState.Connecting -> StatusIconKind.CONNECTING
        is ChatConnectionState.Connected ->
            if (totalTokens != null && contextWindowTokens != null && contextWindowTokens > 0) {
                StatusIconKind.USAGE_RING
            } else {
                StatusIconKind.CONNECTED
            }
        is ChatConnectionState.Failed -> StatusIconKind.FAILED
        is ChatConnectionState.Disconnected -> StatusIconKind.DISCONNECTED
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectingSunnyIcon() {
    // Constantly rotating Material "VerySunny" shape as the connecting indicator,
    // matching the server card's connecting state.
    val rotation by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(CONNECTING_ROTATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
    )
    Box(
        modifier =
            Modifier
                .size(20.dp)
                .rotate(rotation)
                .clip(MaterialShapes.VerySunny.toShape())
                .background(MaterialTheme.colorScheme.secondary),
    )
}

@Composable
private fun UsageRingIcon(
    totalTokens: Long,
    contextWindowTokens: Long,
) {
    val ratio by animateFloatAsState(
        targetValue =
            (totalTokens.toFloat() / contextWindowTokens.toFloat())
                .coerceIn(0f, 1f),
        animationSpec = tween(500),
    )
    // 20dp fills a 40dp button with 10dp content padding, matching the connecting icon.
    CircularProgressIndicator(
        progress = { ratio },
        modifier = Modifier.size(20.dp),
        // Shift to error colour when the context window is nearly full.
        color = if (ratio > 0.95f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        strokeWidth = 3.dp,
        // The default track matches the tonal button container and vanishes.
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        gapSize = 2.dp,
    )
}

@Composable
private fun StatusDot(color: Color) {
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(10.dp),
    ) {}
}

/** Duration of one full rotation of the connecting sunny icon, in milliseconds. */
private const val CONNECTING_ROTATION_MS = 2400
