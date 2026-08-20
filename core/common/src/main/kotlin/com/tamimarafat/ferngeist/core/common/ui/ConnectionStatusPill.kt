package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
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
    totalTokens: Int? = null,
    contextWindowTokens: Int? = null,
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
            contentPadding = PaddingValues(12.dp),
            modifier =
                modifier
                    .size(40.dp)
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
    totalTokens: Int?,
    contextWindowTokens: Int?,
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
    totalTokens: Int?,
    contextWindowTokens: Int?,
) {
    when (connectionState) {
        is ChatConnectionState.Connecting -> {
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
                        .size(14.dp)
                        .rotate(rotation)
                        .clip(MaterialShapes.VerySunny.toShape())
                        .background(MaterialTheme.colorScheme.secondary),
            )
        }

        is ChatConnectionState.Connected ->
            if (totalTokens != null && contextWindowTokens != null && contextWindowTokens > 0) {
                val ratio by animateFloatAsState(
                    targetValue =
                        (totalTokens.toFloat() / contextWindowTokens.toFloat())
                            .coerceIn(0f, 1f),
                    animationSpec = tween(500),
                )
                DonutRing(
                    ratio = ratio,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(10.dp),
                ) {}
            }

        is ChatConnectionState.Failed ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(10.dp),
            ) {}

        is ChatConnectionState.Disconnected ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(10.dp),
            ) {}
    }
}

/**
 * Draws a small donut / ring chart representing a usage ratio.
 *
 * Renders a 3dp-thick circular arc starting at 12 o'clock and sweeping clockwise
 * proportional to [ratio]. The ring is rendered as a muted background arc
 * ([MaterialTheme.colorScheme.outline]) with a coloured foreground arc on top.
 *
 * The foreground arc uses [MaterialTheme.colorScheme.primary] by default and shifts
 * to [MaterialTheme.colorScheme.error] when [ratio] exceeds 0.95 (near-full warning).
 *
 * @param ratio The fill fraction in `[0f..1f]` — controls the foreground sweep angle.
 * @param modifier Optional [Modifier] applied to the inner [Canvas].
 */
@Composable
private fun DonutRing(
    ratio: Float,
    modifier: Modifier = Modifier,
) {
    // Shift to error colour when the context window is nearly full.
    val arcColor =
        if (ratio > 0.95f) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    val ringColor = MaterialTheme.colorScheme.outline
    // 16dp canvas inside a 40dp button with 12dp content padding leaves
    // enough room for a readable 3dp ring.
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        // Square the size so the ring is a perfect circle regardless of
        // minor constraint asymmetry.
        val aSize = Size(size.minDimension, size.minDimension)
        val topLeft =
            Offset(
                (size.width - aSize.width) / 2f,
                (size.height - aSize.height) / 2f,
            )
        // Background: full grey ring.
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = aSize,
            style = stroke,
        )
        // Foreground: coloured arc proportional to usage ratio.
        // -90° start shifts the origin from 3 o'clock (Canvas default)
        // to 12 o'clock, matching standard progress-ring convention.
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = 360f * ratio,
            useCenter = false,
            topLeft = topLeft,
            size = aSize,
            style = stroke,
        )
    }
}

/** Duration of one full rotation of the connecting sunny icon, in milliseconds. */
private const val CONNECTING_ROTATION_MS = 2400
