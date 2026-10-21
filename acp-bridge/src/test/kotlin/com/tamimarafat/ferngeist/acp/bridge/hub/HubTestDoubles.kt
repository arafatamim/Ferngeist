package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.GatewayFileContents
import com.tamimarafat.ferngeist.gateway.GatewayFileList
import com.tamimarafat.ferngeist.gateway.GatewayGitCommitFiles
import com.tamimarafat.ferngeist.gateway.GatewayGitLog
import com.tamimarafat.ferngeist.gateway.GatewayGitRefs
import com.tamimarafat.ferngeist.gateway.GatewayProtocol
import com.tamimarafat.ferngeist.gateway.GatewayRemoteStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import com.tamimarafat.ferngeist.gateway.GatewaySessionResumeResponse
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import com.tamimarafat.ferngeist.gateway.GatewayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Deterministic clock: each call advances one tick, so focus order is exact. */
internal fun tickingClock(): () -> Long {
    var ticks = 0L
    return { ++ticks }
}

internal fun newHub(
    repo: GatewayRepository = FakeGatewayRepo(),
    maxHot: Int = 3,
    maxGateway: Int = 5,
    sessionRepository: SessionRepository = FakeSessionRepo(),
    clock: () -> Long = tickingClock(),
): ChatConnectionHub =
    ChatConnectionHub(
        gatewayRepository = repo,
        scope = CoroutineScope(Dispatchers.Unconfined),
        connectivityObserver = ConnectivityObserverStub(initialState = true),
        sessionRepository = sessionRepository,
        limits =
            HubLimits(
                maxHotConnections = maxHot,
                maxGatewaySessionsPerDevice = maxGateway,
                clock = clock,
            ),
    )

internal suspend fun ChatConnectionHub.registerChat(
    serverId: String = "srv",
    sessionId: String,
    gatewaySessionId: String? = "gs-$sessionId",
    gatewaySourceId: String = "src-1",
    agentId: String = "agent-1",
    connected: Boolean = true,
    streaming: Boolean = false,
    manager: AcpConnection? = null,
): String =
    register(
        serverId = serverId,
        sessionId = sessionId,
        gatewaySessionId = gatewaySessionId,
        gatewaySourceId = gatewaySourceId,
        agentId = agentId,
        isConnected = { connected },
        isStreaming = { streaming },
        manager = manager,
    )

internal class FakeGatewayRepo : GatewayRepository {
    val closed = mutableListOf<String>()
    var sessions: List<GatewaySessionSummary> = emptyList()
    var closeFailure: Exception? = null
    var listFailure: Exception? = null
    var resumeFailure: Exception? = null
    var resumedSessions = mutableListOf<String>()
    var protocolVersion: String? = GatewayProtocol.CURRENT
    var connectCalls = 0
    var connectFailure: Exception? = null

    override suspend fun createWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
        base: String?,
        branch: String?,
    ) = TODO("unused in these tests")

    override suspend fun listWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ) = TODO("unused in these tests")

    override suspend fun listUnmanagedWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
    ) = TODO("unused in these tests")

    override suspend fun adoptWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        path: String,
        repo: String?,
    ) = TODO("unused in these tests")

    override suspend fun fetchRepoRefs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String,
    ) = TODO("unused in these tests")

    override suspend fun deleteWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        worktreeId: String,
        force: Boolean,
    ) = TODO("unused in these tests")

    override suspend fun fetchStatus(
        scheme: String,
        host: String,
    ): GatewayStatus =
        GatewayStatus(
            name = "gw",
            version = "dev",
            protocolVersion = protocolVersion,
            remote = GatewayRemoteStatus(configured = true),
        )

    override suspend fun startPairing(
        scheme: String,
        host: String,
    ) = TODO("unused in these tests")

    override suspend fun completePairing(
        scheme: String,
        host: String,
        challengeId: String,
        code: String,
        deviceName: String,
    ) = TODO("unused in these tests")

    override suspend fun refreshCredential(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ) = TODO("unused in these tests")

    override suspend fun fetchAgents(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ) = TODO("unused in these tests")

    override suspend fun connectAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ): GatewayConnectResponse {
        connectCalls += 1
        connectFailure?.let { throw it }
        return GatewayConnectResponse(
            runtimeId = "rt-1",
            sessionId = "s-new",
            attachToken = "token",
            webSocketUrl = "ws://gw/v1/acp/rt-1",
        )
    }

    override suspend fun restartRuntime(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        envVars: Map<String, String>,
    ) = TODO("unused in these tests")

    override suspend fun fetchRuntimeLogs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
    ) = TODO("unused in these tests")

    override suspend fun resumeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    ): GatewaySessionResumeResponse {
        resumedSessions += sessionId
        resumeFailure?.let { throw it }
        return GatewaySessionResumeResponse(attachToken = "fresh-token")
    }

    override suspend fun listGatewaySessions(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): List<GatewaySessionSummary> {
        listFailure?.let { throw it }
        return sessions
    }

    override suspend fun closeSession(
        scheme: String,
        host: String,
        gatewayCredential: String,
        sessionId: String,
    ) {
        closed += sessionId
        closeFailure?.let { throw it }
    }

    override suspend fun getPushConfig(
        scheme: String,
        host: String,
        gatewayCredential: String,
    ): com.tamimarafat.ferngeist.gateway.GatewayPushConfig = TODO()

    override suspend fun registerPushSubscription(
        scheme: String,
        host: String,
        gatewayCredential: String,
        subscription: com.tamimarafat.ferngeist.gateway.GatewayPushSubscription,
    ) = TODO("unused in these tests")

    override suspend fun fetchGitStatus(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String?,
        compare: String?,
    ) = TODO("unused in these tests")

    override suspend fun fetchGitDiff(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String?,
        acpSessionId: String?,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ) = TODO("unused in these tests")

    override suspend fun fetchGitLog(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        head: String?,
        skip: Int,
        limit: Int,
        acpSessionId: String?,
        compare: String?,
        beyond: Int,
    ): GatewayGitLog = TODO("unused in these tests")

    override suspend fun fetchGitRefs(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String?,
    ): GatewayGitRefs = TODO("unused in these tests")

    override suspend fun fetchGitCommit(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        sha: String,
        acpSessionId: String?,
    ): GatewayGitCommitFiles = TODO("unused in these tests")

    override suspend fun fetchFile(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        path: String,
        acpSessionId: String?,
    ): GatewayFileContents = TODO("unused in these tests")

    override suspend fun fetchFileList(
        scheme: String,
        host: String,
        gatewayCredential: String,
        runtimeId: String,
        acpSessionId: String?,
    ): GatewayFileList = TODO("unused in these tests")

    override suspend fun createCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        displayName: String,
        command: String,
        args: List<String>,
        hint: String,
    ): GatewayAgent = TODO("unused in these tests")

    override suspend fun deleteCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ) = TODO("unused in these tests")

    override suspend fun stopAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        agentId: String,
    ) = TODO("unused in these tests")
}

