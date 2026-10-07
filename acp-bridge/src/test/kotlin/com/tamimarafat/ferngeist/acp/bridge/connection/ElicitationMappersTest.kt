@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.ElicitationId
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationSchema
import com.agentclientprotocol.model.ElicitationSchemaType
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.ToolCallId
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ElicitationFieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ElicitationMappersTest {
    private fun formRequest(toolCallId: ToolCallId? = ToolCallId("tool-1")) =
        CreateElicitationRequest(
            scope = ElicitationScope.Session(SessionId("ses-1"), toolCallId),
            mode =
                ElicitationMode.Form(
                    ElicitationSchema(
                        type = ElicitationSchemaType.OBJECT,
                        title = "Refactor",
                        properties =
                            mapOf(
                                "strategy" to
                                    ElicitationPropertySchema.StringProperty(
                                        title = "Strategy",
                                        description = null,
                                        minLength = null,
                                        maxLength = null,
                                        pattern = null,
                                        format = null,
                                        default = "balanced",
                                        enumValues = listOf("conservative", "balanced"),
                                        oneOf = null,
                                    ),
                            ),
                        required = listOf("strategy"),
                        description = null,
                    ),
                ),
            message = "How should I approach this?",
        )

    @Test
    fun formRequest_mapsSchemaToSelectField() {
        val domain = ElicitationMappers.toDomain("ses-1", formRequest())

        assertTrue(domain is ChatElicitationRequest.Form)
        val form = domain as ChatElicitationRequest.Form
        assertEquals("ses-1", form.sessionId)
        assertEquals("tool-1", form.toolCallId)
        assertEquals("How should I approach this?", form.message)
        assertEquals(1, form.fields.size)
        val field = form.fields.first()
        assertEquals("strategy", field.key)
        assertTrue(field.required)
        val kind = field.kind as ElicitationFieldKind.SingleSelect
        assertEquals(listOf("conservative", "balanced"), kind.options.map { it.value })
        assertEquals("balanced", kind.default)
    }

    @Test
    fun formRequest_withoutToolCall_mapsToSessionScopedForm() {
        val domain = ElicitationMappers.toDomain("ses-1", formRequest(toolCallId = null))

        assertTrue(domain is ChatElicitationRequest.Form)
        assertNull((domain as ChatElicitationRequest.Form).toolCallId)
    }

    @Test
    fun urlRequest_mapsToUrlDomain() {
        val request =
            CreateElicitationRequest(
                scope = ElicitationScope.Session(SessionId("ses-1"), null),
                mode = ElicitationMode.Url(ElicitationId("github-oauth-001"), "https://agent.example.com/connect"),
                message = "Please authorize access.",
            )

        val domain = ElicitationMappers.toDomain("ses-1", request)

        assertTrue(domain is ChatElicitationRequest.Url)
        val url = domain as ChatElicitationRequest.Url
        assertEquals("github-oauth-001", url.elicitationId)
        assertEquals("https://agent.example.com/connect", url.url)
    }

    @Test
    fun acceptResponse_carriesTypedContentValues() {
        val response =
            ElicitationMappers.toAcceptResponse(
                mapOf(
                    "name" to ChatElicitationValue.TextValue("ada"),
                    "enabled" to ChatElicitationValue.BooleanValue(true),
                    "count" to ChatElicitationValue.IntegerValue(3),
                    "ratio" to ChatElicitationValue.NumberValue(1.5),
                    "tags" to ChatElicitationValue.StringListValue(listOf("a", "b")),
                ),
            )

        val content =
            (response.action as com.agentclientprotocol.model.ElicitationAction.Accept).content
        assertEquals(5, content?.size)
    }
}
