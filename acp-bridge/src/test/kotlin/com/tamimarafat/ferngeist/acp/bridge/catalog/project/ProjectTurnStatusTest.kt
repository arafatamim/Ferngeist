package com.tamimarafat.ferngeist.acp.bridge.catalog.project

import com.tamimarafat.ferngeist.acp.bridge.hub.newHub
import com.tamimarafat.ferngeist.acp.bridge.hub.registerChat
import com.tamimarafat.ferngeist.acp.bridge.session.SessionSnapshot
import com.tamimarafat.ferngeist.core.model.TurnStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTurnStatusTest : ProjectCatalogFixture() {
    @Test
    fun `opening a chat acknowledges its finished turn but not a later one`() =
        runTest {
            val hub = newHub()
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    hub = hub,
                    turns =
                        turnsEngine(
                            turns(
                                turnLine("claude", "same", "succeeded"),
                                turnLine("claude", "other", "succeeded"),
                            ),
                            turns(turnLine("claude", "same", "succeeded")),
                            turns(
                                turnLine("claude", "same", "succeeded", changedAt = "2026-10-27T09:45:00Z"),
                                observedAt = "2026-10-27T09:45:00Z",
                            ),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            assertTrue(catalog.turnStatuses.value["b-claude/same"]?.status != TurnStatus.FINISHED)
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/other"]?.status)
            hub.chatScreenClosed(claude.id, "same")
            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `a finish fetched while its chat is open stays seen after closing`() =
        runTest {
            val hub = newHub()
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    hub = hub,
                    turns = turnsEngine(turns(turnLine("claude", "same", "succeeded"))),
                )
            hub.chatScreenOpened(claude.id, "same", "")
            hub.chatScreenOpened(codex.id, "another pane", "")
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            hub.chatScreenClosed(claude.id, "same")
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `a failed fetch keeps acknowledgement but a gateway restart invalidates it`() =
        runTest {
            val hub = newHub()
            val finished = turns(turnLine("claude", "same", "succeeded"))
            val restarted =
                turns(turnLine("claude", "same", "succeeded"), gatewayStartedAt = "2026-10-27T09:15:00Z")
            val answers = listOf(finished, null, finished, restarted)
            var request = 0
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    hub = hub,
                    turns =
                        MockEngine {
                            val answer = answers[request++]
                            respond(
                                answer.orEmpty(),
                                if (answer == null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            )
                        },
                )
            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            hub.chatScreenClosed(claude.id, "same")
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.turnStatuses.value.isEmpty())
            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)
            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `success expires ten minutes after gateway completion without another refresh`() =
        runTest {
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    turns =
                        turnsEngine(
                            turns(
                                turnLine("claude", "same", "succeeded", changedAt = "2026-10-27T09:30:00Z"),
                                turnLine("claude", "working", "running", changedAt = "2026-10-27T09:30:00Z"),
                                turnLine("claude", "failed", "failed", changedAt = "2026-10-27T09:30:00Z"),
                                observedAt = "2026-10-27T09:30:00Z",
                            ),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            advanceTimeBy(599_999)
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.value["b-claude/working"]?.status)
            assertEquals(TurnStatus.ERROR, catalog.turnStatuses.value["b-claude/failed"]?.status)
        }

    @Test
    fun `stale or unreadable success after fetching cannot claim finish`() =
        runTest {
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    turns =
                        turnsEngine(
                            turns(
                                turnLine(
                                    "claude",
                                    "stale",
                                    "succeeded",
                                    changedAt = "2026-10-27T09:49:59Z",
                                ),
                                turnLine("claude", "malformed", "succeeded", changedAt = "not-a-time"),
                                turnLine("claude", "future", "succeeded", changedAt = "2026-10-27T10:02:00Z"),
                                turnLine("claude", "running", "running", changedAt = "not-a-time"),
                                turnLine("claude", "failed", "failed", changedAt = "not-a-time"),
                                observedAt = "2026-10-27T10:00:00Z",
                            ),
                            turns(
                                turnLine(
                                    "claude",
                                    "stale",
                                    "succeeded",
                                    changedAt = "2026-10-27T09:49:59Z",
                                ),
                                turnLine("claude", "malformed", "succeeded", changedAt = "not-a-time"),
                                turnLine("claude", "future", "succeeded", changedAt = "2026-10-27T10:02:00Z"),
                                turnLine("claude", "running", "running", changedAt = "not-a-time"),
                                turnLine("claude", "failed", "failed", changedAt = "not-a-time"),
                                observedAt = "2026-10-27T10:01:00Z",
                            ),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/stale"]?.status)

            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/stale"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/malformed"]?.status)
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/future"]?.status)
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.value["b-claude/running"]?.status)
            assertEquals(TurnStatus.ERROR, catalog.turnStatuses.value["b-claude/failed"]?.status)
        }

    @Test
    fun `an authoritative snapshot removing the session forgets its acknowledgement`() =
        runTest {
            val hub = newHub()
            val finished = turns(turnLine("claude", "same", "succeeded"))
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    hub = hub,
                    turns = turnsEngine(finished, turns(), finished),
                )
            hub.chatScreenOpened(claude.id, "same", "")
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            hub.chatScreenClosed(claude.id, "same")
            runCurrent()
            assertEquals(TurnStatus.UNKNOWN, catalog.turnStatuses.value["b-claude/same"]?.status)

            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.turnStatuses.value.isEmpty())
            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.value["b-claude/same"]?.status)
        }

    @Test
    fun `a running turn marks a chat the listing never reported`() =
        runTest {
            val projects = """{"type":"agent","agentId":"claude","status":"unsupported"}""" + "\n"
            val catalog =
                catalog(
                    MockEngine { respond(projects, HttpStatusCode.OK) },
                    turns = turnsEngine(turns(turnLine("claude", "unlisted", "running"))),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/unlisted"), catalog.inProgressChatKeys.first())
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.first()["b-claude/unlisted"]?.status)
        }

    @Test
    fun `status reaches only the agents bound on this phone, under their target ids`() =
        runTest {
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    turns =
                        turnsEngine(
                            turns(
                                turnLine("claude", "same", "running"),
                                turnLine("gemini", "other", "running"),
                            ),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(
                mapOf("b-claude/same" to TurnStatus.WORKING),
                catalog.turnStatuses.first().mapValues { it.value.status },
            )
        }

    @Test
    fun `a tracked chat's own status outranks the gateway answer even when it is unknown`() =
        runTest {
            val hub = newHub()
            val catalog =
                catalog(
                    MockEngine { respond(agentLine("claude", "ok", "same"), HttpStatusCode.OK) },
                    hub = hub,
                    turns = turnsEngine(turns(turnLine("claude", "same", "succeeded"))),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(
                mapOf("b-claude/same" to TurnStatus.FINISHED),
                catalog.turnStatuses.first().mapValues { it.value.status },
            )

            val (key, live) =
                hub.registerLiveChat(
                    serverId = claude.id,
                    sessionId = "same",
                    status = TurnStatus.UNKNOWN,
                )
            runCurrent()
            assertEquals(
                "a tracked chat with no outcome is unknown, not the gateway's finished",
                TurnStatus.UNKNOWN,
                catalog.turnStatuses.first()["b-claude/same"]?.status,
            )
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())

            live.value = SessionSnapshot(sessionId = "same", isStreaming = true, turnStatus = TurnStatus.WORKING)
            runCurrent()
            assertEquals(TurnStatus.WORKING, catalog.turnStatuses.first()["b-claude/same"]?.status)

            hub.close(key)
            runCurrent()
            assertEquals(TurnStatus.FINISHED, catalog.turnStatuses.first()["b-claude/same"]?.status)
        }

    @Test
    fun `a tracked chat whose local status was withdrawn does not inherit the gateway answer`() =
        runTest {
            val hub = newHub()
            val catalog =
                catalog(
                    MockEngine { respond(agentLine("claude", "ok", "same"), HttpStatusCode.OK) },
                    hub = hub,
                    turns =
                        turnsEngine(
                            turns(turnLine("claude", "same", "running", attention = listOf("permission"))),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(TurnStatus.NEEDS_ATTENTION, catalog.turnStatuses.first()["b-claude/same"]?.status)

            // A tracked chat with no live snapshot: its connection is gone, so nothing reliable
            // is known and the gateway's status must not stand in for it.
            val key = hub.registerChat(serverId = claude.id, sessionId = "same")
            runCurrent()
            assertTrue(
                "a tracked chat with no local status is unknown",
                key !in catalog.turnStatuses.first(),
            )
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())

            hub.close(key)
            runCurrent()
            assertEquals(TurnStatus.NEEDS_ATTENTION, catalog.turnStatuses.first()["b-claude/same"]?.status)
        }

    @Test
    fun `a cold start knows no status until a fetch answers`() =
        runTest {
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    turns = turnsEngine(turns(turnLine("claude", "same", "running"))),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())

            val restored = catalog(MockEngine { respond("", HttpStatusCode.OK) })
            runCurrent()
            assertTrue(restored.turnStatuses.first().isEmpty())
            assertTrue(restored.inProgressChatKeys.first().isEmpty())
        }

    @Test
    fun `a later snapshot drops a status the gateway no longer reports`() =
        runTest {
            val catalog =
                catalog(
                    MockEngine { respond("", HttpStatusCode.OK) },
                    turns =
                        turnsEngine(
                            turns(turnLine("claude", "same", "running")),
                            turns(turnLine("claude", "same", "succeeded")),
                            turns(),
                        ),
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())

            catalog.refresh(force = true).join()
            runCurrent()
            assertEquals(
                mapOf("b-claude/same" to TurnStatus.FINISHED),
                catalog.turnStatuses.first().mapValues { it.value.status },
            )
            assertTrue(catalog.inProgressChatKeys.first().isEmpty())

            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.turnStatuses.first().isEmpty())
        }

    @Test
    fun `a failed status fetch clears the statuses and leaves the listing alone`() =
        runTest {
            val projects = agentLine("claude", "ok", "same")
            var requests = 0
            val catalog =
                catalog(
                    MockEngine { respond(projects, HttpStatusCode.OK) },
                    turns =
                        MockEngine {
                            if (requests++ == 0) {
                                respond(turns(turnLine("claude", "same", "running")), HttpStatusCode.OK)
                            } else {
                                respond("", HttpStatusCode.InternalServerError)
                            }
                        },
                )
            runCurrent()
            catalog.refresh().join()
            runCurrent()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first())

            catalog.refresh(force = true).join()
            runCurrent()
            assertTrue(catalog.turnStatuses.first().isEmpty())
            assertEquals(setOf("same"), catalog.state.first().chatIds())
        }

    @Test
    fun `a slow listing does not hold up the status fetch`() =
        runTest {
            val listing = ByteChannel(autoFlush = true)
            val catalog =
                catalog(
                    MockEngine { respond(listing, HttpStatusCode.OK) },
                    turns = turnsEngine(turns(turnLine("claude", "same", "running"))),
                )
            runCurrent()
            val refreshing = catalog.refresh()
            assertEquals(setOf("b-claude/same"), catalog.inProgressChatKeys.first { it.isNotEmpty() })
            listing.close()
            refreshing.join()
        }

    @Test
    fun `activity timestamps exclude tracked and working chats without inferring running state`() =
        runTest {
            store().write(gateway.id, previous)
            val hub = newHub()
            val catalog =
                catalog(
                    MockEngine { respond(agentLine("claude", "unsupported", null), HttpStatusCode.OK) },
                    hub = hub,
                    turns = turnsEngine(turns(turnLine("claude", "old-claude", "running"))),
                )
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
}
