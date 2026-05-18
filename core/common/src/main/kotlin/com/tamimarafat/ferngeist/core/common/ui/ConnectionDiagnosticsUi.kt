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
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.RpcDirection
import com.tamimarafat.ferngeist.acp.bridge.connection.WebSocketState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ConnectionDiagnosticsDialog(
    connectionState: AcpConnectionState,
    diagnostics: ConnectionDiagnostics,
    onDismiss: () -> Unit,
    totalTokens: Int? = null,
    contextWindowTokens: Int? = null,
    costAmount: Double? = null,
    costCurrency: String? = null,
    showCancelSupport: Boolean = false,
) {
    val scrollState = rememberScrollState()
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
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.common_connection, connectionStateLabel(connectionState)))
                Text(stringResource(R.string.common_websocket, websocketStateLabel(diagnostics.websocketState)))
                Text(stringResource(R.string.common_server, diagnostics.serverUrl ?: stringResource(R.string.common_unknown)))
                val agentName = diagnostics.agentInfo?.name ?: stringResource(R.string.common_unknown)
                val agentVersion = diagnostics.agentInfo?.version ?: stringResource(R.string.common_unknown)
                Text(stringResource(R.string.common_server_info, agentName, agentVersion))
                Text(stringResource(R.string.common_pending_rpc, diagnostics.pendingRequestCount))
                if (showCancelSupport) {
                    val cancelLabel = when (diagnostics.supportsSessionCancel) {
                        true -> stringResource(R.string.common_supported)
                        false -> stringResource(R.string.common_unsupported)
                        null -> stringResource(R.string.common_unknown)
                    }
                    Text(stringResource(R.string.common_cancel_support, cancelLabel))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.common_total_tokens, totalTokensText))
                Text(stringResource(R.string.common_usage_percentage, contextUsagePct))
                Text(stringResource(R.string.common_cost_spent, costText))
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.common_recent_rpc), style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentRpc.isEmpty()) {
                    Text(
                        stringResource(R.string.common_no_recent_rpc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    diagnostics.recentRpc.takeLast(12).reversed().forEach { entry ->
                        val rpcIdText = entry.rpcId?.let { " #$it" } ?: ""
                        val summaryText = entry.summary?.let { " - $it" } ?: ""
                        Text(
                            text = "${formatDiagnosticsTime(
                                entry.timestampMs,
                            )}  ${directionLabel(entry.direction)} ${entry.method}$rpcIdText$summaryText",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.common_recent_errors), style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentErrors.isEmpty()) {
                    Text(
                        stringResource(R.string.common_no_recent_errors),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    diagnostics.recentErrors.takeLast(8).reversed().forEach { entry ->
                        Text(
                            text = "${formatDiagnosticsTime(entry.timestampMs)}  ${entry.source}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun websocketStateLabel(state: WebSocketState): String =
    when (state) {
        WebSocketState.CONNECTING -> stringResource(R.string.common_connecting)
        WebSocketState.OPEN -> stringResource(R.string.common_connected)
        WebSocketState.CLOSED -> stringResource(R.string.common_disconnected)
        WebSocketState.FAILED -> stringResource(R.string.common_failed)
    }

@Composable
fun connectionStateLabel(state: AcpConnectionState): String =
    when (state) {
        is AcpConnectionState.Connecting -> stringResource(R.string.common_connecting)
        is AcpConnectionState.Connected -> stringResource(R.string.common_connected)
        is AcpConnectionState.Failed -> stringResource(R.string.common_failed)
        is AcpConnectionState.Disconnected -> stringResource(R.string.common_disconnected)
    }

@Composable
private fun directionLabel(direction: RpcDirection): String =
    when (direction) {
        RpcDirection.OutboundRequest -> stringResource(R.string.common_rpc_direction_req)
        RpcDirection.InboundResult -> stringResource(R.string.common_rpc_direction_res)
        RpcDirection.InboundError -> stringResource(R.string.common_rpc_direction_err)
        RpcDirection.InboundNotification -> stringResource(R.string.common_rpc_direction_ntf)
    }

private fun formatDiagnosticsTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

private fun percentString(
    part: Int?,
    total: Int?,
    locale: Locale,
): String? {
    if (part == null || total == null || total <= 0) return null
    val percent = part.toDouble() / total.toDouble()
    return NumberFormat
        .getPercentInstance(locale)
        .apply {
            maximumFractionDigits = 0
        }.format(percent)
}

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

internal fun formatCompactTokens(
    tokens: Int,
    locale: Locale,
): String {
    val absolute = kotlin.math.abs(tokens.toLong())
    return when {
        absolute >= 1_000_000_000L -> {
            val value = (tokens / 1_000_000_000.0).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}B"
        }

        absolute >= 1_000_000L -> {
            val value = (tokens / 1_000_000.0).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}M"
        }

        absolute >= 1_000L -> {
            val value = (tokens / 1_000.0).roundToInt()
            "${NumberFormat.getIntegerInstance(locale).format(value)}k"
        }

        else -> NumberFormat.getIntegerInstance(locale).format(tokens)
    }
}
