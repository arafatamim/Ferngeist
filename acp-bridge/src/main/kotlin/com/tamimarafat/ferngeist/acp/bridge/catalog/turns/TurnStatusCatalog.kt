package com.tamimarafat.ferngeist.acp.bridge.catalog.turns

import com.tamimarafat.ferngeist.acp.bridge.connection.computeReconnectDelayMs
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnActivity
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.GatewayCredentialInvalidException
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnKey
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStreamClosedException
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStreamFrame
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

// Close codes from the wire contract that need a behaviour other than reconnecting with backoff.
private const val CLOSE_CREDENTIAL_INVALID = 4401
private const val CLOSE_BEHIND = 4408
private const val CLOSE_SUPERSEDED = 4409

/**
 * Turn status per `targetId/sessionId`, from each paired gateway's `/v1/turns/stream` socket
 * while the app is visible. A gateway that predates the stream answers 404 and contributes no
 * status, exactly like a manual server: an absent badge, never a wrong one.
 *
 * Remote status lives in memory only: every frame replaces a gateway's whole snapshot, a closed
 * socket drops it, and nothing here is ever persisted or hydrated, so a cold start knows nothing
 * until the first frame lands.
 */
internal class TurnStatusCatalog(
    private val turnStream: GatewayTurnStream,
    bindings: Flow<List<LaunchableTarget>>,
    private val scope: CoroutineScope,
    private val hub: ChatConnectionHub,
) {
    /** This phone's bindings as a state, so a publish can resolve a gateway turn to its chat key. */
    private val targets = bindings.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private data class SeenTurns(
        val gatewayStartedAt: String,
        val turns: Set<GatewayTurn>,
    )

    private data class RemoteSnapshot(
        val snapshot: GatewayTurnsSnapshot,
        val expiredOutcomes: Set<GatewayTurn>,
        val receivedAt: ComparableTimeMark = TimeSource.Monotonic.markNow(),
        val generation: Any = Any(),
    )

    private val remote = MutableStateFlow<Map<String, RemoteSnapshot?>>(emptyMap())
    private val acknowledged = HashMap<String, SeenTurns>()
    private val expiryJobs = HashMap<String, Job>()

    private val remoteStatuses =
        combine(remote, targets, hub.openChatIds) { snapshots, targets, open ->
            acknowledged.keys.retainAll(snapshots.keys)
            buildMap {
                targets
                    .filterIsInstance<LaunchableTarget.GatewayAgent>()
                    .groupBy { it.gatewaySource.id }
                    .forEach { (gatewayId, bindings) ->
                        val state = snapshots[gatewayId] ?: return@forEach
                        val statuses = acknowledge(gatewayId, state, bindings, open)
                        bindings.forEach { target ->
                            statuses[target.binding.agentId]?.forEach { (sessionId, status) ->
                                put("${target.id}/$sessionId", status)
                            }
                        }
                    }
            }
        }

    val statuses: StateFlow<Map<String, TurnActivity>> =
        remoteStatuses.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val turnEnds = Channel<Set<String>>(Channel.UNLIMITED)

    /**
     * The tracked chats whose running turn disappeared from a gateway's new snapshot, as
     * `targetId/sessionId` keys. A withdrawal emits nothing: the socket dropping says the answer
     * went stale, not that every turn on that gateway ended.
     */
    val turnEnded: Flow<Set<String>> = turnEnds.receiveAsFlow()

    /** Chats with a turn in flight, for the readers that predate [statuses]. */
    val activeChatKeys: StateFlow<Set<String>> =
        statuses
            .map { all ->
                all.filterValues { it.status == TurnStatus.WORKING || it.status == TurnStatus.NEEDS_ATTENTION }.keys
            }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Streams every paired gateway's status until cancelled. The app collects it while visible;
     * cancelling closes every socket and withdraws every streamed snapshot.
     */
    suspend fun stream(gateways: Flow<List<GatewaySource>>) {
        gateways.collectLatest { list ->
            coroutineScope {
                list.forEach { gateway -> launch { streamGateway(gateway) } }
            }
        }
    }

    fun retain(paired: Set<String>) {
        synchronized(expiryJobs) {
            expiryJobs.keys.filterNot { it in paired }.forEach { gatewayId ->
                expiryJobs.remove(gatewayId)?.cancel()
            }
            remote.update { all -> all.filterKeys { it in paired } }
        }
    }

    private sealed interface StreamEnd {
        data object Ended : StreamEnd

        data class Closed(
            val code: Int,
        ) : StreamEnd

        data class Refused(
            val status: Int,
        ) : StreamEnd

        data object Failed : StreamEnd
    }

    private enum class StreamAction { STOP, RECONNECT_NOW, RECONNECT }

    /**
     * Connects one gateway, publishes its frames, and reconnects until the stream is cancelled or
     * the gateway says to stop (contract C5's close-code table). A snapshot resets the backoff.
     */
    private suspend fun streamGateway(gateway: GatewaySource) {
        var attempt = 0
        while (true) {
            val end =
                try {
                    collectFrames(gateway) { attempt = 0 }
                } finally {
                    // C5: the state is valid only while the socket is open, so every end of a
                    // collection — including cancellation — withdraws it before anything else.
                    replaceSnapshot(gateway.id, null)
                }
            when (streamAction(end)) {
                StreamAction.STOP -> return
                StreamAction.RECONNECT_NOW -> attempt = 0
                StreamAction.RECONNECT -> delay(computeReconnectDelayMs(++attempt))
            }
        }
    }

    private suspend fun collectFrames(
        gateway: GatewaySource,
        onSnapshot: () -> Unit,
    ): StreamEnd {
        val entries = LinkedHashMap<GatewayTurnKey, GatewayTurn>()
        var gatewayStartedAt: String? = null
        return try {
            turnStream.frames(gateway.scheme, gateway.host, gateway.gatewayCredential).collect { frame ->
                when (frame) {
                    is GatewayTurnStreamFrame.Snapshot -> {
                        entries.clear()
                        frame.snapshot.turns.forEach { entries[it.key()] = it }
                        gatewayStartedAt = frame.snapshot.gatewayStartedAt
                        publish(gateway.id, frame.snapshot)
                        onSnapshot()
                    }
                    is GatewayTurnStreamFrame.Changes -> {
                        val started = gatewayStartedAt ?: return@collect
                        frame.removed.forEach(entries::remove)
                        frame.upserts.forEach { entries[it.key()] = it }
                        publish(gateway.id, GatewayTurnsSnapshot(started, frame.observedAt, entries.values.toList()))
                    }
                }
            }
            StreamEnd.Ended
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GatewayRequestException) {
            StreamEnd.Refused(error.statusCode)
        } catch (closed: GatewayTurnStreamClosedException) {
            StreamEnd.Closed(closed.code)
        } catch (_: GatewayCredentialInvalidException) {
            StreamEnd.Closed(CLOSE_CREDENTIAL_INVALID)
        } catch (_: IOException) {
            StreamEnd.Failed
        } catch (_: SerializationException) {
            StreamEnd.Failed
        }
    }

    private fun streamAction(end: StreamEnd): StreamAction =
        when (end) {
            // Only our own cancellation closes the socket from this side, and that never gets
            // here, so a stream that just ended (1000, 1001, no close reason) was the gateway's.
            StreamEnd.Ended, StreamEnd.Failed -> StreamAction.RECONNECT
            is StreamEnd.Closed -> closeAction(end.code)
            is StreamEnd.Refused -> refusalAction(end.status)
        }

    private fun closeAction(code: Int): StreamAction =
        when (code) {
            CLOSE_BEHIND -> StreamAction.RECONNECT_NOW
            CLOSE_CREDENTIAL_INVALID, CLOSE_SUPERSEDED -> StreamAction.STOP
            else -> StreamAction.RECONNECT
        }

    private fun refusalAction(status: Int): StreamAction =
        when (status) {
            // The gateway predates the stream: no status for it, and nothing to poll.
            HTTP_NOT_FOUND -> StreamAction.STOP
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> StreamAction.STOP
            else -> StreamAction.RECONNECT
        }

    private fun GatewayTurn.key() = GatewayTurnKey(agentId, gatewaySessionId, sessionId)

    /** Publishes one answer; the pull and every frame share this path's acknowledgement and expiry. */
    private fun publish(
        gatewayId: String,
        answer: GatewayTurnsSnapshot,
    ) {
        val expired = HashSet<GatewayTurn>()
        val scheduledExpirations = ArrayList<Pair<GatewayTurn, Long>>()
        answer.turns.forEach { turn ->
            if (turn.state == SUCCEEDED || turn.state == FAILED) {
                val remaining = turn.expiryDelay(answer.observedAt)
                if (remaining == 0L) expired += turn else scheduledExpirations += turn to remaining
            }
        }
        publishTurnEnds(gatewayId, answer)
        replaceSnapshot(gatewayId, RemoteSnapshot(answer, expired), scheduledExpirations)
    }

    /**
     * Tells the projects catalog which tracked chats just finished, so it can refetch the
     * timestamps the gateway stamps on completion. Only a snapshot over another snapshot
     * compares: a withdrawal neither emits nor becomes the comparison base, or every network
     * blip would refetch `/v1/projects`.
     */
    private fun publishTurnEnds(
        gatewayId: String,
        next: GatewayTurnsSnapshot,
    ) {
        val previous = remote.value[gatewayId]?.snapshot ?: return
        val tracked = hub.trackedChatIds.value
        val ended = runningChats(previous) - runningChats(next)
        val endedTracked = ended.filterTo(HashSet()) { it in tracked }
        if (endedTracked.isNotEmpty()) turnEnds.trySend(endedTracked)
    }

    /** The chat keys this gateway reports a running turn for, under this phone's target ids. */
    private fun runningChats(snapshot: GatewayTurnsSnapshot): Set<String> {
        val bindings = targets.value.filterIsInstance<LaunchableTarget.GatewayAgent>()
        return snapshot.turns
            .filter { it.state == RUNNING }
            .mapNotNullTo(HashSet()) { turn ->
                bindings.firstOrNull { it.binding.agentId == turn.agentId }?.let { "${it.id}/${turn.sessionId}" }
            }
    }

    private fun replaceSnapshot(
        gatewayId: String,
        snapshot: RemoteSnapshot?,
        scheduledExpirations: List<Pair<GatewayTurn, Long>> = emptyList(),
    ) {
        synchronized(expiryJobs) {
            remote.update { it + (gatewayId to snapshot) }
            expiryJobs.remove(gatewayId)?.cancel()
            if (snapshot == null || scheduledExpirations.isEmpty()) return
            expiryJobs[gatewayId] =
                scope.launch {
                    var waiting = scheduledExpirations
                    var elapsed = 0L
                    while (waiting.isNotEmpty()) {
                        val deadline = waiting.minOf { it.second }
                        delay(deadline - elapsed)
                        elapsed = deadline
                        val (due, later) = waiting.partition { it.second == deadline }
                        remote.update { all ->
                            val current = all[gatewayId]
                            if (current?.generation == snapshot.generation) {
                                val expired = current.expiredOutcomes + due.map { it.first }
                                all + (gatewayId to current.copy(expiredOutcomes = expired))
                            } else {
                                all
                            }
                        }
                        waiting = later
                    }
                }
        }
    }

    /** Remember exact outcomes, even while a local connection masks the gateway's answer. */
    private fun acknowledge(
        gatewayId: String,
        state: RemoteSnapshot,
        bindings: List<LaunchableTarget.GatewayAgent>,
        open: Set<String>,
    ): AgentTurnStatuses {
        val snapshot = state.snapshot
        val previous =
            acknowledged[gatewayId]?.takeIf { it.gatewayStartedAt == snapshot.gatewayStartedAt }?.turns.orEmpty()
        val seen =
            snapshot.turns.filterTo(HashSet()) { turn ->
                turn in previous ||
                    (
                        (turn.state == SUCCEEDED || turn.state == FAILED) &&
                            bindings.any { it.binding.agentId == turn.agentId && "${it.id}/${turn.sessionId}" in open }
                    )
            }
        acknowledged[gatewayId] = SeenTurns(snapshot.gatewayStartedAt, seen)
        return snapshot.turnStatuses(seen, state.expiredOutcomes, state.receivedAt)
    }
}
