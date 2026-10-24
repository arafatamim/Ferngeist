package com.tamimarafat.ferngeist.feature.chat.deps

import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.ChatScrollStateStore
import com.tamimarafat.ferngeist.feature.chat.PendingPromptStore
import com.tamimarafat.ferngeist.feature.chat.SwitcherHintStore
import javax.inject.Inject

/** The session-side repositories a chat reads and writes. */
data class ChatRepositories
    @Inject
    constructor(
        val sessionRepository: SessionRepository,
        val launchableTargetRepository: LaunchableTargetRepository,
        val turnTimingRepository: TurnTimingRepository,
    )

/** The stores that keep a chat's local state: its scroll position, queued prompts and switcher hints. */
data class ChatLocalStores
    @Inject
    constructor(
        val scrollState: ChatScrollStateStore,
        val pendingPrompts: PendingPromptStore,
        val switcherHints: SwitcherHintStore,
        val recentSelections: RecentSelectionStore,
    )
