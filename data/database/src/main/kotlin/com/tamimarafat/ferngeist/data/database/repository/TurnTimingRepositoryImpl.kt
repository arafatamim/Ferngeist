package com.tamimarafat.ferngeist.data.database.repository

import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.core.model.repository.TurnTimingRepository
import com.tamimarafat.ferngeist.data.database.dao.TurnTimingDao
import com.tamimarafat.ferngeist.data.database.entity.TurnTimingEntity
import java.util.concurrent.TimeUnit

class TurnTimingRepositoryImpl(
    private val dao: TurnTimingDao,
    private val now: () -> Long = System::currentTimeMillis,
) : TurnTimingRepository {
    override suspend fun getTimings(
        serverId: String,
        sessionId: String,
    ): Map<TurnKey, TurnTiming> {
        // Pruned on read: every chat open is a chance, so no scheduled job is needed.
        dao.deleteFinishedBefore(now() - retentionMs)
        return dao.getForSession(serverId, sessionId).associate {
            TurnKey(it.turnIndex, it.promptHash) to TurnTiming(it.startedAt, it.finishedAt)
        }
    }

    override suspend fun record(
        serverId: String,
        sessionId: String,
        key: TurnKey,
        timing: TurnTiming,
    ) {
        dao.upsert(
            TurnTimingEntity(serverId, sessionId, key.index, key.promptHash, timing.startedAt, timing.finishedAt),
        )
    }

    private companion object {
        val retentionMs = TimeUnit.DAYS.toMillis(7)
    }
}
