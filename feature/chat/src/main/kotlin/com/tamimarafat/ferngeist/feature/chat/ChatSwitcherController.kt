package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatSessionPresence
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.WorktreeRef
import com.tamimarafat.ferngeist.core.model.iconUrl
import com.tamimarafat.ferngeist.core.model.repository.ChatListings
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Builds the chat switcher's list of live sessions and releases the ones the user closes,
 * so [ChatViewModel] only forwards intents and exposes the resulting state.
 */
internal class ChatSwitcherController(
    private val scope: CoroutineScope,
    private val presence: ChatSessionPresence,
    private val chatListings: ChatListings,
    private val launchableTargetRepository: LaunchableTargetRepository,
    private val serverId: String,
    private val currentSessionId: () -> String,
) {
    companion object {
        /**
         * Byte-identical to the emitters the session list uses for the same
         * refusals: one rule, one phrasing, and [localizeChatError] finds the
         * resource for these keys.
         */
        private const val CLOSE_STREAMING_MESSAGE =
            "This session is still responding. Cancel or close it from inside the chat first."
        private const val CLOSE_GATEWAY_ONLY_MESSAGE = "Close is only available for gateway sessions."
        private const val CLOSE_FAILED_MESSAGE = "Failed to close session."
    }

    /** Live sessions across all servers, current included, for the chat switcher. */
    val state: StateFlow<SwitcherUiState> =
        switcherFlow().stateIn(scope, SharingStarted.WhileSubscribed(5000), SwitcherUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun switcherFlow(): Flow<SwitcherUiState> =
        presence.warmServers
            .flatMapLatest { serverIds ->
                if (serverIds.isEmpty()) {
                    flowOf(SwitcherUiState())
                } else {
                    combineSwitcherRows(serverIds.sorted())
                }
            }

    private fun combineSwitcherRows(serverIds: List<String>): Flow<SwitcherUiState> =
        combine(
            combine(
                serverIds.map { sid ->
                    combine(
                        chatListings.getSessions(sid),
                        presence.connectedSessionIds(sid),
                    ) { sessions, liveIds ->
                        Triple(sid, sessions, liveIds)
                    }
                },
            ) { triples -> triples },
            worktreesFlow(serverIds),
            presence.turnStatuses,
        ) { triples, worktreesByServer, statuses ->
            val sessionsByServer = triples.associate { it.first to it.second }
            val liveByServer = triples.associate { it.first to it.third }
            val servers =
                triples.map { it.first }.distinct().map { sid ->
                    val target = launchableTargetRepository.getTarget(sid)
                    SwitcherServer(
                        id = sid,
                        name = target?.name ?: sid,
                        worktrees = worktreesByServer[sid].orEmpty(),
                        // One paired gateway shares its projects across all its agents.
                        sourceId = (target as? LaunchableTarget.GatewayAgent)?.gatewaySource?.id ?: sid,
                        iconUrl = target?.iconUrl,
                    )
                }
            val groups =
                deriveSwitcherGroups(
                    servers = servers,
                    sessionsByServer = sessionsByServer,
                    liveIdsByServer = liveByServer,
                    turnStatus = { sid, sess -> statuses["$sid/$sess"] ?: TurnStatus.UNKNOWN },
                    current = SwitcherCurrent(serverId, currentSessionId()),
                )
            SwitcherUiState(
                groups = groups,
                liveTotalCount = groups.sumOf { it.sessions.size },
                hasOthers = groups.any { group -> group.sessions.any { !it.isCurrent } },
            )
        }

    /** The last listing a gateway answered with, so a failed refresh does not rename projects. */
    private var lastWorktrees: Map<String, List<WorktreeRef>> = emptyMap()

    /**
     * The gateway's worktrees per live server. The last successful listing paints first so the
     * sheet opens with the right project names, then the refresh replaces it: a worktree folder
     * is named for its branch, and only the gateway's listing says which repo it belongs to.
     */
    private fun worktreesFlow(serverIds: List<String>): Flow<Map<String, List<WorktreeRef>>> =
        flow {
            emit(lastWorktrees)
            val fetched = serverIds.associateWith { presence.worktrees(it) }
            val merged = serverIds.associateWith { sid -> fetched[sid] ?: lastWorktrees[sid].orEmpty() }
            lastWorktrees = merged
            emit(merged)
        }

    /**
     * Releases a live session the switcher listed, through the same hub call the
     * session list's Disconnect makes so both surfaces mean one thing by
     * "close": the gateway process stops and the local transport is released.
     * Refused while the session streams — the gateway would cut a live turn —
     * and for manual agents, which have no gateway leg to release.
     *
     * Returns the message to show when the close is refused or fails, or null on success.
     */
    suspend fun close(
        targetServerId: String,
        targetSessionId: String,
    ): String? {
        if (presence.isStreaming(targetServerId, targetSessionId)) return CLOSE_STREAMING_MESSAGE
        if (!presence.isGatewayBacked(targetServerId)) return CLOSE_GATEWAY_ONLY_MESSAGE
        return runCatching {
            presence.closeSession(targetServerId, targetSessionId)
        }.fold(
            onSuccess = { null },
            onFailure = { error -> error.message ?: CLOSE_FAILED_MESSAGE },
        )
    }
}
