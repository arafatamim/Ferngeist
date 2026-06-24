package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import java.util.UUID

/**
 * Location of a tool call segment inside the message list.
 *
 * Together with a [Map] keyed by `toolCallId`, this gives O(1) lookup for tool
 * call updates/permissions instead of the O(messages × segments) scan the
 * reducer used to do.
 */
data class ToolCallLocation(
    val messageIndex: Int,
    val segmentIndex: Int,
)

/**
 * Result of reducing a single event: the updated message list plus the
 * (mostly unchanged) tool call index. Sub-handlers that don't touch tool
 * calls return the same index they were given; sub-handlers that do
 * ([upsertToolCall], [updateToolCall], [updateToolCallPermission],
 * [clearToolCallPermission]) return a precisely updated copy.
 */
data class ReducerResult(
    val messages: List<ChatMessage>,
    val toolCallIndex: Map<String, ToolCallLocation>,
)

object SessionMessageReducer {
    fun handleEvent(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent,
    ): ReducerResult = when (event) {
        is AppSessionEvent.UserMessage ->
            ReducerResult(
                messages = appendUserText(messages, event.text, event.append, event.timestampMs),
                toolCallIndex = toolCallIndex,
            )
        is AppSessionEvent.AgentMessage ->
            appendText(
                messages,
                toolCallIndex,
                event.text,
                AssistantSegment.Kind.MESSAGE,
                event.timestampMs,
            )
        is AppSessionEvent.AgentThought ->
            appendText(
                messages,
                toolCallIndex,
                event.text,
                AssistantSegment.Kind.THOUGHT,
                event.timestampMs,
            )
        is AppSessionEvent.ToolCallStarted ->
            upsertToolCall(messages, toolCallIndex, event)
        is AppSessionEvent.ToolCallUpdated ->
            updateToolCall(messages, toolCallIndex, event)
        is AppSessionEvent.ToolPermissionRequested ->
            updateToolCallPermission(messages, toolCallIndex, event)
        is AppSessionEvent.ToolPermissionResolved ->
            clearToolCallPermission(messages, toolCallIndex, event.toolCallId)
        is AppSessionEvent.PlanUpdated ->
            ReducerResult(
                messages = updatePlan(messages, event.entries, event.timestampMs),
                toolCallIndex = toolCallIndex,
            )
        is AppSessionEvent.TurnComplete ->
            ReducerResult(
                messages = finishStreaming(messages),
                toolCallIndex = toolCallIndex,
            )
        else ->
            ReducerResult(messages = messages, toolCallIndex = toolCallIndex)
    }

    fun startStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        // Idempotent: avoid stacking placeholder bubbles when called repeatedly
        if (messages.isNotEmpty() &&
            messages.last().role == ChatMessage.Role.ASSISTANT &&
            messages.last().isStreaming
        ) {
            return messages
        }

