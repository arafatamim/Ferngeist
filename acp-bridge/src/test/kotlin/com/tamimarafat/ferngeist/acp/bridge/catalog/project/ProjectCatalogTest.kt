package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.connection.LaunchRepositories
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.hub.registerChat
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepositoryImpl
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectChat
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectRepo
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjectWorktree
import com.tamimarafat.ferngeist.gateway.projects.GatewayProjects
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
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

    @Test
    fun `a created chat joins the worktree its cwd sits in until the snapshot lists it`() {
        val worktree = "$repo/.worktrees/email"
        val snapshot =
            previous.copy(
                checkouts =
                    listOf(
                        GatewayProjectRepo(repo, "main", listOf(GatewayProjectWorktree(id = "wt", path = worktree))),
                    ),
            )
        val created =
            mapOf(
                "b-codex" to
                    listOf(
                        SessionSummary(id = "new", cwd = worktree),
                        SessionSummary(id = "old-codex", cwd = repo),
                        SessionSummary(id = "elsewhere", cwd = "/h/Other"),
                    ),
            )

        val chats = projectsOf(gateway, snapshot, listOf(claude, codex), created).single().chats

        assertEquals(setOf("old-claude", "old-codex", "new"), chats.map { it.summary.id }.toSet())
        assertEquals(worktree, chats.single { it.summary.id == "new" }.worktree)
    }

    @Test
    fun `recents take gateway agents from the snapshot and direct agents from stored rows`() {
        val direct = LaunchableTarget.Manual(ServerConfig(id = "direct", name = "Local", scheme = "ws", host = "h"))
        val stored =
            listOf(
                SessionSummary(id = "stale-claude", updatedAt = 9_999_999_999_999, serverId = "b-claude"),
                SessionSummary(id = "local", updatedAt = 1, serverId = "direct"),
            )
        val created = mapOf("b-codex" to listOf(SessionSummary(id = "fresh", updatedAt = Long.MAX_VALUE)))

        val recents = recentChatsOf(listOf(claude, codex, direct), mapOf(gateway.id to previous), stored, created, 10)

        assertEquals(listOf("fresh", "old-claude", "old-codex", "local"), recents.map { it.id })
        assertEquals("b-codex", recents.first().serverId)
    }

    @Test
    fun `confirmed missing repos prune failed agent cache too`() {
        val next =
            previous.merge(
                com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
                    .Checkouts(missingRepos = listOf(repo)),
            )
        assertTrue(
            next.agents.values
                .flatten()
                .isEmpty(),
        )
        assertTrue(next.checkouts.orEmpty().isEmpty())
    }

    @Test
    fun `deletion survives stale listing and restart without hiding another agents same id`() =
        runTest {
            store().write(
                gateway.id,
                previous.copy(
                    agents =
                        mapOf(
                            "claude" to listOf(chat("same")),
                            "codex" to listOf(chat("same")),
                        ),
                ),
            )
            val catalog = catalog(MockEngine { respond(agentLine("claude", "ok", "same"), HttpStatusCode.OK) })
            runCurrent()
            catalog.deleteSession(claude.id, "same")
            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.getSessions(claude.id).first().isEmpty())
            assertEquals(listOf("same"), catalog.getSessions(codex.id).first().map { it.id })
            val restored = catalog(MockEngine { respond("", HttpStatusCode.OK) })
            runCurrent()
            assertTrue(restored.getSessions(claude.id).first().isEmpty())
            assertEquals(listOf("same"), restored.getSessions(codex.id).first().map { it.id })
        }

    @Test
    fun `direct listing hydrates after restart but a newly created chat does not`() =
        runTest {
            val direct = LaunchableTarget.Manual(ServerConfig(id = "direct", name = "Local", scheme = "ws", host = "h"))
            val engine = MockEngine { respond("", HttpStatusCode.OK) }
            val first = catalog(engine, listOf(direct))
            runCurrent()
            first.recordListing(direct.id, listOf(SessionSummary("listed", updatedAt = 1)))
            first.chatCreated(direct.id, SessionSummary("memory", updatedAt = 2))
            assertEquals(listOf("memory", "listed"), first.getSessions(direct.id).first().map { it.id })
            val restored = catalog(engine, listOf(direct))
            runCurrent()
            assertEquals(listOf("listed"), restored.getSessions(direct.id).first().map { it.id })
            assertEquals(listOf("listed"), restored.getRecentSessions(10).first().map { it.id })
        }

    @Test
    fun `successful omission retires suppression while failed listing retains it`() {
        val line =
            com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
                .Agent("claude", "ok")
        val hidden = previous.copy(suppressed = mapOf("claude" to setOf("old-claude")))
        assertEquals(setOf("old-claude"), hidden.merge(line.copy(status = "failed")).suppressed["claude"])
        assertTrue(
            hidden
                .merge(line)
                .suppressed["claude"]
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun `binding removal hides its memory and disk listings from every derived list`() =
        runTest {
            val engine = MockEngine { respond("", HttpStatusCode.OK) }
            val first = catalog(engine)
            runCurrent()
            first.recordListing(claude.id, listOf(SessionSummary("listed")))
            first.chatCreated(claude.id, SessionSummary("memory", cwd = repo))
            val removed = catalog(engine, emptyList())
            runCurrent()
            assertTrue(removed.getSessions(claude.id).first().isEmpty())
            assertTrue(removed.getRecentSessions(10).first().isEmpty())
            assertTrue(
                removed.state.value.projects
                    .flatMap { it.chats }
                    .isEmpty(),
            )
        }

    @Test
    fun `failed checkout discovery retains unconfirmed roots`() {
        val next =
            previous.merge(
                com.tamimarafat.ferngeist.gateway.projects.GatewayProjectLine
                    .Checkouts(),
            )
        assertEquals(previous.checkouts, next.checkouts)
    }

    @Test
    fun `oldest binding owns listed chats independent of name order with id tie break`() {
        val older = claude.copy(binding = claude.binding.copy(id = "a-old", name = "Zulu", createdAt = 1))
        val tied = claude.copy(binding = claude.binding.copy(id = "b-old", name = "Alpha", createdAt = 1))
        val newer = claude.copy(binding = claude.binding.copy(id = "new", name = "Aardvark", createdAt = 2))
        val projects = projectsOf(gateway, previous, listOf(newer, tied, older))
        assertEquals(
            older.id,
            projects
                .single()
                .chats
                .single()
                .target.id,
        )
    }

    @Test
    fun `gateway suppression covers stale fallback listings and duplicate bindings`() =
        runTest {
            store().write(gateway.id, previous)
            val duplicate = claude.copy(binding = claude.binding.copy(id = "duplicate"))
            val catalog =
                catalog(
                    MockEngine { respond(agentLine("claude", "ok", "old-claude"), HttpStatusCode.OK) },
                    listOf(claude, duplicate, codex),
                )
            runCurrent()
            catalog.recordListing(duplicate.id, listOf(SessionSummary("old-claude", cwd = repo)))
            catalog.deleteSession(claude.id, "old-claude")
            catalog.refresh(force = true).join()
            runCurrent()
            catalog.recordListing(duplicate.id, listOf(SessionSummary("old-claude", cwd = repo)))
            assertTrue(catalog.getSessions(claude.id).first().isEmpty())
            assertTrue(catalog.getSessions(duplicate.id).first().isEmpty())
            assertEquals(listOf("old-codex"), catalog.getRecentSessions(10).first().map { it.id })
            assertEquals(setOf("old-codex"), catalog.state.value.chatIds())
        }

    @Test
    fun `confirmed missing root removes retained fallback and memory chats across restart`() =
        runTest {
            store().write(gateway.id, previous)
            val body = """{"type":"checkouts","repos":[],"missingRepos":["$repo"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) })
            runCurrent()
            catalog.recordListing(claude.id, listOf(SessionSummary("old-claude", cwd = repo)))
            catalog.chatCreated(codex.id, SessionSummary("memory", cwd = repo))
            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.getRecentSessions(10).first().isEmpty())
            assertTrue(
                catalog.state.value.projects
                    .isEmpty(),
            )
            assertTrue(
                store()
                    .readAll()
                    .values
                    .flatMap { it.listings.values.flatten() }
                    .isEmpty(),
            )
            catalog.recordListing(claude.id, listOf(SessionSummary("old-claude", cwd = repo)))
            assertTrue(catalog.getSessions(claude.id).first().isEmpty())
            val restored = catalog(MockEngine { respond("", HttpStatusCode.OK) })
            runCurrent()
            assertTrue(restored.getRecentSessions(10).first().isEmpty())
            assertTrue(
                restored.state.value.projects
                    .isEmpty(),
            )
        }

    @Test
    fun `gateway running includes unlisted chats on unsuccessful agent lines`() =
        runTest {
            val body = """{"type":"agent","agentId":"claude","status":"unsupported","running":["unlisted"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) })
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/unlisted"), catalog.inProgressChatKeys.first())
        }

    @Test
    fun `hub idle overrides gateway running and untracking restores gateway answer`() =
        runTest {
            val hub = newHub()
            val body = """{"type":"agent","agentId":"claude","status":"ok","running":["same"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) }, hub = hub)
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())
            val key = hub.registerChat(serverId = claude.id, sessionId = "same")
            runCurrent()
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())
            hub.close(key)
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())
        }

    @Test
    fun `hydration never restores gateway running`() =
        runTest {
            val body = """{"type":"agent","agentId":"claude","status":"ok","running":["same"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) })
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())
            val restored = catalog(MockEngine { respond("", HttpStatusCode.OK) })
            runCurrent()
            assertTrue(restored.inProgressChatKeys.first().isEmpty())
        }

    @Test
    fun `older gateway omitting running clears previous running`() =
        runTest {
            var body = """{"type":"agent","agentId":"claude","status":"ok","running":["same"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) })
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())
            body = agentLine("claude", "ok", "same")
            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())
        }

    @Test
    fun `activity timestamps exclude tracked and running chats without inferring running state`() =
        runTest {
            store().write(gateway.id, previous)
            val hub = newHub()
            val body = """{"type":"agent","agentId":"claude","status":"unsupported","running":["old-claude"]}""" + "\n"
            val catalog = catalog(MockEngine { respond(body, HttpStatusCode.OK) }, hub = hub)
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-codex/old-codex"), catalog.activityUpdatedAt.value.keys)
            val key = hub.registerChat(serverId = codex.id, sessionId = "old-codex")
            runCurrent()
            assertTrue(catalog.activityUpdatedAt.value.isEmpty())
            assertEquals(setOf("b-claude/old-claude"), catalog.inProgressChatKeys.value)
            hub.close(key)
            runCurrent()
            assertEquals(setOf("b-codex/old-codex"), catalog.activityUpdatedAt.value.keys)
        }

    @Test
    fun `activity uses refreshed gateway timestamps and ignores missing or malformed values`() =
        runTest {
            var timestamp = "2026-10-25T00:00:00Z"
            val catalog =
                catalog(
                    MockEngine {
                        respond(
                            """{"type":"agent","agentId":"claude","status":"ok","chats":[
                    {"sessionId":"recent","cwd":"$repo","repo":"$repo","updatedAt":"$timestamp"},
                    {"sessionId":"missing","cwd":"$repo","repo":"$repo"},
                    {"sessionId":"invalid","cwd":"$repo","repo":"$repo","updatedAt":"bad"}
                ]}""".replace("\n", "") + "\n",
                            HttpStatusCode.OK,
                        )
                    },
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(mapOf("b-claude/recent" to 1_792_886_400_000L), catalog.activityUpdatedAt.value)
            assertTrue(catalog.inProgressChatKeys.value.isEmpty())
            timestamp = "2026-10-25T00:00:30Z"
            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(mapOf("b-claude/recent" to 1_792_886_430_000L), catalog.activityUpdatedAt.value)
        }

    @Test
    fun `local turn completion refreshes project timestamp within the fetch throttle`() =
        runTest {
            val hub = newHub()
            var timestamp = "2026-10-25T00:00:00Z"
            val catalog =
                catalog(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { respond(timestampLine(timestamp), HttpStatusCode.OK) }
                        },
                    ),
                    hub = hub,
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            val key = hub.registerChat(serverId = claude.id, sessionId = "same")
            hub.storeSnapshot(key, snapshot(streaming = true))
            runCurrent()
            assertEquals(setOf(key), catalog.inProgressChatKeys.value)
            timestamp = "2026-10-25T00:00:30Z"
            assertEquals(
                1_792_886_400_000L,
                catalog.state.value.projects
                    .single()
                    .updatedAt,
            )

            hub.storeSnapshot(key, snapshot(streaming = false))
            runCurrent()

            assertEquals(
                1_792_886_430_000L,
                catalog.state.value.projects
                    .single()
                    .updatedAt,
            )
        }

    @Test
    fun `completions sharing a gateway fetch fresh timestamps after an older fetch finishes`() =
        runTest {
            val hub = newHub()
            val body = ByteChannel(autoFlush = true)
            var requests = 0
            val catalog =
                catalog(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                requests++
                                if (requests == 1) {
                                    respond(body, HttpStatusCode.OK)
                                } else {
                                    respond(timestampLine("2026-10-25T00:00:30Z"), HttpStatusCode.OK)
                                }
                            }
                        },
                    ),
                    hub = hub,
                )
            runCurrent()
            val keys = listOf(claude, codex).map { hub.registerChat(serverId = it.id, sessionId = "same") }
            keys.forEach { hub.storeSnapshot(it, snapshot(streaming = true)) }
            runCurrent()
            assertEquals(keys.toSet(), catalog.inProgressChatKeys.value)
            catalog.refresh()
            runCurrent()
            body.writeStringUtf8(timestampLine("2026-10-25T00:00:00Z"))
            runCurrent()
            keys.forEach { hub.storeSnapshot(it, snapshot(streaming = false)) }
            runCurrent()
            assertEquals(
                1_792_886_400_000L,
                catalog.state.value.projects
                    .single()
                    .updatedAt,
            )

            body.close()
            runCurrent()

            assertEquals(
                1_792_886_430_000L,
                catalog.state.value.projects
                    .single()
                    .updatedAt,
            )
            assertEquals(2, requests)
        }

    private fun timestampLine(timestamp: String): String =
        """{"type":"agent","agentId":"claude","status":"ok","chats":[""" +
            """{"sessionId":"same","cwd":"$repo","repo":"$repo","updatedAt":"$timestamp"}]}""" + "\n"

    private fun snapshot(streaming: Boolean) =
        ChatSessionSnapshot(
            loadState = ChatLoadState.READY,
            messages = emptyList(),
            isStreaming = streaming,
            configOptions = emptyList(),
            availableCommands = emptyList(),
            commandsAdvertised = false,
            error = null,
            usage = null,
        )

    private fun TestScope.catalog(
        engine: MockEngine,
        targets: List<LaunchableTarget> = listOf(claude, codex),
        hub: ChatConnectionHub = newHub(),
    ) = ProjectCatalog(
        gatewayProjects = GatewayProjects(HttpClient(engine), json),
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
