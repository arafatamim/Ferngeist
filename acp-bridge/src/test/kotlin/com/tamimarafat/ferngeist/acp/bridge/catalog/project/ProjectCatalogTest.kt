package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepositoryImpl
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val gateway = GatewaySource(id = "gw", name = "PC", host = "pc:5788", gatewayCredential = "token")
    private val claude = agent("b-claude", "Claude", "claude")
    private val codex = agent("b-codex", "Codex", "codex")
    private val repo = "/h/Ferngeist"
    private val previous =
        ProjectSnapshot(
            agents = mapOf("claude" to listOf(chat("old-claude")), "codex" to listOf(chat("old-codex"))),
            checkouts = listOf(GatewayProjectRepo(repo = repo, branch = "main")),
        )

    @Test
    fun `stream cut before checkouts keeps received lines, failed agents' cache and old checkouts`() =
        runTest {
            store().write(gateway.id, previous)
            val body = ByteChannel(autoFlush = true)
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) })
            runCurrent()

            catalog.refresh()
            runCurrent()
            body.writeStringUtf8(agentLine("claude", "ok", "new-claude") + agentLine("codex", "authRequired", null))
            val expected = setOf("new-claude", "old-codex")
            catalog.state.first { it.chatIds() == expected }
            body.close(IOException("connection reset"))
            runCurrent()

            val restored = store().readAll().getValue(gateway.id)
            assertEquals(
                expected,
                restored.agents.values
                    .flatten()
                    .map { it.sessionId }
                    .toSet(),
            )
            assertEquals("main", restored.checkouts?.single()?.branch)
        }

    @Test
    fun `404 hides the gateway's projects and forgets its snapshot`() =
        runTest {
            store().write(gateway.id, previous)
            val catalog = catalog(MockEngine { respond("", HttpStatusCode.NotFound) })
            runCurrent()
            assertEquals(setOf("old-claude", "old-codex"), catalog.state.value.chatIds())

            catalog.refresh()

            catalog.state.first { it.projects.isEmpty() }
            assertTrue(store().readAll().isEmpty())
        }

    @Test
    fun `chats of agents not bound on this phone are dropped`() {
        val snapshot = previous.copy(agents = previous.agents + ("gemini" to listOf(chat("unbound"))))

        val projects = projectsOf(gateway, snapshot, listOf(claude))

        assertEquals(listOf("old-claude"), projects.single().chats.map { it.summary.id })
        assertEquals(
            "b-claude",
            projects
                .single()
                .chats
                .single()
                .summary.serverId,
        )
    }

    private fun TestScope.catalog(engine: MockEngine) =
        ProjectCatalog(
            gatewayProjects = GatewayProjects(HttpClient(engine), json),
            repositories =
                LaunchRepositories(
                    gatewayRepository = GatewayRepositoryImpl(HttpClient(engine), json),
                    gatewaySourceRepository = Gateways(listOf(gateway)),
                    launchableTargetRepository = Targets(listOf(claude, codex)),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ),
            store = store(),
            scope = backgroundScope,
        )

    private fun store() = ProjectSnapshotStore(folder.root, json)

    private fun ProjectCatalogState.chatIds() = projects.flatMap { it.chats }.map { it.summary.id }.toSet()

    private fun agent(
        id: String,
        name: String,
        agentId: String,
    ) = LaunchableTarget.GatewayAgent(
        GatewayAgentBinding(id = id, name = name, gatewaySourceId = gateway.id, agentId = agentId),
        gateway,
    )

    private fun chat(sessionId: String) =
        GatewayProjectChat(sessionId = sessionId, cwd = repo, repo = repo, updatedAt = "2026-10-24T10:00:00Z")

    private fun agentLine(
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
