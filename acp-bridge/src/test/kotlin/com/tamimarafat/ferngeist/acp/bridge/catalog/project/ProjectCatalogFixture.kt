package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.gateway.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepositoryImpl
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurn
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnKey
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStreamFrame
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnsSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

private const val STARTED_AT = "2026-10-27T09:00:00Z"
private const val OBSERVED_AT = "2026-10-27T09:30:00Z"

@OptIn(ExperimentalCoroutinesApi::class)
open class ProjectCatalogFixture {
    @get:Rule
    val folder = TemporaryFolder()

    internal val json = Json { ignoreUnknownKeys = true }
    internal val gateway = GatewaySource(id = "gw", name = "PC", host = "pc:5788", gatewayCredential = "token")
    internal val claude = agent("b-claude", "Claude", "claude")
    internal val codex = agent("b-codex", "Codex", "codex")

    /** Discovery consent the catalog under test sees; a test overrides it before building one. */
    internal var discoveryConsent: Flow<Set<String>> =
        flowOf(setOf(claude.binding.launchConsentKey, codex.binding.launchConsentKey))
    internal val repo = "/h/Ferngeist"
    internal val previous =
        ProjectSnapshot(
            agents = mapOf("claude" to listOf(chat("old-claude")), "codex" to listOf(chat("old-codex"))),
            checkouts = listOf(GatewayProjectRepo(repo = repo, branch = "main")),
        )

    internal fun timestampLine(timestamp: String): String =
        """{"type":"agent","agentId":"claude","status":"ok","chats":[""" +
            """{"sessionId":"same","cwd":"$repo","repo":"$repo","updatedAt":"$timestamp"}]}""" + "\n"

    internal fun TestScope.catalog(
        engine: MockEngine,
        targets: List<LaunchableTarget> = listOf(claude, codex),
        hub: ChatConnectionHub = newHub(),
        stream: GatewayTurnStream = idleTurnStream(),
    ) = ProjectCatalog(
        gateway =
            ProjectCatalogGateway(
                GatewayProjects(HttpClient(engine), json),
                stream,
            ),
        repositories =
            LaunchRepositories(
                gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                gatewaySourceRepository = Gateways(listOf(gateway)),
                launchableTargetRepository = Targets(targets),
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
        store = store(),
        scope = backgroundScope,
        hub = hub,
        consent = discoveryConsent,
    )

    /** A stream the test drives frame by frame, through [turnStream]. */
    internal fun fakeStream(): Channel<GatewayTurnStreamFrame> = Channel(Channel.UNLIMITED)

    internal fun turnStream(frames: Channel<GatewayTurnStreamFrame>): GatewayTurnStream =
        mockk { every { frames(any(), any(), any()) } returns frames.receiveAsFlow() }

    internal fun idleTurnStream(): GatewayTurnStream =
        mockk {
            every { frames(any(), any(), any()) } returns Channel<GatewayTurnStreamFrame>().receiveAsFlow()
        }

    internal fun snapshotFrame(
        vararg turns: GatewayTurn,
        gatewayStartedAt: String = STARTED_AT,
        observedAt: String = OBSERVED_AT,
    ): GatewayTurnStreamFrame.Snapshot =
        GatewayTurnStreamFrame.Snapshot(GatewayTurnsSnapshot(gatewayStartedAt, observedAt, turns.toList()))

    internal fun changesFrame(
        upsert: GatewayTurn? = null,
        removed: List<GatewayTurnKey> = emptyList(),
    ): GatewayTurnStreamFrame.Changes = GatewayTurnStreamFrame.Changes(OBSERVED_AT, listOfNotNull(upsert), removed)

    internal fun gatewayTurn(
        agentId: String,
        sessionId: String,
        state: String,
        changedAt: String = OBSERVED_AT,
    ): GatewayTurn =
        GatewayTurn(
            agentId = agentId,
            sessionId = sessionId,
            gatewaySessionId = "gs-$sessionId",
            state = state,
            attention = emptyList(),
            startedAt = OBSERVED_AT,
            changedAt = changedAt,
        )

    internal fun store() = ProjectSnapshotStore(folder.root, json)

    internal fun ProjectCatalogState.chatIds() = projects.flatMap { it.chats }.map { it.summary.id }.toSet()

    internal fun agent(
        id: String,
        name: String,
        agentId: String,
    ) = LaunchableTarget.GatewayAgent(
        GatewayAgentBinding(id = id, name = name, gatewaySourceId = gateway.id, agentId = agentId),
        gateway,
    )

    internal fun chat(sessionId: String) =
        GatewayProjectChat(sessionId = sessionId, cwd = repo, repo = repo, updatedAt = "2026-10-24T10:00:00Z")

    internal fun agentLine(
        agentId: String,
        status: String,
        sessionId: String?,
    ): String {
        val chats = sessionId?.let { """{"sessionId":"$it","cwd":"$repo","repo":"$repo"}""" }.orEmpty()
        return """{"type":"agent","agentId":"$agentId","status":"$status","chats":[$chats]}""" + "\n"
    }

    private class Gateways(
        private val gateways: List<GatewaySource>,
    ) : GatewaySourceRepository {
        override fun getGateways(): Flow<List<GatewaySource>> = flowOf(gateways)

        override suspend fun addGateway(gateway: GatewaySource) = Unit

        override suspend fun updateGateway(gateway: GatewaySource) = Unit

        override suspend fun deleteGateway(id: String) = Unit

        override suspend fun getGateway(id: String) = gateways.firstOrNull { it.id == id }

        override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
    }

    private class Targets(
        private val targets: List<LaunchableTarget>,
    ) : LaunchableTargetRepository {
        override fun getTargets(): Flow<List<LaunchableTarget>> = flowOf(targets)

        override suspend fun getTarget(id: String) = getTargets().first().firstOrNull { it.id == id }

        override suspend fun updatePreferredAuthMethod(
            targetId: String,
            methodId: String,
        ) = Unit

        override suspend fun deleteTarget(id: String) = Unit
    }
}
