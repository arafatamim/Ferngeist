package com.tamimarafat.ferngeist.core.common.ui

import java.text.NumberFormat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.common.R
import androidx.compose.ui.platform.LocalLocale

/**
 * Floating pill showing the ACP connection state as a colored dot, loading spinner, or
 * a donut chart of session context usage when connected and usage data is available.
 *
 * The pill is a circular [FilledTonalButton] with [secondaryContainer] color.
 *
 * When [connectionState] is [AcpConnectionState.Connected] and both [totalTokens] and
 * [contextWindowTokens] are non-null with [contextWindowTokens] > 0, a Canvas donut arc is
 * drawn showing the usage ratio (sweep from 12 o'clock). The arc uses
 * [MaterialTheme.colorScheme.primary], shifting to [MaterialTheme.colorScheme.error] when
 * the ratio exceeds 95%.
 *
 * Otherwise the indicator follows [connectionState]:
 * - [AcpConnectionState.Connecting] → [CircularProgressIndicator] (12dp)
 * - [AcpConnectionState.Connected] → [MaterialTheme.colorScheme.primary] dot
 * - [AcpConnectionState.Failed] → [MaterialTheme.colorScheme.error] dot
 * - [AcpConnectionState.Disconnected] → [MaterialTheme.colorScheme.outlineVariant] dot
 *
 * Long-press shows a [RichTooltip] with connection status and, when donut mode is active,
 * compact context usage info (e.g. "24K used of 128K context").
 *
 * @param connectionState The current ACP connection state to display.
 * @param totalTokens Total tokens used in the session, or null if unknown.
 * @param contextWindowTokens Context window size in tokens, or null if unknown.
 * @param costAmount Session cost amount, or null if unknown.
 * @param costCurrency ISO 4217 currency code (e.g. "USD"), or null if unknown.
 * @param onClick Called when the pill is tapped (e.g. open diagnostics dialog).
 * @param modifier Optional [Modifier] applied to the outer [FilledTonalButton].
 */
@ExperimentalMaterial3Api
@Composable
fun ConnectionStatusPill(
    connectionState: AcpConnectionState,
    totalTokens: Int? = null,
    contextWindowTokens: Int? = null,
    costAmount: Double? = null,
    costCurrency: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionLabel = connectionStateLabel(connectionState)
    val connectionStatusDesc = stringResource(R.string.common_connection_status_desc)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(title = { Text(stringResource(R.string.common_connection_status, connectionLabel)) }) {
                if (totalTokens != null && contextWindowTokens != null && contextWindowTokens > 0) {
                    val formattedUsed = formatCompactTokens(totalTokens, LocalLocale.current.platformLocale)
                    val formattedWindow = formatCompactTokens(contextWindowTokens, LocalLocale.current.platformLocale)
                    Column {
                        Text(stringResource(R.string.common_context_used, formattedUsed, formattedWindow))
                        costAmount?.let { amount ->
                            val costFmt = NumberFormat.getCurrencyInstance(LocalLocale.current.platformLocale).apply {
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
        },
        state = rememberTooltipState(),
    ) {
        FilledTonalButton(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
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
            when (connectionState) {
                is AcpConnectionState.Connecting ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )

                is AcpConnectionState.Connected ->
                    if (totalTokens != null && contextWindowTokens != null && contextWindowTokens > 0) {
                        val ratio by animateFloatAsState(
                            targetValue = (totalTokens.toFloat() / contextWindowTokens.toFloat())
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

                is AcpConnectionState.Failed ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(10.dp),
                    ) {}

                is AcpConnectionState.Disconnected ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(10.dp),
                    ) {}
            }
        }
    }
}

/**
 * Draws a small donut / ring chart representing a usage ratio.
 *
 * Renders a 3dp-thick circular arc starting at 12 o'clock and sweeping clockwise
 * proportional to [ratio]. The ring is rendered as a grey background arc
 * ([MaterialTheme.colorScheme.outlineVariant]) with a coloured foreground arc on top.
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
    val arcColor = if (ratio > 0.95f) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    // 16dp canvas inside a 40dp button with 12dp content padding leaves
    // enough room for a readable 3dp ring.
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        // Square the size so the ring is a perfect circle regardless of
        // minor constraint asymmetry.
        val aSize = Size(size.minDimension, size.minDimension)
        val topLeft = Offset(
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
