package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.GatewayCredentialExpiredException
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurns
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Instant

/** Status per ACP session id, by agent id: one gateway's answer, ready to join onto its bindings. */
internal typealias AgentTurnStatuses = Map<String, Map<String, TurnStatus>>

private const val RUNNING = "running"
private const val SUCCEEDED = "succeeded"
private const val FAILED = "failed"

/**
 * Turn status per `targetId/sessionId`, from each paired gateway's last `/v1/turns` answer with
 * the hub's live status on top.
 *
 * Remote status lives in memory only: every answer replaces a gateway's whole snapshot, a failure
 * drops it, and nothing here is ever persisted or hydrated, so a cold start knows nothing until a
 * fetch lands.
 */
internal class TurnStatusCatalog(
    private val gatewayTurns: GatewayTurns,
    targets: Flow<List<LaunchableTarget>>,
    scope: CoroutineScope,
    hub: ChatConnectionHub,
) {
    private data class SeenTurns(
        val gatewayStartedAt: String,
        val turns: Set<GatewayTurn>,
    )

    private val remote = MutableStateFlow<Map<String, GatewayTurnsSnapshot?>>(emptyMap())
    private val acknowledged = HashMap<String, SeenTurns>()

    private val remoteStatuses =
        combine(remote, targets, hub.openChatIds) { snapshots, targets, open ->
            acknowledged.keys.retainAll(snapshots.keys)
            buildMap {
                targets
                    .filterIsInstance<LaunchableTarget.GatewayAgent>()
                    .groupBy { it.gatewaySource.id }
                    .forEach { (gatewayId, bindings) ->
                        val snapshot = snapshots[gatewayId] ?: return@forEach
                        val statuses = acknowledge(gatewayId, snapshot, bindings, open)
                        bindings.forEach { target ->
                            statuses[target.binding.agentId]?.forEach { (sessionId, status) ->
                                put("${target.id}/$sessionId", status)
                            }
                        }
                    }
            }
        }

    /**
     * A chat this phone tracks answers for itself and never inherits the gateway's answer: a
     * tracked chat whose local status was withdrawn (a dropped connection, a cold start) is
     * unknown, not a stale remote attention or finished claim. Untracked chats take the gateway's
     * status, and only an untracked chat can be absent from this map.
     */
    val statuses: StateFlow<Map<String, TurnStatus>> =
        combine(remoteStatuses, hub.trackedChatIds, hub.turnStatuses) { remote, tracked, local ->
            remote.filterKeys { it !in tracked } + local
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Chats with a turn in flight, for the readers that predate [statuses]. */
    val activeChatKeys: StateFlow<Set<String>> =
        statuses
            .map { all ->
                all.filterValues { it == TurnStatus.WORKING || it == TurnStatus.NEEDS_ATTENTION }.keys
            }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * One gateway's snapshot. Any failure — an older gateway's 404, no session service, offline —
     * leaves no status for it: an answer this phone cannot read never turns into a success claim.
     */
    suspend fun refresh(gateway: GatewaySource) {
        val answer =
            try {
                gatewayTurns.fetchTurns(gateway.scheme, gateway.host, gateway.gatewayCredential)
            } catch (error: CancellationException) {
                throw error
            } catch (_: GatewayRequestException) {
                null
            } catch (_: GatewayCredentialExpiredException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            }
        remote.update { it + (gateway.id to answer) }
    }

    /** Drops the statuses of gateways no longer paired. */
    fun retain(paired: Set<String>) {
        remote.update { all -> all.filterKeys { it in paired } }
    }

    /** Remember exact outcomes, even while a local connection masks the gateway's answer. */
    private fun acknowledge(
        gatewayId: String,
        snapshot: GatewayTurnsSnapshot,
        bindings: List<LaunchableTarget.GatewayAgent>,
        open: Set<String>,
    ): AgentTurnStatuses {
        val previous =
            acknowledged[gatewayId]?.takeIf { it.gatewayStartedAt == snapshot.gatewayStartedAt }?.turns.orEmpty()
        val seen =
            snapshot.turns.filterTo(HashSet()) { turn ->
                turn in previous ||
                    (
                        turn.state == SUCCEEDED &&
                            bindings.any { it.binding.agentId == turn.agentId && "${it.id}/${turn.sessionId}" in open }
                    )
            }
        acknowledged[gatewayId] = SeenTurns(snapshot.gatewayStartedAt, seen)
        return snapshot.turnStatuses(seen)
    }
}

/** One status per ACP chat, however many gateway sessions ran a turn for it. */
internal fun GatewayTurnsSnapshot.turnStatuses(acknowledged: Set<GatewayTurn> = emptySet()): AgentTurnStatuses =
    turns
        .groupBy { it.agentId }
        .mapValues { (_, agentTurns) ->
            agentTurns.groupBy { it.sessionId }.mapValues { (_, entries) -> entries.chatStatus(acknowledged) }
        }

/**
 * A prompt in flight outranks every stored outcome, and simultaneous running entries count
 * together, so a pending request on any of them needs attention. Otherwise the latest started
 * outcome is the current one: start times compare at the gateway's full nanosecond precision,
 * the change time and the gateway session id only break exact ties, and an entry whose start
 * time cannot be read claims nothing.
 */
private fun List<GatewayTurn>.chatStatus(acknowledged: Set<GatewayTurn>): TurnStatus {
    val running = filter { it.state == RUNNING }
    if (running.isNotEmpty()) {
        return if (running.any { it.attention.isNotEmpty() }) TurnStatus.NEEDS_ATTENTION else TurnStatus.WORKING
    }
    val latest =
        maxWithOrNull(
            compareBy<GatewayTurn>(
                { it.startedAt.instant() ?: Instant.MIN },
                { it.changedAt.instant() ?: Instant.MIN },
                { it.gatewaySessionId },
            ),
        ) ?: return TurnStatus.UNKNOWN
    // Unable to order this outcome against the others, the gateway session id means nothing.
    return if (latest.startedAt.instant() == null || latest in acknowledged) TurnStatus.UNKNOWN else latest.status()
}

/** Cancelled, unknown and anything a newer gateway adds carry no success claim. */
private fun GatewayTurn.status(): TurnStatus =
    when (state) {
        RUNNING -> TurnStatus.WORKING
        SUCCEEDED -> TurnStatus.FINISHED
        FAILED -> TurnStatus.ERROR
        else -> TurnStatus.UNKNOWN
    }

/** RFC3339Nano at the precision the gateway sent; a time this client cannot read orders last. */
private fun String?.instant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
