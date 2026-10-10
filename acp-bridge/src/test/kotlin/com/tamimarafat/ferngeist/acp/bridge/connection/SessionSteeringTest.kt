package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.acp.bridge.session.SessionMessageReducer
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class SessionSteeringTest {
    @Test
    fun steeringAdvertisedInInitializeMeta_reachesCapabilities() {
        val meta = Json.parseToJsonElement("""{"steering":{"supported":true},"other":1}""")

        assertTrue(AgentCapabilities().withSteeringMeta(meta).supportsSteering())
        assertFalse(AgentCapabilities().withSteeringMeta(null).supportsSteering())
        assertFalse(AgentCapabilities().withSteeringMeta(JsonObject(emptyMap())).supportsSteering())
    }

    @Test
    fun outcome_onlyInjectedAndStartedNewTurnCountAsTaken() {
        fun parse(json: String) = parseSteerOutcome(Json.parseToJsonElement(json))

        assertEquals(SteerOutcome.Injected, parse("""{"outcome":"injected"}"""))
        assertEquals(SteerOutcome.StartedNewTurn, parse("""{"outcome":"startedNewTurn"}"""))
        assertEquals(SteerOutcome.NotConsumed, parse("""{"outcome":"promptRequired","reason":"noRunningTurn"}"""))
        assertEquals(SteerOutcome.NotConsumed, parse("""{"outcome":"failed"}"""))
        assertEquals(SteerOutcome.NotConsumed, parse("null"))
    }

    @Test
    fun params_carryPromptBlocksAndAskNotToStartATurn() {
        val params = steeringParams("s1", listOf(ContentBlock.Text("hi")))

        assertEquals("s1", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals(
            "hi",
            params["prompt"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        val steering = params["_meta"]!!.jsonObject["steering"]!!.jsonObject
        assertEquals("promptRequired", steering["idleBehavior"]!!.jsonPrimitive.content)
    }

    @Test
    fun steeredPrompt_closesTheReplySoFarAndLandsBelowIt() {
        val streaming = ChatMessage(id = "a", role = ChatMessage.Role.ASSISTANT, content = "", isStreaming = true)

        val messages =
            SessionMessageReducer.appendLocalUserMessage(
                listOf(streaming),
                "nudge",
                emptyList(),
                emptyList(),
                steered = true,
            )

        assertEquals(listOf("a", null), messages.map { if (it.role == ChatMessage.Role.USER) null else it.id })
        assertFalse(messages[0].isStreaming)
        assertEquals("nudge", messages[1].content)
    }
}
