@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.MultiSelectItems
import com.tamimarafat.ferngeist.core.model.ChatElicitationField
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ElicitationFieldKind
import com.tamimarafat.ferngeist.core.model.ElicitationOption
import java.util.UUID

/**
 * Maps ACP `elicitation/create` requests to chat-domain models and user answers back.
 *
 * Returns null for requests this client cannot render: request-scoped elicitations
 * (outside any session) have no session UI to attach to. Callers answer those
 * with [cancelResponse] directly.
 */
internal object ElicitationMappers {
    fun toDomain(
        sessionId: String,
        request: CreateElicitationRequest,
    ): ChatElicitationRequest? {
        val scope = request.scope as? ElicitationScope.Session ?: return null
        val toolCallId = scope.toolCallId?.value
        val key = UUID.randomUUID().toString()
        return when (val mode = request.mode) {
            is ElicitationMode.Form -> {
                val schema = mode.requestedSchema
                ChatElicitationRequest.Form(
                    key = key,
                    message = request.message,
                    sessionId = sessionId,
                    toolCallId = toolCallId,
                    title = schema.title,
                    description = schema.description,
                    fields =
                        schema.properties.map { (name, property) ->
                            toField(name, property, schema.required ?: emptyList())
                        },
                )
            }
            is ElicitationMode.Url ->
                ChatElicitationRequest.Url(
                    key = key,
                    message = request.message,
                    sessionId = sessionId,
                    toolCallId = toolCallId,
                    elicitationId = mode.elicitationId.value,
                    url = mode.url,
                )
        }
    }

    fun toAcceptResponse(values: Map<String, ChatElicitationValue>): CreateElicitationResponse =
        CreateElicitationResponse(
            action =
                ElicitationAction.Accept(
                    content = values.mapValues { (_, value) -> value.toSdk() },
                ),
        )

    fun declineResponse(): CreateElicitationResponse = CreateElicitationResponse(action = ElicitationAction.Decline)

    fun cancelResponse(): CreateElicitationResponse = CreateElicitationResponse(action = ElicitationAction.Cancel)

    private fun toField(
        name: String,
        property: ElicitationPropertySchema,
        required: List<String>,
    ): ChatElicitationField =
        when (property) {
            is ElicitationPropertySchema.StringProperty -> toStringField(name, property, required)
            is ElicitationPropertySchema.BooleanProperty ->
                ChatElicitationField(
                    key = name,
                    title = property.title,
                    description = property.description,
                    required = name in required,
                    kind = ElicitationFieldKind.BooleanField(default = property.default),
                )
            is ElicitationPropertySchema.IntegerProperty ->
                ChatElicitationField(
                    key = name,
                    title = property.title,
                    description = property.description,
                    required = name in required,
                    kind =
                        ElicitationFieldKind.IntegerField(
                            minimum = property.minimum,
                            maximum = property.maximum,
                            default = property.default,
                        ),
                )
            is ElicitationPropertySchema.NumberProperty ->
                ChatElicitationField(
                    key = name,
                    title = property.title,
                    description = property.description,
                    required = name in required,
                    kind =
                        ElicitationFieldKind.NumberField(
                            minimum = property.minimum,
                            maximum = property.maximum,
                            default = property.default,
                        ),
                )
            is ElicitationPropertySchema.ArrayProperty ->
                ChatElicitationField(
                    key = name,
                    title = property.title,
                    description = property.description,
                    required = name in required,
                    kind =
                        ElicitationFieldKind.MultiSelect(
                            options = property.items.toOptions(),
                            default = property.default ?: emptyList(),
                            minItems = property.minItems?.toInt(),
                            maxItems = property.maxItems?.toInt(),
                        ),
                )
        }

    private fun toStringField(
        name: String,
        property: ElicitationPropertySchema.StringProperty,
        required: List<String>,
    ): ChatElicitationField {
        val options = property.toOptions()
        val kind =
            if (options != null) {
                ElicitationFieldKind.SingleSelect(options = options, default = property.default)
            } else {
                ElicitationFieldKind.Text(
                    minLength = property.minLength,
                    maxLength = property.maxLength,
                    pattern = property.pattern,
                    default = property.default,
                )
            }
        return ChatElicitationField(
            key = name,
            title = property.title,
            description = property.description,
            required = name in required,
            kind = kind,
        )
    }

    private fun ElicitationPropertySchema.StringProperty.toOptions(): List<ElicitationOption>? {
        val enum = enumValues
        if (!enum.isNullOrEmpty()) {
            return enum.map { ElicitationOption(value = it) }
        }
        val alternatives = oneOf
        if (!alternatives.isNullOrEmpty()) {
            return alternatives.map {
                ElicitationOption(value = it.value, title = it.title, description = it.description)
            }
        }
        return null
    }

    private fun MultiSelectItems.toOptions(): List<ElicitationOption> =
        when (this) {
            is MultiSelectItems.Titled -> items.options.map { ElicitationOption(it.value, it.title, it.description) }
            is MultiSelectItems.Untitled -> items.values.map { ElicitationOption(value = it) }
        }

    private fun ChatElicitationValue.toSdk(): ElicitationContentValue =
        when (this) {
            is ChatElicitationValue.TextValue -> ElicitationContentValue.StringValue(value)
            is ChatElicitationValue.BooleanValue -> ElicitationContentValue.BooleanValue(value)
            is ChatElicitationValue.IntegerValue -> ElicitationContentValue.IntegerValue(value)
            is ChatElicitationValue.NumberValue -> ElicitationContentValue.NumberValue(value)
            is ChatElicitationValue.StringListValue -> ElicitationContentValue.StringArrayValue(values)
        }
}
