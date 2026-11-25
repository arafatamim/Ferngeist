package com.tamimarafat.ferngeist.feature.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory scroll state store for tests. */
class InMemoryChatScrollStateStore : ChatScrollStateStore {
    private val entries = linkedMapOf<Pair<String, String>, ChatScrollSnapshot>()

    override suspend fun restore(
        serverId: String,
        sessionId: String,
    ): ChatScrollSnapshot? = entries[serverId to sessionId]

    override suspend fun save(
        serverId: String,
        sessionId: String,
        snapshot: ChatScrollSnapshot,
    ) {
        entries[serverId to sessionId] = snapshot
    }

    override suspend fun clear(
        serverId: String,
        sessionId: String,
    ) {
        entries.remove(serverId to sessionId)
    }
}

/** In-memory switcher hint store for tests; unseen until marked. */
class InMemorySwitcherHintStore : SwitcherHintStore {
    private val state = MutableStateFlow(false)
    override val seen: StateFlow<Boolean> = state

    override suspend fun markSeen() {
        state.value = true
    }
}

/** In-memory recent selection store for tests: newest first, no cap. */
class FakeRecentSelectionStore : RecentSelectionStore {
    private val entries = MutableStateFlow(emptyMap<String, List<String>>())

    override fun getRecentSelections(key: String): Flow<List<String>> = entries.map { it[key].orEmpty() }

    override suspend fun addSelection(
        key: String,
        value: String,
    ) {
        entries.update { it + (key to listOf(value) + it[key].orEmpty().minus(value)) }
    }

    override suspend fun clearByPrefix(prefix: String) {
        entries.update { map -> map.filterKeys { !it.startsWith(prefix) } }
    }
}