internal class FakeSessionRepo : SessionRepository {
    private val rows = linkedMapOf<String, SessionSummary>()

    /** Title writes, so read-only paths can assert they wrote nothing. */
    var titleWrites = 0
        private set

    /** Whole-table replacements, so read-only paths can assert they wrote nothing. */
    var replaceCount = 0
        private set

    private fun key(
        serverId: String,
        sessionId: String,
    ) = "$serverId/$sessionId"

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> =
        flowOf(rows.filterKeys { it.startsWith("$serverId/") }.values.toList())

    override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> =
        flowOf(rows.values.sortedByDescending { it.updatedAt }.take(limit))

    override suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary? = rows[key(serverId, sessionId)]

    override suspend fun upsertSession(
        serverId: String,
        summary: SessionSummary,
    ) {
        rows[key(serverId, summary.id)] = summary.copy(serverId = serverId)
    }

    override suspend fun updateSessionTitle(
        serverId: String,
        sessionId: String,
        title: String,
    ) {
        titleWrites++
        rows[key(serverId, sessionId)]?.let { rows[key(serverId, sessionId)] = it.copy(title = title) }
    }

    override suspend fun touchSession(
        serverId: String,
        sessionId: String,
        updatedAt: Long,
    ) {
        rows[key(serverId, sessionId)]?.let { rows[key(serverId, sessionId)] = it.copy(updatedAt = updatedAt) }
    }

    override suspend fun setGatewaySessionId(
        serverId: String,
        sessionId: String,
        gatewaySessionId: String?,
    ) {
        rows[key(serverId, sessionId)]?.let {
            rows[key(serverId, sessionId)] = it.copy(gatewaySessionId = gatewaySessionId)
        }
    }

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) {
        rows.remove(key(serverId, sessionId))
    }

    override suspend fun clearStaleGatewaySessions(
        serverId: String,
        liveGatewaySessionIds: Set<String>,
    ): Int {
        val stale =
            rows.keys.filter { key ->
                key.startsWith("$serverId/") &&
                    rows[key]?.gatewaySessionId.let { id -> id != null && id !in liveGatewaySessionIds }
            }
        stale.forEach { rows[it] = rows.getValue(it).copy(gatewaySessionId = null) }
        return stale.size
    }

    override suspend fun clearSessions(serverId: String) {
        rows.keys.removeAll { it.startsWith("$serverId/") }
    }

    override suspend fun replaceSessions(
        serverId: String,
        sessions: List<SessionSummary>,
    ) {
        replaceCount++
        clearSessions(serverId)
        sessions.forEach { upsertSession(serverId, it) }
    }
}

internal class FakeLaunchableTargets : LaunchableTargetRepository {
    /** The single target this fake serves, or null for "no such target". */
    var target: LaunchableTarget? = null

    override fun getTargets(): Flow<List<LaunchableTarget>> = flowOf(listOfNotNull(target))

    override suspend fun getTarget(id: String): LaunchableTarget? = target?.takeIf { it.id == id }

    override suspend fun updatePreferredAuthMethod(
        targetId: String,
        methodId: String,
    ) = Unit

    override suspend fun deleteTarget(id: String) = Unit
}

internal class FakeGatewaySources : GatewaySourceRepository {
    override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

    override suspend fun addGateway(gateway: GatewaySource) = Unit

    override suspend fun updateGateway(gateway: GatewaySource) = Unit

    override suspend fun deleteGateway(id: String) = Unit

    override suspend fun getGateway(id: String): GatewaySource? = null

    override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
}

internal class FakeAuthEnvValueStore : AuthEnvValueStore {
    private val stored = mutableMapOf<String, MutableMap<String, String>>()

    override suspend fun getValues(serverId: String): Map<String, String> = stored[serverId].orEmpty()

    override suspend fun deleteValues(serverId: String) {
        stored.remove(serverId)
    }

    override suspend fun updateValues(
        serverId: String,
        envVarNames: Set<String>,
        envValues: Map<String, String>,
    ) {
        val values = stored.getOrPut(serverId) { mutableMapOf() }
        envVarNames.forEach { name -> envValues[name]?.let { values[name] = it } }
    }
}
