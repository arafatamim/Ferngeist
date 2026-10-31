package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.tamimarafat.ferngeist.core.model.AgentQuota
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatDiagnosticError
import com.tamimarafat.ferngeist.core.model.QuotaCost
import com.tamimarafat.ferngeist.core.model.QuotaWindow
import com.tamimarafat.ferngeist.core.model.UsageState

private const val MINUTE_MS = 60_000L
private const val OLDER_ERROR_OFFSET_MS = 9 * MINUTE_MS
private const val RECENT_ERROR_OFFSET_MS = 2 * MINUTE_MS

private val healthyDiagnostics =
    ChatConnectionDiagnostics(
        serverUrl = "wss://gateway.example.com/acp?token=abc",
        pendingRequestCount = 0,
        reconnectAttempts = 0,
        recentErrors = emptyList(),
    )

// Relative times are read against the wall clock, so offsets are taken from it too.
private fun failingDiagnostics(now: Long = System.currentTimeMillis()) =
    healthyDiagnostics.copy(
        pendingRequestCount = 2,
        reconnectAttempts = 3,
        recentErrors =
            listOf(
                ChatDiagnosticError(now - OLDER_ERROR_OFFSET_MS, "transport", "Socket closed unexpectedly"),
                ChatDiagnosticError(now - RECENT_ERROR_OFFSET_MS, "auth", "Credential refresh failed (401)"),
            ),
    )

private fun codexBarQuota(now: Long = System.currentTimeMillis()): List<AgentQuota> {
    fun inMinutes(minutes: Long) = now + minutes * MINUTE_MS
    return listOf(
        AgentQuota(
            provider = "Claude",
            account = "API User (Claude Pro)",
            updatedAtMs = now - MINUTE_MS,
            windows =
                listOf(
                    QuotaWindow(
                        "5-Hour",
                        usedFraction = 0.08f,
                        paceFraction = 0.12f,
                        resetsAtMs = inMinutes(minutes = 264),
                    ),
                    QuotaWindow(
                        "Weekly",
                        usedFraction = 0.05f,
                        paceFraction = 0.07f,
                        resetsAtMs = inMinutes(minutes = 8_880),
                    ),
                ),
            costs =
                listOf(
                    QuotaCost("Today", amount = 18.90, tokens = 38_000_000),
                    QuotaCost("Last 30 days", amount = 461.70, tokens = 1_500_000_000),
                ),
            costCurrency = "USD",
        ),
        AgentQuota(
            provider = "Codex",
            updatedAtMs = now - MINUTE_MS,
            windows =
                listOf(
                    QuotaWindow(
                        "5-Hour",
                        usedFraction = 0.97f,
                        paceFraction = 0.6f,
                        resetsAtMs = inMinutes(minutes = 40),
                    ),
                ),
        ),
        AgentQuota("DeepSeek", updatedAtMs = now, windows = listOf(QuotaWindow("Monthly", usedFraction = 0.31f))),
    )
}

private val busyUsage =
    UsageState(totalTokens = 182_000, contextWindowTokens = 200_000, costAmount = 1.37, costCurrency = "USD")

@Composable
private fun DiagnosticsPreview(
    state: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    usage: UsageState?,
    quota: List<AgentQuota>? = null,
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface { DiagnosticsSheetContent(state, diagnostics, usage, quota) }
    }
}

@Preview(name = "Connected", showBackground = true)
@Preview(name = "Connected (dark)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectedPreview() {
    DiagnosticsPreview(ChatConnectionState.Connected, healthyDiagnostics, busyUsage, codexBarQuota())
}

@Preview(name = "Failed", showBackground = true)
@Composable
private fun FailedPreview() {
    val failed = ChatConnectionState.Failed("Unable to reach gateway")
    DiagnosticsPreview(failed, failingDiagnostics(), busyUsage, codexBarQuota())
}

@Preview(name = "No quota relayed (placeholder)", showBackground = true)
@Composable
private fun NoUsagePreview() {
    DiagnosticsPreview(ChatConnectionState.Connecting, healthyDiagnostics, usage = null)
}
