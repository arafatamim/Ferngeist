package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.formatCompactTokens
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.model.AgentQuota
import com.tamimarafat.ferngeist.core.model.QuotaCost
import com.tamimarafat.ferngeist.core.model.QuotaWindow
import java.text.NumberFormat
import java.util.Locale

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val MS_PER_MINUTE = 60_000L

// shortcut: opens on the first provider, not the agent in use; select by agent once the gateway names it.

/**
 * Plan quota of the agent's provider account: a provider picker, then one meter per window and the
 * spend. [quota] is null (or empty) while the gateway relays none, which shows static copy instead.
 */
@Composable
internal fun QuotaSection(quota: List<AgentQuota>?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.common_diagnostics_quota))
        if (quota.isNullOrEmpty()) {
            Text(
                stringResource(R.string.common_diagnostics_quota_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        var selected by rememberSaveable { mutableIntStateOf(0) }
        val index = selected.coerceIn(quota.indices)
        if (quota.size > 1) ProviderPicker(quota.map { it.provider }, index) { selected = it }
        ProviderQuota(quota[index])
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderPicker(
    providers: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        providers.forEachIndexed { i, provider ->
            ToggleButton(
                checked = i == selected,
                onCheckedChange = { onSelect(i) },
                shapes = ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size),
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) { Text(provider) }
        }
    }
}

@Composable
private fun ProviderQuota(quota: AgentQuota) {
    val now = rememberPresentationTime()
    val locale = LocalLocale.current.platformLocale
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            quota.account?.let { SubtleText(it) }
            val age = relativeTime(quota.updatedAtMs, now).lowercase(locale)
            SubtleText(stringResource(R.string.common_quota_updated, age))
        }
        quota.windows.forEach { QuotaWindowRow(it, now, locale) }
        if (quota.costs.isNotEmpty()) CostRows(quota, locale)
    }
}

@Composable
private fun QuotaWindowRow(
    window: QuotaWindow,
    now: Long,
    locale: Locale,
) {
    val percent = NumberFormat.getPercentInstance(locale)
    val used = window.usedFraction.coerceIn(0f, 1f)
    val pace = window.paceFraction?.coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(window.label, style = MaterialTheme.typography.titleMediumEmphasized)
        MeterBar(used, pace)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    stringResource(R.string.common_quota_used, percent.format(used)),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                pace?.let { SubtleText(paceLabel(used, it, percent)) }
            }
            Column(horizontalAlignment = Alignment.End) {
                window.resetsAtMs?.let { resetsAt ->
                    val remaining = formatResetsIn(resetsAt - now, locale)
                    SubtleText(stringResource(R.string.common_quota_resets_in, remaining))
                }
                if (pace != null && used <= pace) SubtleText(stringResource(R.string.common_quota_lasts_until_reset))
            }
        }
    }
}

@Composable
private fun paceLabel(
    used: Float,
    pace: Float,
    percent: NumberFormat,
): String =
    if (used <= pace) {
        stringResource(R.string.common_quota_in_reserve, percent.format(pace - used))
    } else {
        stringResource(R.string.common_quota_over_pace, percent.format(used - pace))
    }

@Composable
private fun CostRows(
    quota: AgentQuota,
    locale: Locale,
) {
    val money = currencyFormat(locale, quota.costCurrency)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.common_diagnostics_cost), style = MaterialTheme.typography.titleMediumEmphasized)
        quota.costs.forEach { cost -> LabelValueRow(cost.label, costValue(cost, money, locale)) }
    }
}

@Composable
private fun costValue(
    cost: QuotaCost,
    money: NumberFormat,
    locale: Locale,
): String {
    val amount = money.format(cost.amount)
    val tokens = cost.tokens ?: return amount
    return stringResource(R.string.common_quota_cost_value, amount, formatCompactTokens(tokens, locale))
}

/** Two largest non-zero units, e.g. "4h 24m"; localized by ICU so no unit strings are needed. */
private fun formatResetsIn(
    remainingMs: Long,
    locale: Locale,
): String {
    val minutes = (remainingMs / MS_PER_MINUTE).coerceAtLeast(0)
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes % MINUTES_PER_DAY / MINUTES_PER_HOUR
    val parts =
        when {
            days > 0 -> listOf(Measure(days, MeasureUnit.DAY), Measure(hours, MeasureUnit.HOUR))
            hours > 0 ->
                listOf(Measure(hours, MeasureUnit.HOUR), Measure(minutes % MINUTES_PER_HOUR, MeasureUnit.MINUTE))
            else -> listOf(Measure(minutes, MeasureUnit.MINUTE))
        }
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
    return parts.joinToString(" ") { format.formatMeasures(it) }
}

@Composable
private fun SubtleText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
