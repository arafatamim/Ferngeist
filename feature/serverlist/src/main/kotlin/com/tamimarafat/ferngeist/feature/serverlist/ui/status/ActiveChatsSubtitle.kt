package com.tamimarafat.ferngeist.feature.serverlist.ui.status

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import com.tamimarafat.ferngeist.core.common.ui.status.rememberShimmerTextBrush
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun ActiveChatsSubtitle(activeChats: Int) {
    val green = colorResource(R.color.serverlist_active_chats)
    var textWidth by remember { mutableFloatStateOf(0f) }
    val brush =
        rememberShimmerTextBrush(
            isActive = textWidth > 0f,
            baseColor = green,
            labelPrefix = "activeChats",
            // Blend white over green so the softer sweep never exposes the card background.
            highlightColor = lerp(green, Color.White, 0.65f),
            textWidth = textWidth,
        )
    Text(
        text = pluralStringResource(R.plurals.serverlist_active_chats, activeChats, activeChats),
        style = MaterialTheme.typography.bodyMedium.copy(brush = brush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { textWidth = it.getLineRight(0) - it.getLineLeft(0) },
    )
}
