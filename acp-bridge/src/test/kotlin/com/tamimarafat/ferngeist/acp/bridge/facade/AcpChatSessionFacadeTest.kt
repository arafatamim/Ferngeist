package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.ConnectivityObserverStub
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnection
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpManagerEvent
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionSurface
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
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
import com.tamimarafat.ferngeist.gateway.GatewayRequestException
import com.tamimarafat.ferngeist.gateway.GatewaySessionSummary
import com.tamimarafat.ferngeist.gateway.GatewayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Facade behavior against a recording [ChatConnectionSurface] and a fake gateway.
 *
 * Only the transport-free paths are drivable here: everything past
 * registerConnectingWithHub sits behind a connected [AcpConnection],
 * which is a concrete final class that only reaches Connected through a real
 * gateway handshake — so registration timing, sentinel skipping, and snapshot
 * re-keying stay covered at the ChatViewModel level (fake facade) and hub
 * level (ChatConnectionHubTest), and the seam exists to make the facade's own
 * wiring testable once a manager seam lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcpChatSessionFacadeTest {
    private class RecordingSurface : ChatConnectionSurface {
        val abandoned = mutableListOf<AcpConnection>()

        override fun acquireChatManager(): AcpConnection = TODO("unused in facade attendance tests")

        override fun abandon(manager: AcpConnection) {
            abandoned += manager
        }

        override fun managerFor(chatId: String): AcpConnection? = null

        override fun snapshotFor(chatId: String): ChatSessionSnapshot? = null

        override fun storeSnapshot(
            chatId: String,
            snapshot: ChatSessionSnapshot,
        ) = Unit

        override suspend fun register(
            serverId: String,
            sessionId: String,
            gatewaySessionId: String?,
            gatewaySourceId: String,
            agentId: String,
            isConnected: () -> Boolean,
            isStreaming: () -> Boolean,
            manager: AcpConnection?,
        ): String = TODO("unused in facade attendance tests")

        override fun refresh() = Unit

        override suspend fun resolveSessionTitle(
            serverId: String,
            sessionId: String,
            cwd: String?,
        ): String? = null
    }

    private class FakeLaunchableTargets : LaunchableTargetRepository {
        var target: LaunchableTarget? = null

        override fun getTargets(): Flow<List<LaunchableTarget>> = flowOf(listOfNotNull(target))

        override suspend fun getTarget(id: String): LaunchableTarget? = target

        override suspend fun updatePreferredAuthMethod(
            targetId: String,
            methodId: String,
        ) = Unit

        override suspend fun deleteTarget(id: String) = Unit
    }

    private class FakeGatewaySources : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(emptyList())

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String): GatewaySource? = null

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private class FakeGatewayRepo : GatewayRepository {
        /** Connect failures, so a test can drive the facade's error surfacing. */
        var connectFailure: Exception? = null
        var connectCalls = 0

        override suspend fun fetchStatus(
            scheme: String,
            host: String,
        ): GatewayStatus =
            GatewayStatus(
                name = "gw",
                version = "1.0",
                protocolVersion = GatewayProtocol.CURRENT,
                remote = GatewayRemoteStatus(configured = true),
            )

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

        override suspend fun createWorktree(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String,
            base: String?,
            branch: String?,
        ) = TODO("unused in facade tests")

        override suspend fun listWorktrees(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String?,
        ) = TODO("unused in facade tests")

        override suspend fun findMissingPaths(
            scheme: String,
            host: String,
            gatewayCredential: String,
            paths: List<String>,
        ): List<String> = TODO("unused in facade tests")

        override suspend fun fetchRepoRefs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            repo: String,
        ) = TODO("unused in facade tests")

        override suspend fun deleteWorktree(
            scheme: String,
            host: String,
            gatewayCredential: String,
            worktreeId: String,
            force: Boolean,
        ) = TODO("unused in facade tests")

        override suspend fun startPairing(
            scheme: String,
            host: String,
        ) = TODO("unused in facade tests")

        override suspend fun completePairing(
            scheme: String,
            host: String,
            challengeId: String,
            code: String,
            deviceName: String,
        ) = TODO("unused in facade tests")

        override suspend fun refreshCredential(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in facade tests")

        override suspend fun fetchAgents(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ) = TODO("unused in facade tests")

        override suspend fun restartRuntime(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            envVars: Map<String, String>,
        ) = TODO("unused in facade tests")

        override suspend fun fetchRuntimeLogs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
        ) = TODO("unused in facade tests")

        override suspend fun resumeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = TODO("unused in facade tests")

        override suspend fun listGatewaySessions(
            scheme: String,
            host: String,
            gatewayCredential: String,
        ): List<GatewaySessionSummary> = emptyList()

        override suspend fun closeSession(
            scheme: String,
            host: String,
            gatewayCredential: String,
            sessionId: String,
        ) = Unit

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
        ) = Unit

        override suspend fun fetchGitStatus(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
            compare: String?,
        ) = TODO("unused in facade tests")

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
        ) = TODO("unused in facade tests")

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
        ): GatewayGitLog = TODO("unused in facade tests")

        override suspend fun fetchGitRefs(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
        ): GatewayGitRefs = TODO("unused in facade attendance tests")

        override suspend fun fetchGitCommit(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            sha: String,
            acpSessionId: String?,
        ): GatewayGitCommitFiles = TODO("unused in facade tests")

        override suspend fun fetchFile(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            path: String,
            acpSessionId: String?,
        ): GatewayFileContents = TODO("unused in facade tests")

        override suspend fun fetchFileList(
            scheme: String,
            host: String,
            gatewayCredential: String,
            runtimeId: String,
            acpSessionId: String?,
        ): GatewayFileList = TODO("unused in facade tests")

        override suspend fun createCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            displayName: String,
            command: String,
            args: List<String>,
            hint: String,
        ): GatewayAgent = TODO("unused in facade tests")

        override suspend fun deleteCustomAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO("unused in facade tests")

        override suspend fun stopAgent(
            scheme: String,
            host: String,
            gatewayCredential: String,
            agentId: String,
        ) = TODO("unused in facade tests")
    }

    private fun newManager(scope: CoroutineScope): AcpConnection =
        AcpConnection(
            connectivityObserver = ConnectivityObserverStub(initialState = true),
            resumeAttachToken = { _, _ -> error("no gateway resume in this test") },
            scope = scope,
        )

    private fun gatewayTarget(): LaunchableTarget.GatewayAgent =
        LaunchableTarget.GatewayAgent(
            binding = GatewayAgentBinding(id = "srv", name = "srv", gatewaySourceId = "src", agentId = "agent-1"),
            gatewaySource =
                GatewaySource(
                    id = "src",
                    name = "gw",
                    scheme = "http",
                    host = "gw",
                    gatewayCredential = "cred",
                ),
        )

    private fun newFacade(
        surface: RecordingSurface,
        scope: CoroutineScope,
        manager: AcpConnection = newManager(scope),
        targets: LaunchableTargetRepository = FakeLaunchableTargets(),
        repo: GatewayRepository = FakeGatewayRepo(),
    ): AcpChatSessionFacade =
        AcpChatSessionFacade(
            scope = scope,
            connectionManager = manager,
            launchableTargetRepository = targets,
            gatewaySourceRepository = FakeGatewaySources(),
            gatewayRepository = repo,
            serverId = "srv",
            initialSessionId = "s1",
            cwd = "/work",
            hub = surface,
        )

    @Test
    fun `clear on an unregistered facade abandons its pending chat manager`() =
        runTest {
            val surface = RecordingSurface()
            val facade = newFacade(surface, scope = backgroundScope)

            facade.clear()

            // The screen closed before the transport ever registered (failed
            // spawn or never-connected load), so the hub-owned pending manager
            // must be released — otherwise it leaks in the hub's pending list
            // and keeps the process-wide aggregates alive.
            assertEquals(1, surface.abandoned.size)
        }

    @Test
    fun `a gateway connect failure surfaces the gateway's own error text`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeGatewayRepo().apply {
                    connectFailure = GatewayRequestException(502, "agent exited: boom", null)
                }
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val facade = newFacade(RecordingSurface(), backgroundScope, targets = targets, repo = repo)
            val failures = mutableListOf<String>()
            val collector = backgroundScope.launch { facade.loadFailed.collect { failures += it } }
            advanceUntilIdle()

            var loaded = false
            backgroundScope.launch {
                facade.loadSession()
                loaded = true
            }
            awaitRealWork { loaded }

            // The gateway's 502 body is the only thing that explains the failure;
            // a locally authored "Failed to reconnect to srv" hides it.
            assertEquals("agent exited: boom", failures.first())
            assertFalse(failures.any { it.contains("Failed to reconnect") })
            collector.cancel()
        }

    @Test
    fun `a rejected resume starts a fresh gateway connect`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeGatewayRepo().apply {
                    connectFailure = GatewayRequestException(502, "agent exited: boom", null)
                }
            val targets = FakeLaunchableTargets().apply { target = gatewayTarget() }
            val manager = newManager(backgroundScope)
            val facade =
                newFacade(
                    RecordingSurface(),
                    backgroundScope,
                    manager = manager,
                    targets = targets,
                    repo = repo,
                )
            val failures = mutableListOf<String>()
            val collector = backgroundScope.launch { facade.loadFailed.collect { failures += it } }
            advanceUntilIdle()

            manager.emitEvent(AcpManagerEvent.SessionResumeRejected)
            awaitRealWork { failures.isNotEmpty() }

            // The gateway session is gone: the only path is POST /v1/agents/{id}/connect.
            assertEquals(1, repo.connectCalls)
            assertEquals("agent exited: boom", failures.first())
            collector.cancel()
        }
}

/**
 * Lets the real-dispatcher work a facade path started (the gateway call hops to
 * [kotlinx.coroutines.Dispatchers.IO]) finish, then pumps the test scheduler so the
 * collectors observing it run. Virtual time alone would return before that work lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.awaitRealWork(done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + REAL_WORK_TIMEOUT_MS
    while (!done() && System.currentTimeMillis() < deadline) {
        Thread.sleep(REAL_WORK_POLL_MS)
        advanceUntilIdle()
    }
}

private const val REAL_WORK_TIMEOUT_MS = 10_000L
private const val REAL_WORK_POLL_MS = 2L

/**
 * Drives the connection's event stream the way the transport does when the gateway
 * rejects a resume. Unit tests cannot run a real ACP server, so this emits on the same
 * private flow the transport uses — observers cannot tell the difference.
 */
private fun AcpConnection.emitEvent(event: AcpManagerEvent) {
    val eventsField = AcpConnection::class.java.getDeclaredField("_events")
    eventsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val events = eventsField.get(this) as MutableSharedFlow<AcpManagerEvent>
    events.tryEmit(event)
}
