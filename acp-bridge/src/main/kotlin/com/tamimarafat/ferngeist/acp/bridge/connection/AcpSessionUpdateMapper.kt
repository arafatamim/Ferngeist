package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.CommandInfo
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoiceGroup
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOrigin
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import java.time.Instant
import java.time.OffsetDateTime

internal object AcpSessionUpdateMapper {
    @OptIn(UnstableApi::class)
    fun mapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent? =
        when (update) {
            is SessionUpdate.UserMessageChunk ->
                AppSessionEvent.UserMessage(
                    text = extractText(update.content),
                    images = listOfNotNull(extractImage(update.content)),
                    files = listOfNotNull(extractFiles(update.content)),
                    append = true,
                )
            is SessionUpdate.AgentMessageChunk -> AppSessionEvent.AgentMessage(extractText(update.content))
            is SessionUpdate.AgentThoughtChunk -> AppSessionEvent.AgentThought(extractText(update.content))
            is SessionUpdate.ToolCall ->
                AppSessionEvent.ToolCallStarted(
                    toolCallId = update.toolCallId.value,
                    title = update.title,
                    kind = update.kind,
                    status = update.status,
                    rawInput = update.rawInput,
                )
            is SessionUpdate.ToolCallUpdate ->
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = update.toolCallId.value,
                    status = update.status,
                    title = update.title,
                    kind = update.kind,
                    content = update.content,
                    rawInput = update.rawInput,
                    rawOutput = update.rawOutput,
                )
            is SessionUpdate.PlanUpdate ->
                AppSessionEvent.PlanUpdated(
                    entries = update.entries,
                )
            is SessionUpdate.AvailableCommandsUpdate ->
                AppSessionEvent.CommandsUpdated(
                    update.availableCommands.map { cmd ->
                        CommandInfo(name = cmd.name, description = cmd.description)
                    },
                )
            is SessionUpdate.CurrentModeUpdate -> AppSessionEvent.ModeChanged(update.currentModeId.value)
            is SessionUpdate.UsageUpdate -> {
                AppSessionEvent.UsageUpdated(
                    totalTokens = update.used.toInt(),
                    contextWindowTokens = update.size.toInt(),
                    costAmount = update.cost?.amount,
                    costCurrency = update.cost?.currency,
                )
            }
            is SessionUpdate.ConfigOptionUpdate -> {
                val mapped =
                    update.configOptions.map { sdkOption ->
                        mapSdkConfigOption(sdkOption)
                    }
                AppSessionEvent.ConfigOptionsUpdated(mapped)
            }
            is SessionUpdate.SessionInfoUpdate ->
                AppSessionEvent.SessionInfoUpdated(
                    title = update.title,
                    updatedAt = update.updatedAt,
                )
            is SessionUpdate.PlanRemoved ->
                AppSessionEvent.PlanUpdated(entries = emptyList())
            is SessionUpdate.PlanUpdateV2 ->
                AppSessionEvent.PlanUpdated(
                    entries =
                        if (update.plan is PlanVariant.Items) {
                            (update.plan as PlanVariant.Items).entries
                        } else {
                            emptyList()
                        },
                )
            is SessionUpdate.UnknownSessionUpdate -> AppSessionEvent.Unknown(update.toString())
        }

    fun mapStopReason(reason: StopReason): String =
        when (reason) {
            StopReason.END_TURN -> "end_turn"
            StopReason.MAX_TOKENS -> "max_tokens"
            StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
            StopReason.REFUSAL -> "refusal"
            StopReason.CANCELLED -> "cancelled"
        }

    fun parseIsoOrMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()

        raw.toLongOrNull()?.let { numeric ->
            return if (numeric in 1_000_000_000L..9_999_999_999L) {
                numeric * 1000L
            } else {
                numeric
            }
        }

        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }
    private fun extractText(content: ContentBlock): String =
        when (content) {
            is ContentBlock.Text -> content.text
            else -> ""
        }

    private fun extractImage(content: ContentBlock): ChatImageData? {
        if (content !is ContentBlock.Image) return null
        val base64 = content.data.ifBlank { dataUriBase64(content.uri) }
        if (base64.isBlank()) return null
        val mimeType = content.mimeType.ifBlank { dataUriMimeType(content.uri) }.ifBlank { "image/*" }
        return ChatImageData(base64 = base64, mimeType = mimeType)
    }

    private fun extractFiles(content: ContentBlock): ChatFileData? {
        if (content !is ContentBlock.Resource) return null
        val resource = content.resource as? EmbeddedResourceResource.BlobResourceContents ?: return null
        val blob = resource.blob
        if (blob.isBlank()) return null
        val name = resource.uri.substringAfterLast('/').ifBlank { "file" }
        val mimeType = resource.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val sizeBytes = blob.length.toLong() * 3L / 4L
        return ChatFileData(name = name, base64 = blob, mimeType = mimeType, sizeBytes = sizeBytes)
    }

    /** Extracts the base64 payload from a `data:<mime>;base64,<payload>` URI, else "". */
    private fun dataUriBase64(uri: String?): String {
        val value = uri.orEmpty()
        val marker = ";base64,"
        val idx = value.indexOf(marker)
        return if (value.startsWith("data:") && idx >= 0) value.substring(idx + marker.length) else ""
    }

    /** Extracts the media type from a `data:<mime>;base64,...` URI, else "". */
    private fun dataUriMimeType(uri: String?): String {
        val value = uri.orEmpty()
        if (!value.startsWith("data:")) return ""
        val idx = value.indexOf(";base64,")
        val start = "data:".length
        return if (idx > start) value.substring(start, idx) else ""
    }

    @OptIn(UnstableApi::class)
    internal fun mapSdkConfigOption(sdkOption: com.agentclientprotocol.model.SessionConfigOption): SessionConfigOption =
        when (sdkOption) {
            is com.agentclientprotocol.model.SessionConfigOption.Select -> {
                val (choices, groups) =
                    when (val opts = sdkOption.options) {
                        is com.agentclientprotocol.model.SessionConfigSelectOptions.Flat -> {
                            val mappedChoices = opts.options.map(::mapSdkSelectChoice)
                            mappedChoices to emptyList()
                        }

                        is com.agentclientprotocol.model.SessionConfigSelectOptions.Grouped -> {
                            val mappedGroups =
                                opts.groups.map { group ->
                                    SessionConfigChoiceGroup(
                                        id = group.group.value,
                                        label = group.name,
                                        choices = group.options.map(::mapSdkSelectChoice),
                                    )
                                }
                            mappedGroups.flatMap { it.choices } to mappedGroups
                        }
                    }
                SessionConfigOption.Select(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    origin = SessionConfigOrigin.NativeConfigOption,
                    currentValue = sdkOption.currentValue.value,
                    choices = choices,
                    groups = groups,
                )
            }

            is com.agentclientprotocol.model.SessionConfigOption.BooleanOption -> {
                SessionConfigOption.BooleanOption(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    origin = SessionConfigOrigin.NativeConfigOption,
                    currentValue = sdkOption.currentValue,
                )
            }
        }

    @OptIn(UnstableApi::class)
    private fun mapSdkSelectChoice(
        selectOpt: com.agentclientprotocol.model.SessionConfigSelectOption,
    ): SessionConfigChoice =
        SessionConfigChoice(
            id = selectOpt.value.value,
            label = selectOpt.name,
            value = selectOpt.value.value,
            description = selectOpt.description,
        )
}
