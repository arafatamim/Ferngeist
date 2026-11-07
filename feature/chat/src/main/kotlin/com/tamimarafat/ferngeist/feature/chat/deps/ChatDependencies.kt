package com.tamimarafat.ferngeist.feature.chat.deps

import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.ChatListings
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.ChatScrollStateStore
import com.tamimarafat.ferngeist.feature.chat.PendingPromptStore
import com.tamimarafat.ferngeist.feature.chat.SwitcherHintStore
import com.tamimarafat.ferngeist.feature.chat.activity.PhoneTurnClock
import javax.inject.Inject

/** The session-side repositories a chat reads and writes. */
data class ChatRepositories
    @Inject
    constructor(
        val sessionRepository: ChatCatalog,
        val launchableTargetRepository: LaunchableTargetRepository,
        val turnTimingRepository: TurnTimingRepository,
        val chatListings: ChatListings,
        val providerQuotas: com.tamimarafat.ferngeist.core.model.repository.ProviderQuotas,
    )

/** The stores that keep a chat's local state: its scroll position, queued prompts, switcher hints and own turns. */
data class ChatLocalStores
    @Inject
    constructor(
        val scrollState: ChatScrollStateStore,
        val pendingPrompts: PendingPromptStore,
        val switcherHints: SwitcherHintStore,
        val recentSelections: RecentSelectionStore,
        val phoneTurns: PhoneTurnClock,
    )
