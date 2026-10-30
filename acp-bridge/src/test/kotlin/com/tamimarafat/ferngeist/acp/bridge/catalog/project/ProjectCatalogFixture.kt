package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatRegistration
import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepositoryImpl
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurns
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

private const val EMPTY_TURNS =
    """{"gatewayStartedAt":"2026-10-27T09:00:00Z",""" +
        """"observedAt":"2026-10-27T10:00:00Z","turns":[]}"""

@OptIn(ExperimentalCoroutinesApi::class)
open class ProjectCatalogFixture {
    @get:Rule
    val folder = TemporaryFolder()

    internal val json = Json { ignoreUnknownKeys = true }
    internal val gateway = GatewaySource(id = "gw", name = "PC", host = "pc:5788", gatewayCredential = "token")
    internal val claude = agent("b-claude", "Claude", "claude")
    internal val codex = agent("b-codex", "Codex", "codex")
    internal val repo = "/h/Ferngeist"
    internal val previous =
        ProjectSnapshot(
            agents = mapOf("claude" to listOf(chat("old-claude")), "codex" to listOf(chat("old-codex"))),
            checkouts = listOf(GatewayProjectRepo(repo = repo, branch = "main")),
        )

    internal fun timestampLine(timestamp: String): String =
        """{"type":"agent","agentId":"claude","status":"ok","chats":[""" +
            """{"sessionId":"same","cwd":"$repo","repo":"$repo","updatedAt":"$timestamp"}]}""" + "\n"

    /** A hub chat whose live snapshot this test drives: status and streaming change together. */
    internal fun ChatConnectionHub.registerLiveChat(
        serverId: String,
        sessionId: String,
        status: TurnStatus = TurnStatus.WORKING,
    ): Pair<String, MutableStateFlow<SessionSnapshot>> {
        val live =
            MutableStateFlow(
                SessionSnapshot(
                    sessionId = sessionId,
                    isStreaming = status == TurnStatus.WORKING,
                    turnStatus = status,
                ),
            )
        val chatId =
            register(
                serverId = serverId,
                sessionId = sessionId,
                registration =
                    ChatRegistration(
                        gatewaySessionId = null,
                        gatewaySourceId = "gw",
                        agentId = "claude",
                        isConnected = { true },
                        isStreaming = { live.value.isStreaming },
                        snapshot = live,
                    ),
            )
        return chatId to live
    }

    internal fun TestScope.catalog(
        engine: MockEngine,
        targets: List<LaunchableTarget> = listOf(claude, codex),
        hub: ChatConnectionHub = newHub(),
        turns: MockEngine = turnsEngine(EMPTY_TURNS),
        consent: Flow<Boolean> = flowOf(true),
    ) = ProjectCatalog(
        gateway =
            ProjectCatalogGateway(
                GatewayProjects(HttpClient(engine), json),
                GatewayTurns(HttpClient(turns), json),
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
        consent = consent,
    )

    /** Answers `/v1/turns` with [bodies] in order, repeating the last, all with [status]. */
    internal fun turnsEngine(
        vararg bodies: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockEngine {
        var served = 0
        return MockEngine { respond(bodies.getOrElse(served++) { bodies.last() }, status) }
    }

    internal fun turns(
        vararg turns: String,
        gatewayStartedAt: String = "2026-10-27T09:00:00Z",
        observedAt: String = "2026-10-27T09:30:00Z",
    ): String =
        """{"gatewayStartedAt":"$gatewayStartedAt","observedAt":"$observedAt",""" +
            """"turns":[${turns.joinToString(",")}]}"""

    internal fun turnLine(
        agentId: String,
        sessionId: String,
        state: String,
        attention: List<String> = emptyList(),
        changedAt: String = "2026-10-27T09:30:00Z",
    ): String {
        val attentionJson = attention.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"agentId":"$agentId","sessionId":"$sessionId","gatewaySessionId":"gs",""" +
            """"runtimeId":"rt","state":"$state","attention":$attentionJson,""" +
            """"startedAt":"2026-10-27T09:30:00Z","changedAt":"$changedAt"}"""
    }

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
