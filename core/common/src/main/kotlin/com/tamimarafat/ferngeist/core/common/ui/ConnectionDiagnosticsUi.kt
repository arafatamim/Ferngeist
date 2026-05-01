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
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionState
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectionDiagnostics
import com.tamimarafat.ferngeist.acp.bridge.connection.RpcDirection
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
    totalTokens: Int? = diagnostics.lastTotalTokens,
    contextWindowTokens: Int? = diagnostics.lastContextWindowTokens,
    costAmount: Double? = diagnostics.lastCostAmount,
    costCurrency: String? = diagnostics.lastCostCurrency,
    showCancelSupport: Boolean = false,
) {
    val scrollState = rememberScrollState()
    val totalTokensText =
        totalTokens?.let {
            formatCompactTokens(it, Locale.getDefault())
        } ?: "N/A"
    val contextUsagePct =
        percentString(
            totalTokens,
            contextWindowTokens,
            Locale.getDefault(),
        ) ?: "N/A"
    val costText =
        costAmount?.let { amount ->
            formatCurrency(amount, costCurrency, Locale.getDefault())
        } ?: "Unavailable"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Connection: ${connectionStateLabel(connectionState)}")
                Text("WebSocket: ${diagnostics.websocketState.name.lowercase().replace('_', ' ')}")
                Text("Server: ${diagnostics.serverUrl ?: "Unknown"}")
                val agentName = diagnostics.agentInfo?.name ?: "Unknown"
                val agentVersion = diagnostics.agentInfo?.version ?: "Unknown"
                Text("Server info: $agentName ($agentVersion)")
                Text("Pending RPC requests: ${diagnostics.pendingRequestCount}")
                if (showCancelSupport) {
                    Text(
                        "Cancel support: ${
                            when (diagnostics.supportsSessionCancel) {
                                true -> "Supported"
                                false -> "Unsupported"
                                null -> "Unknown"
                            }
                        }",
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Total tokens used: $totalTokensText")
                Text("Usage percentage: $contextUsagePct")
                Text("Cost spent: $costText")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Recent RPC Activity", style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentRpc.isEmpty()) {
                    Text(
                        "No recent RPC activity",
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
                Text("Recent Errors", style = MaterialTheme.typography.titleSmall)
                if (diagnostics.recentErrors.isEmpty()) {
                    Text(
                        "No recent errors",
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
                Text("Close")
            }
        },
    )
}

fun connectionStateLabel(state: AcpConnectionState): String =
    when (state) {
        is AcpConnectionState.Connecting -> "Connecting"
        is AcpConnectionState.Connected -> "Connected"
        is AcpConnectionState.Failed -> "Failed"
        is AcpConnectionState.Disconnected -> "Disconnected"
    }

private fun directionLabel(direction: RpcDirection): String =
    when (direction) {
        RpcDirection.OutboundRequest -> "REQ"
        RpcDirection.InboundResult -> "RES"
        RpcDirection.InboundError -> "ERR"
        RpcDirection.InboundNotification -> "NTF"
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

private fun formatCompactTokens(
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
