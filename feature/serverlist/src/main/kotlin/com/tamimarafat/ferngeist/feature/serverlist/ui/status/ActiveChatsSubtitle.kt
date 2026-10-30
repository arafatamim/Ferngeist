package com.tamimarafat.ferngeist.feature.serverlist.ui.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingStatusText
import com.tamimarafat.ferngeist.core.common.ui.status.rememberSpinnerVerb
import com.tamimarafat.ferngeist.feature.serverlist.R

@Composable
internal fun ActiveChatsSubtitle(workingChatIds: List<String>) {
    // Borrows the lead chat's verb, so the row agrees with that chat's own card.
    val verb = rememberSpinnerVerb(workingChatIds.first()).lowercase()
    val count = workingChatIds.size
    WorkingStatusText(
        text = pluralStringResource(R.plurals.serverlist_active_chats, count, count, verb),
    )
}
