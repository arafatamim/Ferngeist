package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.formatCompactTokens
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatDiagnosticError
import com.tamimarafat.ferngeist.core.model.UsageState
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

private const val MAX_RECENT_ERRORS = 8

/** Matches the pill's donut ring, which turns red at the same point. */
private const val METER_NEARLY_FULL = 0.95f
private val meterHeight = 12.dp
private val meterMarkerWidth = 3.dp

/** Context and cost for the open chat; rows the agent doesn't report are left out. */
@Composable
internal fun UsageSection(usage: UsageState) {
    val total = usage.totalTokens
    val window = usage.contextWindowTokens?.takeIf { it > 0 }
    if ((total == null || window == null) && usage.costAmount == null) return
    val locale = LocalLocale.current.platformLocale
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.common_diagnostics_this_chat))
        if (total != null && window != null) {
            val ratio = (total.toFloat() / window).coerceIn(0f, 1f)
            val percent = NumberFormat.getPercentInstance(locale).format(ratio)
            MeterRow(
                label = stringResource(R.string.common_diagnostics_context),
                value =
                    stringResource(
                        R.string.common_diagnostics_context_value,
                        percent,
                        formatCompactTokens(total, locale),
                        formatCompactTokens(window, locale),
                    ),
                ratio = ratio,
            )
        }
        usage.costAmount?.let { amount ->
            val cost = currencyFormat(locale, usage.costCurrency)
            LabelValueRow(stringResource(R.string.common_diagnostics_cost), cost.format(amount))
        }
    }
}

@Composable
internal fun ProblemsSection(errors: List<ChatDiagnosticError>) {
    if (errors.isEmpty()) {
        Text(
            stringResource(R.string.common_no_recent_errors),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    val now = rememberPresentationTime()
    ExpandableSection(
        title = stringResource(R.string.common_recent_errors),
        trailing = { Badge { Text(errors.size.toString()) } },
    ) {
        errors.takeLast(MAX_RECENT_ERRORS).asReversed().forEach { error ->
            Column {
                Text(error.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${error.source} · ${relativeTime(error.timestampMs, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DetailsSection(diagnostics: ChatConnectionDiagnostics) {
    ExpandableSection(title = stringResource(R.string.common_diagnostics_details)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                diagnostics.serverUrl?.let { DetailText(stringResource(R.string.common_server, it)) }
                DetailText(stringResource(R.string.common_pending_rpc, diagnostics.pendingRequestCount))
                DetailText(stringResource(R.string.common_reconnect_attempts, diagnostics.reconnectAttempts))
            }
        }
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun MeterRow(
    label: String,
    value: String,
    ratio: Float,
    markerFraction: Float? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelValueRow(label, value, style)
        MeterBar(ratio, markerFraction)
    }
}

/** Flat rounded meter; [markerFraction] draws a pace tick. Turns red once [ratio] nears full. */
@Composable
internal fun MeterBar(
    ratio: Float,
    markerFraction: Float? = null,
) {
    val color = if (ratio > METER_NEARLY_FULL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(meterHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(ratio, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(ratio)
                .clip(CircleShape)
                .background(color),
        )
        markerFraction?.let { marker ->
            // Inside the fill the tick is a gap in the quota card's tone, so it reads on any fill colour.
            val colors = MaterialTheme.colorScheme
            val markerColor = if (marker < ratio) colors.surfaceContainer else colors.onSurfaceVariant
            Box(Modifier.fillMaxHeight().fillMaxWidth(marker)) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(meterMarkerWidth)
                        .fillMaxHeight()
                        .background(markerColor),
                )
            }
        }
    }
}

internal fun currencyFormat(
    locale: Locale,
    code: String?,
): NumberFormat =
    NumberFormat.getCurrencyInstance(locale).apply {
        code?.let { runCatching { currency = Currency.getInstance(it) } }
        maximumFractionDigits = 2
    }

@Composable
internal fun LabelValueRow(
    label: String,
    value: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    // The label takes the slack and wraps, so on narrow screens the value is never pushed off.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(value, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
