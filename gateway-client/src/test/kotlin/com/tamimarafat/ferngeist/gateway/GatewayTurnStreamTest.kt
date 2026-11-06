package com.tamimarafat.ferngeist.gateway

import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnKey
import com.tamimarafat.ferngeist.gateway.turns.GatewayTurnStreamFrame
import com.tamimarafat.ferngeist.gateway.turns.decodeTurnStreamFrame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayTurnStreamTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a snapshot frame decodes every turn member`() {
        val frame = decodeTurnStreamFrame(json, SNAPSHOT)

        val snapshot = (frame as GatewayTurnStreamFrame.Snapshot).snapshot
        assertEquals("2026-11-04T08:00:00Z", snapshot.gatewayStartedAt)
        assertEquals("2026-11-04T09:12:03.104381Z", snapshot.observedAt)
        val turn = snapshot.turns.single()
        assertEquals("claude", turn.agentId)
        assertEquals("gs_1", turn.gatewaySessionId)
        assertEquals("rt_1", turn.runtimeId)
        assertEquals("running", turn.state)
        assertEquals(listOf("permission"), turn.attention)
    }

    @Test
    fun `a changes frame decodes upserts and removed keys`() {
        val frame = decodeTurnStreamFrame(json, CHANGES)

        val changes = frame as GatewayTurnStreamFrame.Changes
        assertEquals("2026-11-04T09:15:02.77Z", changes.observedAt)
        assertEquals("succeeded", changes.upserts.single().state)
        assertEquals("end_turn", changes.upserts.single().stopReason)
        assertEquals(GatewayTurnKey("codex", "gs_7", "ses_q"), changes.removed.single())
    }

    @Test
    fun `a frame type this client predates is skipped`() {
        assertNull(decodeTurnStreamFrame(json, """{"type":"future","observedAt":"2026-11-04T09:15:02Z"}"""))
    }

    private companion object {
        val SNAPSHOT =
            """{"type":"snapshot","gatewayStartedAt":"2026-11-04T08:00:00Z","observedAt":"2026-11-04T09:12:03.104381Z","turns":[
            |  {"agentId":"claude","sessionId":"ses_a","gatewaySessionId":"gs_1","runtimeId":"rt_1",
            |   "state":"running","attention":["permission"],"startedAt":"2026-11-04T09:08:00.5Z",
            |   "changedAt":"2026-11-04T09:08:00.5Z"}]}
            """.trimMargin()

        val CHANGES =
            """{"type":"changes","observedAt":"2026-11-04T09:15:02.77Z",
            | "upserts":[
            |  {"agentId":"claude","sessionId":"ses_a","gatewaySessionId":"gs_1","runtimeId":"rt_1",
            |   "state":"succeeded","attention":[],"startedAt":"2026-11-04T09:08:00.5Z",
            |   "changedAt":"2026-11-04T09:15:02.769Z","stopReason":"end_turn"}],
            | "removed":[{"agentId":"codex","gatewaySessionId":"gs_7","sessionId":"ses_q"}]}
            """.trimMargin()
    }
}
