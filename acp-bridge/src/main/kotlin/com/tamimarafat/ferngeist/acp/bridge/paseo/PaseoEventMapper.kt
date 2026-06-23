package com.tamimarafat.ferngeist.acp.bridge.paseo

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import kotlinx.serialization.json.JsonObject

/**
 * Translates Paseo `AgentStreamEvent` payloads (and replayed timeline items) into
 * Ferngeist [AppSessionEvent]s, defensively, from raw [JsonObject]s.
 *
 * Permission and attention events are handled by [PaseoSessionPort] directly
 * because they need extra bookkeeping (the toolCallId↔requestId map, the
 * notification sink); everything else flows through here.
 */
internal object PaseoEventMapper {
    /** Maps one `AgentStreamEvent` object to zero or more [AppSessionEvent]s. */
    fun mapStreamEvent(event: JsonObject): List<AppSessionEvent> =
        when (event.typeTag()) {
            "thread_started" ->
                listOf(
                    AppSessionEvent.SessionInfoUpdated(
                        title = event.firstString("title"),
                        updatedAt = null,
                    ),
                )

            "turn_started" -> emptyList()

            "turn_completed" ->
                buildList {
                    event.obj("usage")?.let { add(mapUsage(it)) }
                    add(AppSessionEvent.TurnComplete(stopReason = "end_turn"))
                }

            "turn_failed" ->
                listOf(AppSessionEvent.TurnComplete(stopReason = "error"))

            "turn_canceled" ->
                listOf(AppSessionEvent.TurnComplete(stopReason = "cancelled"))

            "timeline" ->
                event.obj("item")?.let { mapTimelineItem(it) } ?: emptyList()

            else -> emptyList()
        }

    /** Maps one `AgentTimelineItem` (live or replayed) to [AppSessionEvent]s. */
    fun mapTimelineItem(item: JsonObject): List<AppSessionEvent> =
        when (item.typeTag()) {
            "user_message" ->
                item.str("text")?.let {
                    listOf(AppSessionEvent.UserMessage(text = it, append = false))
                } ?: emptyList()

            "assistant_message" ->
                item.str("text")?.let { listOf(AppSessionEvent.AgentMessage(text = it)) } ?: emptyList()

            "reasoning" ->
                item.str("text")?.let { listOf(AppSessionEvent.AgentThought(text = it)) } ?: emptyList()

            "tool_call" -> mapToolCall(item)

            "todo" -> emptyList() // Paseo todos render as plan entries; omitted until PlanEntry mapping is wired.

            "error" ->
                item.str("message")?.let { listOf(AppSessionEvent.AgentMessage(text = it)) } ?: emptyList()

            "compaction" -> emptyList()

            else -> emptyList()
        }

    private fun mapToolCall(item: JsonObject): List<AppSessionEvent> {
        val callId = item.firstString("callId", "id") ?: return emptyList()
        val name = item.firstString("name", "title") ?: "Tool Call"
        val statusRaw = item.str("status")
        val status = mapToolStatus(statusRaw)
        val detail = item.obj("detail")
        return if (statusRaw == null || statusRaw == "running" || statusRaw == "pending") {
            listOf(
                AppSessionEvent.ToolCallStarted(
                    toolCallId = callId,
                    title = name,
                    kind = ToolKind.OTHER,
                    status = status,
                    rawInput = detail,
                ),
            )
        } else {
            // completed / failed / canceled — the reducer bootstraps a started entry if missing.
            listOf(
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = callId,
                    status = status,
                    title = name,
                    kind = ToolKind.OTHER,
                    content = null,
                    rawInput = detail,
                    rawOutput = null,
                ),
            )
        }
    }

    private fun mapToolStatus(raw: String?): ToolCallStatus =
        when (raw) {
            "running" -> ToolCallStatus.IN_PROGRESS
            "pending" -> ToolCallStatus.PENDING
            "completed" -> ToolCallStatus.COMPLETED
            "failed" -> ToolCallStatus.FAILED
            "canceled", "cancelled" -> ToolCallStatus.FAILED
            else -> ToolCallStatus.IN_PROGRESS
        }

    private fun mapUsage(usage: JsonObject): AppSessionEvent.UsageUpdated =
        AppSessionEvent.UsageUpdated(
            promptTokens = usage.firstInt("promptTokens", "inputTokens", "input_tokens"),
            completionTokens = usage.firstInt("completionTokens", "outputTokens", "output_tokens"),
            totalTokens = usage.firstInt("totalTokens", "total_tokens"),
            cachedReadTokens =
                usage.firstInt("cachedReadTokens", "cachedInputTokens", "cache_read_tokens"),
            contextWindowTokens =
                usage.firstInt("contextWindowTokens", "contextWindow", "context_window"),
            costAmount = usage.firstDouble("costAmount", "costUsd", "cost"),
            costCurrency = usage.firstString("costCurrency", "currency"),
        )
}
