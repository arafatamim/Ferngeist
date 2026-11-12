package com.tamimarafat.ferngeist.acp.bridge.catalog.turns

import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.hub.registerChat
import com.tamimarafat.ferngeist.core.model.GatewayAgentBinding
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GATEWAY_STARTED = "2026-11-04T08:00:00Z"
private const val OBSERVED = "2026-11-04T09:00:00Z"

@OptIn(ExperimentalCoroutinesApi::class)
class TurnStatusStreamTest {
    private val gateway = GatewaySource(id = "gw", name = "PC", host = "pc:5788", gatewayCredential = "token")
    private val claude =
        LaunchableTarget.GatewayAgent(
            GatewayAgentBinding(id = "b-claude", name = "Claude", gatewaySourceId = gateway.id, agentId = "claude"),
            gateway,
        )
    private val hub = newHub()
    private val stream = mockk<GatewayTurnStream>()

    @Test
    fun `a turn that ended while the app was dead shows finished from the first snapshot`() =
        runTest {
            streamFrames {
                emit(snapshot(turn("ses_a", "succeeded")))
                awaitCancellation()
            }
            val catalog = catalog()
            streamGateways(catalog)
            assertEquals(
                TurnStatus.FINISHED,
                catalog.await("b-claude/ses_a", TurnStatus.FINISHED).status,
            )
        }

    @Test
    fun `changes replace and remove entries on top of the snapshot`() =
        runTest {
            val frames = Channel<GatewayTurnStreamFrame>(Channel.UNLIMITED)
            every { stream.frames(any(), any(), any()) } returns frames.receiveAsFlow()
            val catalog = catalog()
            streamGateways(catalog)

            frames.send(snapshot(turn("ses_a", "running"), turn("ses_b", "running")))
            catalog.await("b-claude/ses_a", TurnStatus.WORKING)

            frames.send(changes(upsert = turn("ses_a", "running", attention = listOf("permission"))))
            assertEquals(TurnStatus.NEEDS_ATTENTION, catalog.await("b-claude/ses_a", TurnStatus.NEEDS_ATTENTION).status)

            frames.send(changes(upsert = turn("ses_a", "succeeded"), removed = listOf(key("ses_b"))))
            assertEquals(TurnStatus.FINISHED, catalog.await("b-claude/ses_a", TurnStatus.FINISHED).status)
            assertNull(catalog.statuses.first { it["b-claude/ses_b"] == null }["b-claude/ses_b"])
        }

    @Test
    fun `a dropped stream withdraws the gateway's statuses until the next snapshot`() =
        runTest {
            val frames = Channel<GatewayTurnStreamFrame>(Channel.UNLIMITED)
            every { stream.frames(any(), any(), any()) } returns frames.receiveAsFlow()
            val catalog = catalog()
            streamGateways(catalog)

            frames.send(snapshot(turn("ses_a", "running")))
            catalog.await("b-claude/ses_a", TurnStatus.WORKING)
            frames.close()

            assertNull(catalog.statuses.first { it["b-claude/ses_a"] == null }["b-claude/ses_a"])
        }

    @Test
    fun `a withdrawn stream reports no turn end`() =
        runTest {
            val frames = Channel<GatewayTurnStreamFrame>(Channel.UNLIMITED)
            every { stream.frames(any(), any(), any()) } returns frames.receiveAsFlow()
            hub.registerChat(serverId = "b-claude", sessionId = "ses_a")
            val catalog = catalog()
            val ended = mutableListOf<Set<String>>()
            backgroundScope.launch { catalog.turnEnded.collect { ended += it } }
            streamGateways(catalog)

            frames.send(snapshot(turn("ses_a", "running")))
            catalog.await("b-claude/ses_a", TurnStatus.WORKING)
            frames.close()
            catalog.statuses.first { it["b-claude/ses_a"] == null }
            advanceUntilIdle()

            assertTrue(ended.isEmpty())
        }

    @Test
    fun `a stream the gateway ends normally reconnects with backoff`() =
        runTest {
            var attempts = 0
            streamFrames {
                attempts++
                emit(snapshot(turn("ses_a", "running")))
                if (attempts > 1) awaitCancellation()
            }
            val catalog = catalog()
            streamGateways(catalog)
            runCurrent()

            assertEquals(1, attempts)
            assertNull(catalog.statuses.value["b-claude/ses_a"])

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(TurnStatus.WORKING, catalog.statuses.value["b-claude/ses_a"]?.status)
        }

    @Test
    fun `a gateway that predates the stream stops and contributes no status`() =
        runTest {
            var attempts = 0
            streamFrames {
                attempts++
                throw GatewayRequestException(404, "not found")
            }
            val catalog = catalog()
            streamGateways(catalog)
            advanceUntilIdle()

            assertTrue(catalog.statuses.first().isEmpty())

            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, attempts)
        }

    @Test
    fun `a rejected credential stops the stream until the gateways flow re-emits`() =
        runTest {
            var attempts = 0
            every { stream.frames(any(), any(), any()) } returns
                flow {
                    attempts++
                    throw GatewayTurnStreamClosedException(4401, "credential_expired")
                }
            val catalog = catalog()
            val gateways = gateways()
            backgroundScope.launch { catalog.stream(gateways) }
            gateways.emit(listOf(gateway))
            runCurrent()
            assertEquals(1, attempts)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, attempts)

            gateways.emit(listOf(gateway.copy(gatewayCredential = "rotated")))
            runCurrent()
            assertEquals(2, attempts)
        }

    @Test
    fun `an invalid saved credential stops retrying`() =
        runTest {
            var attempts = 0
            streamFrames {
                attempts++
                throw GatewayCredentialInvalidException()
            }
            val catalog = catalog()
            backgroundScope.launch { catalog.stream(flowOf(listOf(gateway))) }
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, attempts)
        }

    private suspend fun TestScope.streamGateways(catalog: TurnStatusCatalog) {
        val gateways = gateways()
        backgroundScope.launch { catalog.stream(gateways) }
        gateways.emit(listOf(gateway))
    }

    private fun TestScope.catalog() = TurnStatusCatalog(stream, flowOf(listOf(claude)), backgroundScope, hub)

    private fun gateways() = MutableSharedFlow<List<GatewaySource>>(replay = 1)

    private fun streamFrames(block: suspend FlowCollector<GatewayTurnStreamFrame>.() -> Unit) {
        every { stream.frames(any(), any(), any()) } returns flow(block)
    }

    private suspend fun TurnStatusCatalog.await(
        key: String,
        status: TurnStatus,
    ): TurnActivity = statuses.first { it[key]?.status == status }.getValue(key)

    private fun snapshot(vararg turns: GatewayTurn) =
        GatewayTurnStreamFrame.Snapshot(GatewayTurnsSnapshot(GATEWAY_STARTED, OBSERVED, turns.toList()))

    private fun changes(
        upsert: GatewayTurn? = null,
        removed: List<GatewayTurnKey> = emptyList(),
    ) = GatewayTurnStreamFrame.Changes(
        observedAt = OBSERVED,
        upserts = listOfNotNull(upsert),
        removed = removed,
    )

    private fun turn(
        sessionId: String,
        state: String,
        attention: List<String> = emptyList(),
    ) = GatewayTurn(
        agentId = "claude",
        sessionId = sessionId,
        gatewaySessionId = "gs-$sessionId",
        state = state,
        attention = attention,
        startedAt = OBSERVED,
        changedAt = OBSERVED,
    )

    private fun key(sessionId: String) = GatewayTurnKey("claude", "gs-$sessionId", sessionId)
}
