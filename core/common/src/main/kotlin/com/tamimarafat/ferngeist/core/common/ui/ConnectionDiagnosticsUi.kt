package com.tamimarafat.ferngeist.core.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val TOKENS_PER_K = 1_000L
private const val TOKENS_PER_M = 1_000_000L
private const val TOKENS_PER_B = 1_000_000_000L
private const val MAX_RECENT_ERRORS = 8

/**
 * Modal dialog showing connection diagnostics and usage statistics.
 *
 * All numbers are formatted for the current locale; optional fields fall back to
 * user-friendly placeholders.
 */
@Composable
fun ConnectionDiagnosticsDialog(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    onDismiss: () -> Unit,
    totalTokens: Int? = null,
    contextWindowTokens: Int? = null,
    costAmount: Double? = null,
    costCurrency: String? = null,
) {
    val totalTokensText =
        totalTokens?.let {
            formatCompactTokens(it, Locale.getDefault())
        } ?: stringResource(R.string.common_na)
    val contextUsagePct =
        percentString(
            totalTokens,
            contextWindowTokens,
            Locale.getDefault(),
        ) ?: stringResource(R.string.common_na)
    val costText =
        costAmount?.let { amount ->
            formatCurrency(amount, costCurrency, Locale.getDefault())
        } ?: stringResource(R.string.common_unsupported)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_connection_diagnostics)) },
        text = {
            ConnectionDiagnosticsContent(
                connectionState = connectionState,
                diagnostics = diagnostics,
                totalTokensText = totalTokensText,
                contextUsagePct = contextUsagePct,
                costText = costText,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun ConnectionDiagnosticsContent(
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    totalTokensText: String,
    contextUsagePct: String,
    costText: String,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.common_connection, connectionStateLabel(connectionState)))
        Text(
            stringResource(
                R.string.common_server,
                diagnostics.serverUrl ?: stringResource(R.string.common_unknown),
            ),
        )
        Text(stringResource(R.string.common_pending_rpc, diagnostics.pendingRequestCount))
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.common_total_tokens, totalTokensText))
        Text(stringResource(R.string.common_usage_percentage, contextUsagePct))
        Text(stringResource(R.string.common_cost_spent, costText))
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.common_recent_errors), style = MaterialTheme.typography.titleSmall)
        if (diagnostics.recentErrors.isEmpty()) {
            Text(
                stringResource(R.string.common_no_recent_errors),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            diagnostics.recentErrors.takeLast(MAX_RECENT_ERRORS).reversed().forEach { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

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
 * Formats a percentage or returns null when values are missing or invalid.
 */
private fun percentString(
    part: Int?,
    total: Int?,
    locale: Locale,
): String? {
    if (part == null || total == null || total <= 0) return null
    val percent = part.toDouble() / total.toDouble()
    return NumberFormat
        .getPercentInstance(locale)
        .apply { maximumFractionDigits = 0 }
        .format(percent)
}

/**
 * Formats a currency amount for display, optionally coercing the code to a known [java.util.Currency].
 *
 * Falls back to the locale default currency when [currencyCode] is null or unrecognised.
 */
private fun formatCurrency(
    amount: Double,
    currencyCode: String?,
    locale: Locale,
): String =
    NumberFormat
        .getCurrencyInstance(locale)
        .apply {
            currencyCode?.let {
                runCatching { currency = java.util.Currency.getInstance(it) }
            }
            maximumFractionDigits = 2
        }.format(amount)

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
