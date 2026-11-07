package com.tamimarafat.ferngeist.acp.bridge.catalog.turns

import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalog
import com.tamimarafat.ferngeist.acp.bridge.catalog.project.ProjectCatalogFixture
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.hub.registerChat
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStream
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStreamFrame
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTurnStatusTest : ProjectCatalogFixture() {
    @Test
    fun `opening a chat acknowledges its finished turn but not a later one`() =
        runTest {
            val hub = newHub()
            val (catalog, frames) = streaming(hub = hub)
            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "succeeded"),
                    gatewayTurn("claude", "other", "succeeded"),
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            assertTrue(catalog.turnStatuses.value["b-claude/same"]?.status != TurnStatus.FINISHED)
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/other"]?.status)
            hub.chatScreenClosed(claude.id, "same")
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "succeeded", changedAt = "2026-10-27T09:45:00Z"),
                    observedAt = "2026-10-27T09:45:00Z",
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `opening a chat acknowledges its failed turn but not a later one`() =
        runTest {
            val hub = newHub()
            val (catalog, frames) = streaming(hub = hub)
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "failed")))
            runCurrent()
            assertEquals(TurnStatus.ERROR, catalog.turnStatuses.value["b-claude/same"]?.status)

            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            assertTrue(catalog.turnStatuses.value["b-claude/same"]?.status != TurnStatus.ERROR)
            hub.chatScreenClosed(claude.id, "same")
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "failed")))
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "failed", changedAt = "2026-10-27T09:45:00Z"),
                    observedAt = "2026-10-27T09:45:00Z",
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.ERROR, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `a finish streamed while its chat is open stays seen after closing`() =
        runTest {
            val hub = newHub()
            val (catalog, frames) = streaming(hub = hub)
            hub.chatScreenOpened(claude.id, "same", "")
            hub.chatScreenOpened(codex.id, "another pane", "")
            runCurrent()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            hub.chatScreenClosed(claude.id, "same")
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `acknowledgement survives a dropped socket`() =
        runTest {
            var connections = 0
            val drop = Channel<Unit>(Channel.CONFLATED)
            val emitted =
                flow {
                    emit(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
                    if (connections++ == 0) {
                        drop.receive()
                        throw IOException("dropped")
                    }
                    awaitCancellation()
                }
            val hub = newHub()
            val catalog = catalog(MockEngine { respond("", HttpStatusCode.OK) }, hub = hub, stream = streamOf(emitted))
            hub.chatScreenOpened(claude.id, "same", "")
            backgroundScope.launch { catalog.streamTurns() }
            runCurrent()
            assertEquals(
                TurnStatus.UNKNOWN,
                catalog.turnStatuses.first { it.isNotEmpty() }["b-claude/same"]?.status,
            )

            hub.chatScreenClosed(claude.id, "same")
            drop.send(Unit)
            advanceUntilIdle()
            assertEquals(
                TurnStatus.UNKNOWN,
                catalog.turnStatuses.first { it.isNotEmpty() }["b-claude/same"]?.status,
            )
        }

    @Test
    fun `a gateway restart forgets acknowledgement`() =
        runTest {
            val hub = newHub()
            val (catalog, frames) = streaming(hub = hub)
            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            hub.chatScreenClosed(claude.id, "same")
            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "succeeded"),
                    gatewayStartedAt = "2026-10-27T09:15:00Z",
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `outcomes expire at their respective deadlines without another snapshot`() =
        runTest {
            val (catalog, frames) = streaming()
            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "succeeded"),
                    gatewayTurn("claude", "working", "running"),
                    gatewayTurn("claude", "failed", "failed"),
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            advanceTimeBy(299_999)
            runCurrent()
            assertEquals(TurnStatus.ERROR, catalog.turnStatuses.value["b-claude/failed"]?.status)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/failed"]?.status)
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
            advanceTimeBy(299_999)
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.value["b-claude/working"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/failed"]?.status)
        }

    @Test
    fun `stale or unreadable success after streaming cannot claim finish`() =
        runTest {
            val (catalog, frames) = streaming()
            val observed = "2026-10-27T10:00:00Z"
            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "stale", "succeeded", changedAt = "2026-10-27T09:49:59Z"),
                    gatewayTurn("claude", "malformed", "succeeded", changedAt = "not-a-time"),
                    gatewayTurn("claude", "future", "succeeded", changedAt = "2026-10-27T10:02:00Z"),
                    gatewayTurn("claude", "running", "running", changedAt = "not-a-time"),
                    gatewayTurn("claude", "failed", "failed", changedAt = "not-a-time"),
                    observedAt = observed,
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/stale"]?.status)

            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "stale", "succeeded", changedAt = "2026-10-27T09:49:59Z"),
                    gatewayTurn("claude", "malformed", "succeeded", changedAt = "not-a-time"),
                    gatewayTurn("claude", "future", "succeeded", changedAt = "2026-10-27T10:02:00Z"),
                    gatewayTurn("claude", "running", "running", changedAt = "not-a-time"),
                    gatewayTurn("claude", "failed", "failed", changedAt = "not-a-time"),
                    observedAt = "2026-10-27T10:01:00Z",
                ),
            )
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/stale"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/malformed"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/future"]?.status)
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.value["b-claude/running"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/failed"]?.status)
        }

    @Test
    fun `an authoritative snapshot removing the session forgets its acknowledgement`() =
        runTest {
            val hub = newHub()
            val (catalog, frames) = streaming(hub = hub)
            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            hub.chatScreenClosed(claude.id, "same")
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            frames.send(snapshotFrame())
            runCurrent()
            assertTrue(catalog.turnStatuses.value.isEmpty())

            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `a running turn marks a chat the listing never reported`() =
        runTest {
            val projects = """{"type":"agent","agentId":"claude","status":"unsupported"}""" + "\n"
            val (catalog, frames) = streaming(MockEngine { respond(projects, HttpStatusCode.OK) })
            catalog.refresh().join()
            frames.send(snapshotFrame(gatewayTurn("claude", "unlisted", "running")))
            runCurrent()
            assertEquals(setOf("b-claude/unlisted"), catalog.inProgressChatKeys.first { it.isNotEmpty() })
            assertEquals(
                TurnStatus.WORKING,
                catalog.turnStatuses.first { it.isNotEmpty() }["b-claude/unlisted"]?.status,
            )
        }

    @Test
    fun `status reaches only the agents bound on this phone, under their target ids`() =
        runTest {
            val (catalog, frames) = streaming()
            frames.send(
                snapshotFrame(
                    gatewayTurn("claude", "same", "running"),
                    gatewayTurn("gemini", "other", "running"),
                ),
            )
            runCurrent()
            assertEquals(
                mapOf("b-claude/same" to TurnStatus.WORKING),
                catalog.turnStatuses.first { it.isNotEmpty() }.mapValues { it.value.status },
            )
        }

    @Test
    fun `a cold start knows no status until a snapshot arrives`() =
        runTest {
            val (catalog, frames) = streaming()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "running")))
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first { it.isNotEmpty() })

            val restored = catalog(MockEngine { respond("", HttpStatusCode.OK) })
            runCurrent()
            assertTrue(restored.turnStatuses.first().isEmpty())
            assertTrue(restored.inProgressChatKeys.first().isEmpty())
        }

    @Test
    fun `a later snapshot drops a status the gateway no longer reports`() =
        runTest {
            val (catalog, frames) = streaming()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "running")))
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first { it.isNotEmpty() })

            frames.send(snapshotFrame(gatewayTurn("claude", "same", "succeeded")))
            runCurrent()
            assertEquals(
                mapOf("b-claude/same" to TurnStatus.FINISHED),
                catalog.turnStatuses.first { it.isNotEmpty() }.mapValues { it.value.status },
            )
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())

            frames.send(snapshotFrame())
            runCurrent()
            assertTrue(catalog.turnStatuses.first { it.isEmpty() }.isEmpty())
        }

    @Test
    fun `a dropped stream clears the statuses and leaves the listing alone`() =
        runTest {
            val projects = agentLine("claude", "ok", "same")
            val (catalog, frames) = streaming(MockEngine { respond(projects, HttpStatusCode.OK) })
            catalog.refresh().join()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "running")))
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first { it.isNotEmpty() })

            frames.close()
            runCurrent()
            assertTrue(catalog.turnStatuses.first { it.isEmpty() }.isEmpty())
            assertEquals(setOf("same"), catalog.state.first().chatIds())
        }

    @Test
    fun `a slow listing does not hold up the stream`() =
        runTest {
            val listing = ByteChannel(autoFlush = true)
            val (catalog, frames) = streaming(MockEngine { respond(listing, HttpStatusCode.OK) })
            val refreshing = catalog.refresh()
            frames.send(snapshotFrame(gatewayTurn("claude", "same", "running")))
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first { it.isNotEmpty() })
            listing.close()
            refreshing.join()
        }

    @Test
    fun `activity timestamps exclude tracked and working chats without inferring running state`() =
        runTest {
            store().write(gateway.id, previous)
            val hub = newHub()
            val engine = MockEngine { respond(agentLine("claude", "unsupported", null), HttpStatusCode.OK) }
            val (catalog, frames) = streaming(engine, hub = hub)
            catalog.refresh().join()
            frames.send(snapshotFrame(gatewayTurn("claude", "old-claude", "running")))
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

    /** A catalog whose turn status comes from [frames], already streaming under discovery consent. */
    private fun TestScope.streaming(
        engine: MockEngine = MockEngine { respond("", HttpStatusCode.OK) },
        hub: ChatConnectionHub = newHub(),
    ): Pair<ProjectCatalog, Channel<GatewayTurnStreamFrame>> {
        val frames = fakeStream()
        val catalog = catalog(engine, hub = hub, stream = turnStream(frames))
        backgroundScope.launch { catalog.streamTurns() }
        runCurrent()
        return catalog to frames
    }

    private fun streamOf(emitted: Flow<GatewayTurnStreamFrame>): GatewayTurnStream =
        mockk { every { frames(any(), any(), any()) } returns emitted }
}
