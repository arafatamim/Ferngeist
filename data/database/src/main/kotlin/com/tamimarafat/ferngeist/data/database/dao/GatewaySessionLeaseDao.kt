package com.tamimarafat.ferngeist.data.database.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Gateway session leases recorded on `sessions` rows. Kept apart from [SessionDao] so the
 * session list reads and writes stay one cohesive surface; the claim transaction that spans
 * both lives in `SessionRepositoryImpl.setGatewaySessionId`.
 */
@Dao
interface GatewaySessionLeaseDao {
    @Query(
        "UPDATE sessions SET gatewaySessionId = :gatewaySessionId " +
            "WHERE sessionId = :sessionId AND serverId = :serverId",
    )
    suspend fun updateGatewaySessionId(
        sessionId: String,
        serverId: String,
        gatewaySessionId: String?,
    ): Int

    /**
     * Clears [gatewaySessionId] from every other row on [serverId] that still
     * claims it. A gateway session is leased to exactly one chat, so when the
     * true owner records its session any stale claimant must release it —
     * otherwise two chats resume one session and fight over the lease.
     */
    @Query(
        "UPDATE sessions SET gatewaySessionId = NULL WHERE serverId = :serverId " +
            "AND gatewaySessionId = :gatewaySessionId AND sessionId != :sessionId",
    )
    suspend fun clearGatewaySessionClaim(
        serverId: String,
        gatewaySessionId: String,
        sessionId: String,
    ): Int

    /**
     * Drops recorded gateway leases on [serverId] that are absent from
     * [liveIds]. Callers must branch on an empty [liveIds]: Room expands an
     * empty `NOT IN` list to `NOT IN (NULL)`, which matches nothing — but an
     * empty live set means every pointer is stale.
     */
    @Query(
        "UPDATE sessions SET gatewaySessionId = NULL WHERE serverId = :serverId " +
            "AND gatewaySessionId IS NOT NULL AND gatewaySessionId NOT IN (:liveIds)",
    )
    suspend fun clearStaleGatewaySessions(
        serverId: String,
        liveIds: Set<String>,
    ): Int

    @Query(
        "UPDATE sessions SET gatewaySessionId = NULL WHERE serverId = :serverId " +
            "AND gatewaySessionId IS NOT NULL",
    )
    suspend fun clearAllGatewaySessions(serverId: String): Int
}
