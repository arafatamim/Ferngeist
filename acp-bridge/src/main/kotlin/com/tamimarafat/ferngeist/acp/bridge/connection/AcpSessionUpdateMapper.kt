package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigChoice
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigOption
import java.time.Instant
import java.time.OffsetDateTime

internal object AcpSessionUpdateMapper {
    @OptIn(UnstableApi::class)
    fun mapSessionUpdateToEvent(update: SessionUpdate): AppSessionEvent? {
        return when (update) {
            is SessionUpdate.UserMessageChunk -> AppSessionEvent.UserMessage(
                text = extractText(update.content),
                append = true,
            )
            is SessionUpdate.AgentMessageChunk -> AppSessionEvent.AgentMessage(extractText(update.content))
            is SessionUpdate.AgentThoughtChunk -> AppSessionEvent.AgentThought(extractText(update.content))
            is SessionUpdate.ToolCall -> AppSessionEvent.ToolCallStarted(
                toolCallId = update.toolCallId.value,
                title = update.title,
                kind = update.kind?.toString()?.lowercase(),
                status = update.status?.toString()?.lowercase(),
            )
            is SessionUpdate.ToolCallUpdate -> AppSessionEvent.ToolCallUpdated(
                toolCallId = update.toolCallId.value,
                status = update.status?.toString()?.lowercase(),
                title = update.title,
                kind = update.kind?.toString()?.lowercase(),
                output = update.content?.joinToString("\n") { it.toString() },
                rawOutput = update.rawOutput?.toString(),
            )
            is SessionUpdate.PlanUpdate -> AppSessionEvent.PlanUpdated(
                content = update.entries.joinToString("\n") { it.toString() }
            )
            is SessionUpdate.AvailableCommandsUpdate -> AppSessionEvent.CommandsUpdated(
                update.availableCommands.map { it.name }
            )
            is SessionUpdate.CurrentModeUpdate -> AppSessionEvent.ModeChanged(update.currentModeId.value)
            is SessionUpdate.UsageUpdate -> {
                val costUsd = update.cost?.takeIf { it.currency.equals("USD", ignoreCase = true) }?.amount
                AppSessionEvent.UsageUpdated(
                    totalTokens = update.used.toInt(),
                    contextWindowTokens = update.size.toInt(),
                    costUsd = costUsd,
                )
            }
            is SessionUpdate.ConfigOptionUpdate -> {
                val mapped = update.configOptions.map { sdkOption ->
                    mapSdkConfigOption(sdkOption)
                }
                AppSessionEvent.ConfigOptionsUpdated(mapped)
            }
            is SessionUpdate.SessionInfoUpdate -> AppSessionEvent.SessionInfoUpdated(
                title = update.title,
                updatedAt = update.updatedAt,
            )
            is SessionUpdate.UnknownSessionUpdate -> AppSessionEvent.Unknown(update.toString())
        }
    }

    fun mapStopReason(reason: StopReason): String {
        return when (reason) {
            StopReason.END_TURN -> "end_turn"
            StopReason.MAX_TOKENS -> "max_tokens"
            StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
            StopReason.REFUSAL -> "refusal"
            StopReason.CANCELLED -> "cancelled"
        }
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

    private fun extractText(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            else -> content.toString()
        }
    }

    @OptIn(UnstableApi::class)
    private fun mapSdkConfigOption(
        sdkOption: com.agentclientprotocol.model.SessionConfigOption
    ): SessionConfigOption {
        return when (sdkOption) {
            is com.agentclientprotocol.model.SessionConfigOption.Select -> {
                val choices = when (val opts = sdkOption.options) {
                    is com.agentclientprotocol.model.SessionConfigSelectOptions.Flat ->
                        opts.options.map { selectOpt ->
                            SessionConfigChoice(
                                id = selectOpt.value.value,
                                label = selectOpt.name,
                                value = selectOpt.value.value,
                                description = selectOpt.description,
                            )
                        }
                    is com.agentclientprotocol.model.SessionConfigSelectOptions.Grouped ->
                        opts.groups.flatMap { group ->
                            group.options.map { selectOpt ->
                                SessionConfigChoice(
                                    id = selectOpt.value.value,
                                    label = selectOpt.name,
                                    value = selectOpt.value.value,
                                    description = selectOpt.description,
                                )
                            }
                        }
                }
                SessionConfigOption(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    kind = "select",
                    currentValue = sdkOption.currentValue.value,
                    options = choices,
                )
            }
            else -> {
                SessionConfigOption(
                    id = sdkOption.id.value,
                    name = sdkOption.name,
                    description = sdkOption.description,
                    kind = "boolean",
                    currentValue = null,
                )
            }
        }
    }
}
