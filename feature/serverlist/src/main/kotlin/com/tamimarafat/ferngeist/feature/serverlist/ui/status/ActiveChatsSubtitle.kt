package com.tamimarafat.ferngeist.feature.serverlist.ui.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.tamimarafat.ferngeist.core.common.ui.status.ShimmerStatusText
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun ActiveChatsSubtitle(activeChats: Int) {
    ShimmerStatusText(
        text = pluralStringResource(R.plurals.serverlist_active_chats, activeChats, activeChats),
    )
}