        val streamingMessage =
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true,
            )
        return messages + streamingMessage
    }

    fun appendLocalUserMessage(
        messages: List<ChatMessage>,
        text: String,
        images: List<ChatImageData>,
    ): List<ChatMessage> {
        // Optimistic insertion: the UI gets an immediate user bubble before the server round-trip
        if (text.isBlank() && images.isEmpty()) return messages
        return messages +
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                images = images,
                createdAt = System.currentTimeMillis(),
            )
    }

    private fun appendUserText(
        messages: List<ChatMessage>,
        text: String,
        append: Boolean,
        timestampMs: Long?,
    ): List<ChatMessage> {
        if (text.isEmpty()) return messages
        val mutableMessages = messages.toMutableList()
        val lastMessage = mutableMessages.lastOrNull()

        // append=true: server echo via UserMessageChunk — dedup against the user message or
        // the streaming placeholder that follows it
        if (append) {
            // Exact-match dedup when the last message is already a USER bubble
            if (lastMessage?.role == ChatMessage.Role.USER) {
                if (lastMessage.content == text) return messages
                mutableMessages[mutableMessages.lastIndex] =
                    lastMessage.copy(content = lastMessage.content + text)
                return mutableMessages
            }

            // Server echo dedup: when the assistant placeholder is still empty (no content, no
            // segments), the echoed chunk matches the user text that's one message back
            val previousMessage =
                if (mutableMessages.size >=
                    2
                ) {
                    mutableMessages[mutableMessages.lastIndex - 1]
                } else {
                    null
                }
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT &&
                lastMessage.isStreaming &&
                lastMessage.content.isBlank() &&
                lastMessage.segments.isEmpty() &&
                previousMessage?.role == ChatMessage.Role.USER
            ) {
                val previousText = previousMessage.content
                if (previousText.startsWith(text) || previousText == text) {
                    return messages
                }
            }
        } else {
            // append=false: local optimistic insertion (or redundant source) — dedup against the
            // last USER bubble or against a streaming placeholder whose preceding USER matches
            if (lastMessage?.role == ChatMessage.Role.USER && lastMessage.content == text) {
                return messages
            }
            val previousMessage =
                if (mutableMessages.size >= 2) {
                    mutableMessages[mutableMessages.lastIndex - 1]
                } else {
                    null
                }
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT &&
                lastMessage.isStreaming &&
                lastMessage.content.isBlank() &&
                lastMessage.segments.isEmpty() &&
                previousMessage?.role == ChatMessage.Role.USER &&
                previousMessage.content == text
            ) {
                return messages
            }
        }

        // A new user message arriving means any running assistant turn is obsolete — close it
        val lastStreamingIndex =
            mutableMessages.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT && it.isStreaming
            }
        if (lastStreamingIndex == mutableMessages.lastIndex && lastStreamingIndex >= 0) {
            val streaming = mutableMessages[lastStreamingIndex]
            mutableMessages[lastStreamingIndex] = streaming.copy(isStreaming = false)
        }

        return mutableMessages +
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                createdAt = timestampMs ?: System.currentTimeMillis(),
            )
    }

    private fun appendText(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        text: String,
        kind: AssistantSegment.Kind,
        timestampMs: Long?,
    ): ReducerResult {
        if (text.isEmpty()) return ReducerResult(messages, toolCallIndex)
        val mutableMessages = messages.toMutableList()

        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT) {
                // Reuse the last assistant bubble when chunks arrive in sequence
                mutableMessages.lastIndex
            } else {
                // First chunk of a new turn — seed a fresh assistant bubble
                val newMessage =
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = timestampMs ?: System.currentTimeMillis(),
                    )
                mutableMessages.add(newMessage)
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]

        // Coalesce consecutive segments of the same kind (e.g. two MESSAGE chunks from
        // interleaved tool calls) into one segment instead of stacking many tiny entries.
        // The PersistentList.mutate builder shares structure when no mutation occurs and
        // otherwise appends in O(log n) instead of copying the segment list on every chunk.
        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                val last = segments.lastOrNull()
                if (last != null && last.kind == kind && last.toolCall == null) {
                    segments[segments.lastIndex] = last.copy(text = last.text + text)
                } else {
                    segments.add(AssistantSegment(id = UUID.randomUUID().toString(), kind = kind, text = text))
                }
            }

        // Derive the flat content string from MESSAGE segments for backward-compatible access
        val updatedContent =
            newSegments
                .filter { it.kind == AssistantSegment.Kind.MESSAGE }
                .joinToString("") { it.text }

        mutableMessages[targetIndex] =
            message.copy(
                segments = newSegments,
                content = updatedContent,
                isStreaming = true,
            )
        return ReducerResult(mutableMessages, toolCallIndex)
    }

    private fun updatePlan(
        messages: List<ChatMessage>,
        entries: List<PlanEntry>,
        timestampMs: Long?,
    ): List<ChatMessage> {
        if (entries.isEmpty()) return messages
        val mutableMessages = messages.toMutableList()

        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT) {
                mutableMessages.lastIndex
            } else {
                val newMessage =
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = timestampMs ?: System.currentTimeMillis(),
                    )
                mutableMessages.add(newMessage)
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]

        // Plans are a log: each update replaces the preceding plan segment rather than stacking
        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                val existingPlanIndex = segments.indexOfLast { it.kind == AssistantSegment.Kind.PLAN }
                if (existingPlanIndex != -1) {
                    segments[existingPlanIndex] =
                        segments[existingPlanIndex].copy(
                            text = "",
                            planEntries = entries,
                        )
                } else {
                    segments.add(
                        AssistantSegment(
                            id = UUID.randomUUID().toString(),
                            kind = AssistantSegment.Kind.PLAN,
                            text = "",
                            planEntries = entries,
                        ),
                    )
                }
            }

        val updatedContent =
            newSegments
                .filter { it.kind == AssistantSegment.Kind.MESSAGE }
                .joinToString("") { it.text }

        mutableMessages[targetIndex] =
            message.copy(
                segments = newSegments,
                content = updatedContent,
                isStreaming = true,
            )
        return mutableMessages
    }

    private fun upsertToolCall(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolCallStarted,
    ): ReducerResult {
        val toolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }

        // Idempotent: a ToolCallStarted with an id already known to the index (replay,
        // out-of-order bootstrap from updateToolCall) must not duplicate the segment
        val existing = toolCallIndex[toolCallId]
        if (existing != null) {
            return ReducerResult(messages, toolCallIndex)
        }

        val mutableMessages = messages.toMutableList()
        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT && lastMessage.isStreaming) {
                // Attach to the actively streaming bubble rather than creating a new message
                mutableMessages.lastIndex
            } else {
                mutableMessages.add(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]
        val newSegmentIndex = message.segments.size

        val newSegment =
            AssistantSegment(
                id = UUID.randomUUID().toString(),
                kind = AssistantSegment.Kind.TOOL_CALL,
                toolCall =
                    ToolCallDisplay(
                        toolCallId = toolCallId,
                        title = event.title.ifBlank { "Tool Call" },
                        kind = event.kind,
                        status = event.status,
                        rawInput = event.rawInput,
                    ),
            )
        val newSegments = message.segments.adding(newSegment)
        mutableMessages[targetIndex] = message.copy(segments = newSegments)
        val newIndex = toolCallIndex + (toolCallId to ToolCallLocation(targetIndex, newSegmentIndex))
        return ReducerResult(mutableMessages, newIndex)
    }

    private fun updateToolCall(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolCallUpdated,
    ): ReducerResult {
        val incomingToolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }
        val location = toolCallIndex[incomingToolCallId]
        if (location == null) {
            // Out-of-order event: ToolCallUpdated arrived before ToolCallStarted. Bootstrap a
            // ToolCallStarted entry first and then apply the update on top. The bootstrap
            // populates the index, so the recursive call resolves in O(1).
            val bootstrapped =
                upsertToolCall(
                    messages,
                    toolCallIndex,
                    AppSessionEvent.ToolCallStarted(
                        toolCallId = incomingToolCallId,
                        title = event.title ?: "Tool Call",
                        kind = event.kind,
                        status = event.status,
                        rawInput = event.rawInput,
                    ),
                )
            return updateToolCall(
                bootstrapped.messages,
                bootstrapped.toolCallIndex,
                event.copy(toolCallId = incomingToolCallId),
            )
        }

        val messageIndex = location.messageIndex
        val segmentIndex = location.segmentIndex
        val mutableMessages = messages.toMutableList()
        val message = mutableMessages[messageIndex]

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                val oldSegment = segments[segmentIndex]
                val oldToolCall = oldSegment.toolCall
                segments[segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall?.copy(
                                content = event.content ?: oldToolCall.content,
                                status = event.status ?: oldToolCall.status,
                                title = event.title ?: oldToolCall.title.ifBlank { "Tool Call" },
                                kind = event.kind ?: oldToolCall.kind,
                                rawInput = event.rawInput ?: oldToolCall.rawInput,
                                rawOutput = event.rawOutput ?: oldToolCall.rawOutput,
                            ),
                    )
            }
        mutableMessages[messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages, toolCallIndex)
    }

    private fun updateToolCallPermission(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolPermissionRequested,
    ): ReducerResult {
        // Ensure the tool call exists. Permission requests can arrive before the
        // corresponding ToolCallStarted (e.g. when a permission UI is shown immediately).
        val bootstrapped =
            if (toolCallIndex.containsKey(event.toolCallId)) {
                ReducerResult(messages, toolCallIndex)
            } else {
                upsertToolCall(
                    messages,
                    toolCallIndex,
                    AppSessionEvent.ToolCallStarted(
                        toolCallId = event.toolCallId,
                        title = event.title ?: "Permission Request",
                        kind = ToolKind.OTHER,
                        status = ToolCallStatus.PENDING,
                    ),
                )
            }

        val location = bootstrapped.toolCallIndex[event.toolCallId] ?: return bootstrapped
        val mutableMessages = bootstrapped.messages.toMutableList()
        val message = mutableMessages[location.messageIndex]
        val oldSegment = message.segments[location.segmentIndex]
        val oldToolCall = oldSegment.toolCall ?: return bootstrapped

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                segments[location.segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall.copy(
                                title = oldToolCall.title.ifBlank { event.title ?: "Permission Request" },
                                status = ToolCallStatus.PENDING,
                                permissionRequestId = event.requestId,
                                permissionOptions =
                                    event.options.map {
                                        AcpPermissionOption(
                                            id = it.id,
                                            label = it.label,
                                            kind = it.kind ?: "unknown",
                                        )
                                    },
                            ),
                    )
            }
        mutableMessages[location.messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages, bootstrapped.toolCallIndex)
    }

    private fun clearToolCallPermission(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        toolCallId: String,
    ): ReducerResult {
        val location = toolCallIndex[toolCallId] ?: return ReducerResult(messages, toolCallIndex)
        val mutableMessages = messages.toMutableList()
        val message = mutableMessages[location.messageIndex]
        val oldSegment = message.segments[location.segmentIndex]
        val oldToolCall = oldSegment.toolCall ?: return ReducerResult(messages, toolCallIndex)

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                segments[location.segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall.copy(
                                permissionOptions = null,
                                permissionRequestId = null,
                                // A PENDING tool call that had a permission request transitions to
                                // IN_PROGRESS once permission is granted (or denied)
                                status =
                                    if (oldToolCall.status == ToolCallStatus.PENDING &&
                                        oldToolCall.permissionRequestId != null
                                    ) {
                                        ToolCallStatus.IN_PROGRESS
                                    } else {
                                        oldToolCall.status
                                    },
                            ),
                    )
            }
        mutableMessages[location.messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages, toolCallIndex)
    }

    fun finishStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        // TurnComplete can arrive for the last streaming message in any position (the order of
        // prompt events is not guaranteed to be sequential)
        val index = messages.indexOfLast { it.isStreaming }
        if (index == -1) return messages

        val mutableMessages = messages.toMutableList()
        val message = mutableMessages[index]
        mutableMessages[index] = message.copy(isStreaming = false)
        return mutableMessages
    }
}
