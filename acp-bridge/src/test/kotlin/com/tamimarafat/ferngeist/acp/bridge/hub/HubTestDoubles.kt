package com.tamimarafat.ferngeist.acp.bridge.hub

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayAgent
import com.tamimarafat.ferngeist.gateway.GatewayConnectResponse
import com.tamimarafat.ferngeist.gateway.GatewayCreateWorktreeRequest
import com.tamimarafat.ferngeist.gateway.GatewayCustomAgentRequest
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
import com.tamimarafat.ferngeist.gateway.workspace.GatewayDiffQuery
import com.tamimarafat.ferngeist.gateway.workspace.GatewayLogQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Deterministic clock: each call advances one tick, so focus order is exact. */
internal fun tickingClock(): () -> Long {
    var ticks = 0L
    return { ++ticks }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun newHub(
    repo: GatewayRepository = FakeGatewayRepo(),
    maxHot: Int = 3,
    maxGateway: Int = 5,
    clock: () -> Long = tickingClock(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
): ChatConnectionHub =
    ChatConnectionHub(
        gatewayRepository = repo,
        scope = CoroutineScope(dispatcher),
        connectivityObserver = ConnectivityObserverStub(initialState = true),
        limits =
            HubLimits(
                maxHotConnections = maxHot,
                maxGatewaySessionsPerDevice = maxGateway,
                clock = clock,
            ),
        ioDispatcher = dispatcher,
    )

/** A connected chat registration; the hub is always handed a live transport probe. */
internal fun ChatConnectionHub.registerChat(
    serverId: String = "srv",
    sessionId: String,
    gatewaySessionId: String? = "gs-$sessionId",
    streaming: Boolean = false,
    manager: AcpConnection? = null,
): String =
    register(
        serverId = serverId,
        sessionId = sessionId,
        registration =
            ChatRegistration(
                gatewaySessionId = gatewaySessionId,
                gatewaySourceId = "src-1",
                agentId = "agent-1",
                isConnected = { true },
                isStreaming = { streaming },
                manager = manager,
            ),
    )

internal class FakeGatewayRepo : GatewayRepository {
    val closed = mutableListOf<String>()

    /** Completes on the first [closeSession], so a test can await a fire-and-forget DELETE. */
    private val firstClose = CompletableDeferred<Unit>()

    suspend fun awaitFirstClose() {
        firstClose.await()
    }

    var sessions: List<GatewaySessionSummary> = emptyList()
    var closeFailure: Exception? = null
    var listFailure: Exception? = null
    var resumeFailure: Exception? = null
    val resumedSessions = mutableListOf<String>()
    var protocolVersion: String? = GatewayProtocol.CURRENT
    var connectCalls = 0
    var connectFailure: Exception? = null

    override suspend fun createWorktree(
        scheme: String,
        host: String,
        gatewayCredential: String,
        request: GatewayCreateWorktreeRequest,
    ) = TODO("unused in these tests")

    override suspend fun listWorktrees(
        scheme: String,
        host: String,
        gatewayCredential: String,
        repo: String?,
    ) = TODO("unused in these tests")

    override suspend fun findMissingPaths(
        scheme: String,
        host: String,
        gatewayCredential: String,
        paths: List<String>,
    ): List<String> = TODO("unused in these tests")

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
        firstClose.complete(Unit)
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
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
        compare: String?,
    ) = TODO("unused in these tests")

    override suspend fun fetchGitDiff(
        connection: GatewayWorkspaceConnection,
        query: GatewayDiffQuery,
    ) = TODO("unused in these tests")

    override suspend fun fetchGitLog(
        connection: GatewayWorkspaceConnection,
        query: GatewayLogQuery,
    ): GatewayGitLog = TODO("unused in these tests")

    override suspend fun fetchGitRefs(
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
    ): GatewayGitRefs = TODO("unused in these tests")

    override suspend fun fetchGitCommit(
        connection: GatewayWorkspaceConnection,
        sha: String,
        acpSessionId: String?,
    ): GatewayGitCommitFiles = TODO("unused in these tests")

    override suspend fun fetchFile(
        connection: GatewayWorkspaceConnection,
        path: String,
        acpSessionId: String?,
    ): GatewayFileContents = TODO("unused in these tests")

    override suspend fun fetchFileList(
        connection: GatewayWorkspaceConnection,
        acpSessionId: String?,
    ): GatewayFileList = TODO("unused in these tests")

    override suspend fun createCustomAgent(
        scheme: String,
        host: String,
        gatewayCredential: String,
        request: GatewayCustomAgentRequest,
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

internal class FakeSessionRepo : ChatCatalog {
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

    override fun chatCreated(
        serverId: String,
        summary: SessionSummary,
    ) {
        rows[key(serverId, summary.id)] = summary.copy(serverId = serverId)
    }

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) {
        rows.remove(key(serverId, sessionId))
    }

    override suspend fun clearSessions(serverId: String) {
        rows.keys.removeAll { it.startsWith("$serverId/") }
    }

    override suspend fun recordListing(
        serverId: String,
        sessions: List<SessionSummary>,
    ) {
        replaceCount++
        clearSessions(serverId)
        sessions.forEach { chatCreated(serverId, it) }
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
