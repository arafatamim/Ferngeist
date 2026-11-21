package com.tamimarafat.ferngeist.feature.serverlist.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.feature.serverlist.R

/**
 * The filter chips both home tabs share: everything, the one live chip, then one per paired
 * computer. Selecting [liveValue] filters down to whatever that tab counts as live.
 */
@Composable
internal fun HomeFilterChips(
    liveValue: String,
    liveLabel: String,
    gateways: List<GatewaySource>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.home_filter_all)) },
        )
        FilterChip(
            selected = selected == liveValue,
            onClick = { onSelect(liveValue) },
            label = { Text(liveLabel) },
            leadingIcon = {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            },
        )
        gateways.forEach { gateway ->
            FilterChip(
                selected = selected == gateway.id,
                onClick = { onSelect(gateway.id) },
                label = { Text(gateway.name) },
            )
        }
    }
}
