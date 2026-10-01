package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimarafat.ferngeist.core.common.ui.AgentIconBadge
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun ChatEmptyHero(
    iconUrl: String?,
    modifier: Modifier = Modifier,
) {
    val titles = LocalResources.current.getStringArray(R.array.chat_empty_titles)
    val title = remember { titles.random() }
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            // Registry logo only: custom/manual agents have no genuine icon,
            // and a placeholder glyph would read as branding that isn't there.
            if (iconUrl != null) {
                AgentIconBadge(
                    iconUrl = iconUrl,
                    fallback = Icons.Default.SmartToy,
                    size = 28.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicText(
                text = title,
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp, stepSize = 2.sp),
            )
        }
    }
}
