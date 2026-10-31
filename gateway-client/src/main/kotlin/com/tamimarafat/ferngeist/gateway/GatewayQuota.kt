package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.core.model.AgentQuota
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.QuotaWindow
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.ProviderQuotas
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject

private const val MILLIS_PER_MINUTE = 60_000L
private const val PERCENT_SCALE = 100f

@Serializable
internal data class GatewayQuotaResponse(
    val providers: List<GatewayQuotaProvider>,
) {
    fun toQuotas(): List<AgentQuota> = providers.map { it.toQuota() }
}

@Serializable
internal data class GatewayQuotaProvider(
    val provider: String,
    val displayName: String? = null,
    val icon: String? = null,
    val usage: GatewayQuotaUsage? = null,
    val rateWindowLabels: GatewayQuotaLabels? = null,
) {
    fun toQuota(): AgentQuota {
        val updated = usage?.updatedAt.epochMillis()
        val balance = usage?.primary?.resetDescription?.takeIf { provider == "deepseek" && it.isNotBlank() }
        val windows =
            listOf(
                rateWindowLabels?.primary.orEmpty().ifBlank { "Primary" } to usage?.primary?.takeIf { balance == null },
                rateWindowLabels?.secondary.orEmpty().ifBlank { "Secondary" } to usage?.secondary,
                rateWindowLabels?.tertiary.orEmpty().ifBlank { "Tertiary" } to usage?.tertiary,
            ) + usage?.extraRateWindows.orEmpty().map { it.title to it.window }
        return AgentQuota(
            provider,
            updatedAtMs = updated,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: provider,
            icon = icon,
            balance = balance,
            windows =
                windows.mapNotNull { (label, window) ->
                    window?.toWindow(label, updated)
                },
        )
    }
}

@Serializable
internal data class GatewayQuotaUsage(
    val primary: GatewayQuotaWindow? = null,
    val secondary: GatewayQuotaWindow? = null,
    val tertiary: GatewayQuotaWindow? = null,
    val updatedAt: String? = null,
    val extraRateWindows: List<GatewayNamedWindow> = emptyList(),
)

@Serializable
internal data class GatewayQuotaLabels(
    val primary: String = "",
    val secondary: String = "",
    val tertiary: String = "",
)

@Serializable
internal data class GatewayNamedWindow(
    val title: String,
    val window: GatewayQuotaWindow? = null,
)

@Serializable
internal data class GatewayQuotaWindow(
    val usageKnown: Boolean? = null,
    val usedPercent: Float? = null,
    val windowMinutes: Long? = null,
    val resetsAt: String? = null,
    val resetDescription: String? = null,
) {
    fun toWindow(
        label: String,
        updated: Long?,
    ): QuotaWindow? {
        if (usageKnown == false || usedPercent == null) return null
        val reset = resetsAt.epochMillis()
        val duration = windowMinutes?.takeIf { it > 0 }?.times(MILLIS_PER_MINUTE)
        val pace =
            if (reset != null && duration != null && updated != null) {
                (1f - (reset - updated).toFloat() / duration).coerceIn(0f, 1f)
            } else {
                null
            }
        return QuotaWindow(label, (usedPercent / PERCENT_SCALE).coerceIn(0f, 1f), pace, reset)
    }
}

private fun String?.epochMillis(): Long? = this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

class GatewayProviderQuotas
    @Inject
    constructor(
        private val targets: LaunchableTargetRepository,
        private val gateway: GatewayRepositoryImpl,
        private val sources: GatewaySourceRepository,
    ) : ProviderQuotas {
        override suspend fun fetch(serverId: String): List<AgentQuota>? {
            val source = (targets.getTarget(serverId) as? LaunchableTarget.GatewayAgent)?.gatewaySource ?: return null
            val refreshed = refreshGatewaySourceIfNeeded(source, gateway, sources)
            return gateway.fetchQuotas(refreshed.scheme, refreshed.host, refreshed.gatewayCredential)
        }
    }
