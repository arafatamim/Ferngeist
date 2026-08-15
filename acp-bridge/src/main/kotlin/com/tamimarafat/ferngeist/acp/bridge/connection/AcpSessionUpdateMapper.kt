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
            is SessionUpdate.UserMessageChunk -> mapUserMessageChunk(update)
            is SessionUpdate.AgentMessageChunk -> AppSessionEvent.AgentMessage(extractText(update.content))
            is SessionUpdate.AgentThoughtChunk -> AppSessionEvent.AgentThought(extractText(update.content))
            is SessionUpdate.ToolCall -> mapToolCall(update)
            is SessionUpdate.ToolCallUpdate -> mapToolCallUpdate(update)
            is SessionUpdate.PlanUpdate, is SessionUpdate.PlanRemoved -> mapPlanUpdate(update, removed = update is SessionUpdate.PlanRemoved)
            is SessionUpdate.AvailableCommandsUpdate -> mapAvailableCommands(update)
            is SessionUpdate.CurrentModeUpdate -> AppSessionEvent.ModeChanged(update.currentModeId.value)
            is SessionUpdate.UsageUpdate -> mapUsageUpdate(update)
            is SessionUpdate.ConfigOptionUpdate -> mapConfigOptionUpdate(update)
            is SessionUpdate.SessionInfoUpdate -> mapSessionInfoUpdate(update)
            is SessionUpdate.PlanUpdateV2 -> mapPlanUpdateV2(update)
            else -> AppSessionEvent.Unknown(update.toString())
        }

    @OptIn(UnstableApi::class)
    private fun mapPlanUpdate(update: SessionUpdate, removed: Boolean): AppSessionEvent =
        AppSessionEvent.PlanUpdated(
            entries = if (removed) emptyList() else (update as SessionUpdate.PlanUpdate).entries,
        )

    @OptIn(UnstableApi::class)
    private fun mapSessionInfoUpdate(update: SessionUpdate.SessionInfoUpdate): AppSessionEvent =
        AppSessionEvent.SessionInfoUpdated(
            title = update.title,
            updatedAt = update.updatedAt,
        )

    @OptIn(UnstableApi::class)
    private fun mapUserMessageChunk(update: SessionUpdate.UserMessageChunk): AppSessionEvent =
        AppSessionEvent.UserMessage(
            text = extractText(update.content),
            images = listOfNotNull(extractImage(update.content)),
            files = listOfNotNull(extractFiles(update.content)),
            append = true,
        )

    @OptIn(UnstableApi::class)
    private fun mapToolCall(update: SessionUpdate.ToolCall): AppSessionEvent =
        AppSessionEvent.ToolCallStarted(
            toolCallId = update.toolCallId.value,
            title = update.title,
            kind = update.kind,
            status = update.status,
            rawInput = update.rawInput,
        )

    @OptIn(UnstableApi::class)
    private fun mapToolCallUpdate(update: SessionUpdate.ToolCallUpdate): AppSessionEvent =
        AppSessionEvent.ToolCallUpdated(
            toolCallId = update.toolCallId.value,
            status = update.status,
            title = update.title,
            kind = update.kind,
            content = update.content,
            rawInput = update.rawInput,
            rawOutput = update.rawOutput,
        )

    @OptIn(UnstableApi::class)
    private fun mapAvailableCommands(update: SessionUpdate.AvailableCommandsUpdate): AppSessionEvent =
        AppSessionEvent.CommandsUpdated(
            update.availableCommands.map { cmd ->
                CommandInfo(name = cmd.name, description = cmd.description)
            },
        )

    @OptIn(UnstableApi::class)
    private fun mapUsageUpdate(update: SessionUpdate.UsageUpdate): AppSessionEvent =
        AppSessionEvent.UsageUpdated(
            totalTokens = update.used.toInt(),
            contextWindowTokens = update.size.toInt(),
            costAmount = update.cost?.amount,
            costCurrency = update.cost?.currency,
        )

    @OptIn(UnstableApi::class)
    private fun mapConfigOptionUpdate(update: SessionUpdate.ConfigOptionUpdate): AppSessionEvent =
        AppSessionEvent.ConfigOptionsUpdated(
            update.configOptions.map { sdkOption ->
                mapSdkConfigOption(sdkOption)
            },
        )

    @OptIn(UnstableApi::class)
    private fun mapPlanUpdateV2(update: SessionUpdate.PlanUpdateV2): AppSessionEvent =
        AppSessionEvent.PlanUpdated(
            entries =
                if (update.plan is PlanVariant.Items) {
                    (update.plan as PlanVariant.Items).entries
                } else {
                    emptyList()
                },
        )

    fun mapStopReason(reason: StopReason): String =
        when (reason) {
            StopReason.END_TURN -> "end_turn"
            StopReason.MAX_TOKENS -> "max_tokens"
            StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
            StopReason.REFUSAL -> "refusal"
            StopReason.CANCELLED -> "cancelled"
        }

    private const val MILLIS_MIN_TEN_DIGITS = 1_000_000_000L
    private const val MILLIS_MAX_TEN_DIGITS = 9_999_999_999L
    private const val MILLIS_PER_SECOND = 1000L

    fun parseIsoOrMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()

        raw.toLongOrNull()?.let { numeric ->
            return if (numeric in MILLIS_MIN_TEN_DIGITS..MILLIS_MAX_TEN_DIGITS) {
                numeric * MILLIS_PER_SECOND
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
