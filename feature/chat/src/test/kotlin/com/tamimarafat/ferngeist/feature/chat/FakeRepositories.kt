package com.tamimarafat.ferngeist.feature.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

/** No-op recent selection store for tests. */
class FakeRecentSelectionStore : RecentSelectionStore {
    override fun getRecentSelections(key: String): Flow<List<String>> = emptyFlow()

    override suspend fun addSelection(
        key: String,
        value: String,
    ) {
        // No-op: recent selections are not tracked in this fake.
    }

    override suspend fun clearByPrefix(prefix: String) {
        // No-op: recent selections are not tracked in this fake.
    }
}
