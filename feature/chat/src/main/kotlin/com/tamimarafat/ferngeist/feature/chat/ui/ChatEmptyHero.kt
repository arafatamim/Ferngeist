package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.status.AgentMark
import com.tamimarafat.ferngeist.core.common.ui.typography.newChatEditorialStyle
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
            modifier = Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Registry logo only: custom/manual agents have no genuine icon,
            // and a placeholder glyph would read as branding that isn't there.
            if (iconUrl != null) {
                AgentMark(iconUrl = iconUrl, size = 48.dp, active = true)
            }
            Text(
                text = title,
                style = newChatEditorialStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}
