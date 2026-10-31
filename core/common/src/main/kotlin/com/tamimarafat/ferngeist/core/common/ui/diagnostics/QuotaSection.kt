package com.tamimarafat.ferngeist.core.common.ui.diagnostics

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.R
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.core.common.ui.formatCompactTokens
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.time.relativeTime
import com.tamimarafat.ferngeist.core.common.ui.time.rememberPresentationTime
import com.tamimarafat.ferngeist.core.model.AgentQuota
import com.tamimarafat.ferngeist.core.model.QuotaCost
import com.tamimarafat.ferngeist.core.model.QuotaWindow
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val MS_PER_MINUTE = 60_000L

/**
 * Plan quota of every provider account the gateway relays, as a segmented list: each card's header
 * names the tightest window, and expands to every window and the spend. [quota] is null (or empty)
 * while the gateway relays none, which shows static copy.
 */
@Composable
internal fun QuotaSection(quota: List<AgentQuota>?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (quota.isNullOrEmpty()) {
            SubtleText(stringResource(R.string.common_diagnostics_quota_placeholder))
            return@Column
        }
        val now = rememberPresentationTime()
        val locale = LocalLocale.current.platformLocale
        val comparator =
            compareBy<AgentQuota, String>(Collator.getInstance(locale)) { it.displayName }
                .thenBy { it.provider }
        val sorted = quota.sortedWith(comparator)
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            sorted.forEachIndexed { index, provider ->
                key(provider.provider) { ProviderCard(provider, index, quota.size, now, locale) }
            }
        }
    }
}

/** The card owns the container so the expanded details share the header's shape and tone. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderCard(
    quota: AgentQuota,
    index: Int,
    count: Int,
    now: Long,
    locale: Locale,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val tightest = quota.windows.maxByOrNull { it.usedFraction }
    val stateLabel = stringResource(if (expanded) R.string.common_expanded else R.string.common_collapsed)
    Column(Modifier.clip(shapes.shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        SegmentedListItem(
            onClick = { expanded = !expanded },
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(containerColor = Color.Transparent),
            modifier = Modifier.handCursor().semantics { stateDescription = stateLabel },
            leadingContent = { AgentIconBadge(iconUrl = quota.icon, fallback = Icons.Rounded.SmartToy) },
            supportingContent = {
                Text(
                    quota.balance
                        ?: quota.providerBalance?.let { currencyFormat(locale, it.currency).format(it.amount) }
                        ?: tightest?.label
                        ?: stringResource(R.string.common_quota_unavailable),
                    maxLines = 1,
                )
            },
            trailingContent = { ProviderTrailing(tightest, expanded, locale) },
        ) {
            Text(quota.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(visible = expanded) { ProviderDetails(quota, now, locale) }
    }
}

/** Tightest window's share used, red when exhausted or ahead of pace, then the chevron. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderTrailing(
    tightest: QuotaWindow?,
    expanded: Boolean,
    locale: Locale,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tightest?.let { window ->
            val used = window.usedFraction.coerceIn(0f, 1f)
            val colors = MaterialTheme.colorScheme
            Text(
                stringResource(R.string.common_quota_used, NumberFormat.getPercentInstance(locale).format(used)),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (used >= 1f || window.isOverPace()) colors.error else colors.onSurface,
            )
        }
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(chevronRotation))
    }
}

@Composable
private fun ProviderDetails(
    quota: AgentQuota,
    now: Long,
    locale: Locale,
) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val age = quota.updatedAtMs?.let { relativeTime(it, now).lowercase(locale) }
        val meta = listOfNotNull(quota.account, age?.let { stringResource(R.string.common_quota_updated, it) })
        SubtleText(meta.joinToString(" · "), MaterialTheme.typography.bodySmall)
        quota.balance?.let { SubtleText(it) }
        quota.providerBalance?.let { SubtleText(currencyFormat(locale, it.currency).format(it.amount)) }
        quota.billing.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubtleText(section.title, MaterialTheme.typography.labelMedium)
                section.rows.forEach { row ->
                    LabelValueRow(row.label, row.value, MaterialTheme.typography.bodyMedium)
                }
            }
        }
        quota.windows.forEach { QuotaWindowRow(it, now, locale) }
        if (quota.costs.isNotEmpty()) {
            val money = currencyFormat(locale, quota.costCurrency)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quota.costs.forEach { cost ->
                    LabelValueRow(cost.label, costValue(cost, money, locale), MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun QuotaWindow.isOverPace(): Boolean = paceFraction?.let { usedFraction > it } == true

/** Label and share used, the meter, then one wrapping line for reset and pace. */
@Composable
private fun QuotaWindowRow(
    window: QuotaWindow,
    now: Long,
    locale: Locale,
) {
    val percent = NumberFormat.getPercentInstance(locale)
    val used = window.usedFraction.coerceIn(0f, 1f)
    val pace = window.paceFraction?.coerceIn(0f, 1f)
    val detail =
        listOfNotNull(
            window.resetsAtMs?.let {
                stringResource(R.string.common_quota_resets_in, formatResetsIn(it - now, locale))
            },
            pace?.let { paceLabel(used, it, percent) },
        ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MeterRow(
            window.label,
            stringResource(R.string.common_quota_used, percent.format(used)),
            used,
            pace,
            MaterialTheme.typography.bodyMedium,
        )
        if (detail.isNotEmpty()) {
            val colors = MaterialTheme.colorScheme
            val color = if (window.isOverPace()) colors.error else colors.onSurfaceVariant
            SubtleText(detail, MaterialTheme.typography.bodySmall, color)
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
private fun SubtleText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = style, color = color)
}
