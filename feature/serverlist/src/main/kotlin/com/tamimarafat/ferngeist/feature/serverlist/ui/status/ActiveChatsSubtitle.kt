package com.tamimarafat.ferngeist.feature.serverlist.ui.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingStatusText
import com.tamimarafat.ferngeist.core.common.ui.status.rememberSpinnerVerb
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun ActiveChatsSubtitle(
    activeChats: Int,
    streamKey: String,
) {
    val verb = rememberSpinnerVerb(streamKey).lowercase()
    WorkingStatusText(
        text = pluralStringResource(R.plurals.serverlist_active_chats, activeChats, activeChats, verb),
    )
}
