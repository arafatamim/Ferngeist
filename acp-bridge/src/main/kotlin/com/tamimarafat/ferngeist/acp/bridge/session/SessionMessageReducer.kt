package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import java.util.UUID

object SessionMessageReducer {
    fun handleEvent(
        messages: List<ChatMessage>,
        event: AppSessionEvent,
    ): List<ChatMessage> =
        when (event) {
            is AppSessionEvent.UserMessage -> appendUserText(messages, event.text, event.append, event.timestampMs)
            is AppSessionEvent.AgentMessage ->
                appendText(
                    messages,
                    event.text,
                    AssistantSegment.Kind.MESSAGE,
                    event.timestampMs,
                )
            is AppSessionEvent.AgentThought ->
                appendText(
                    messages,
                    event.text,
                    AssistantSegment.Kind.THOUGHT,
                    event.timestampMs,
                )
            is AppSessionEvent.ToolCallStarted -> upsertToolCall(messages, event)
            is AppSessionEvent.ToolCallUpdated -> updateToolCall(messages, event)
            is AppSessionEvent.ToolPermissionRequested -> updateToolCallPermission(messages, event)
            is AppSessionEvent.ToolPermissionResolved -> clearToolCallPermission(messages, event.toolCallId)
            is AppSessionEvent.PlanUpdated ->
                appendText(
                    messages,
                    event.content,
                    AssistantSegment.Kind.PLAN,
                    event.timestampMs,
                )
            is AppSessionEvent.TurnComplete -> finishStreaming(messages)
            else -> messages
        }

    fun startStreaming(messages: List<ChatMessage>): List<ChatMessage> {
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

        if (append) {
            if (lastMessage?.role == ChatMessage.Role.USER) {
                if (lastMessage.content == text) return messages
                mutableMessages[mutableMessages.lastIndex] =
                    lastMessage.copy(content = lastMessage.content + text)
                return mutableMessages
            }

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
            if (lastMessage?.role == ChatMessage.Role.USER && lastMessage.content == text) {
                return messages
            }
        }

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
        text: String,
        kind: AssistantSegment.Kind,
        timestampMs: Long?,
    ): List<ChatMessage> {
        if (text.isEmpty()) return messages
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
        val segments = message.segments.toMutableList()

        val textSegmentIndex =
            if (
                segments.isNotEmpty() &&
                segments.last().kind == kind &&
                segments.last().toolCall == null
            ) {
                segments.lastIndex
            } else {
                -1
            }

        if (textSegmentIndex != -1) {
            val existing = segments[textSegmentIndex]
            segments[textSegmentIndex] = existing.copy(text = existing.text + text)
        } else {
            segments.add(AssistantSegment(id = UUID.randomUUID().toString(), kind = kind, text = text))
        }

        val updatedContent =
            segments
                .filter { it.kind == AssistantSegment.Kind.MESSAGE }
                .joinToString("") { it.text }

        mutableMessages[targetIndex] =
            message.copy(
                segments = segments,
                content = updatedContent,
                isStreaming = true,
            )
        return mutableMessages
    }

    private fun upsertToolCall(
        messages: List<ChatMessage>,
        event: AppSessionEvent.ToolCallStarted,
    ): List<ChatMessage> {
        val mutableMessages = messages.toMutableList()
        val toolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }

        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT && lastMessage.isStreaming) {
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
        val segments = message.segments.toMutableList()

        if (segments.any { it.kind == AssistantSegment.Kind.TOOL_CALL && it.toolCall?.toolCallId == toolCallId }) {
            return messages
        }

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
                    ),
            )
        segments.add(newSegment)

        mutableMessages[targetIndex] = message.copy(segments = segments)
        return mutableMessages
    }

    private fun updateToolCall(
        messages: List<ChatMessage>,
        event: AppSessionEvent.ToolCallUpdated,
    ): List<ChatMessage> {
        val incomingToolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }
        val index =
            messages.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT &&
                    it.segments.any { segment -> segment.toolCall?.toolCallId == incomingToolCallId }
            }
        if (index == -1) {
            return upsertToolCall(
                messages,
                AppSessionEvent.ToolCallStarted(
                    toolCallId = incomingToolCallId,
                    title = event.title ?: "Tool Call",
                    kind = event.kind,
                    status = event.status,
                ),
            ).let { withTool ->
                updateToolCall(withTool, event.copy(toolCallId = incomingToolCallId))
            }
        }

        val mutableMessages = messages.toMutableList()
        val message = mutableMessages[index]
        val segments = message.segments.toMutableList()

        val segmentIndex =
            segments.indexOfLast {
                it.kind == AssistantSegment.Kind.TOOL_CALL &&
                    it.toolCall?.toolCallId == incomingToolCallId
            }
        if (segmentIndex != -1) {
            val oldSegment = segments[segmentIndex]
            val oldToolCall = oldSegment.toolCall
            segments[segmentIndex] =
                oldSegment.copy(
                    toolCall =
                        oldToolCall?.copy(
                            status = event.status ?: oldToolCall.status,
                            title = event.title ?: oldToolCall.title.ifBlank { "Tool Call" },
                            kind = event.kind ?: oldToolCall.kind,
                            output = event.output ?: event.rawOutput ?: oldToolCall.output,
                            rawOutput = event.rawOutput ?: oldToolCall.rawOutput,
                        ),
                )
        }

        mutableMessages[index] = message.copy(segments = segments)
        return mutableMessages
    }

    private fun updateToolCallPermission(
        messages: List<ChatMessage>,
        event: AppSessionEvent.ToolPermissionRequested,
    ): List<ChatMessage> {
        val withTool =
            upsertToolCall(
                messages,
                AppSessionEvent.ToolCallStarted(
                    toolCallId = event.toolCallId,
                    title = event.title ?: "Permission Request",
                    kind = "permission",
                    status = "awaiting_permission",
                ),
            )
        val index =
            withTool.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT &&
                    it.segments.any { segment -> segment.toolCall?.toolCallId == event.toolCallId }
            }
        if (index == -1) return withTool

        val mutable = withTool.toMutableList()
        val message = mutable[index]
        val segments = message.segments.toMutableList()
        val segmentIndex =
            segments.indexOfLast {
                it.kind == AssistantSegment.Kind.TOOL_CALL && it.toolCall?.toolCallId == event.toolCallId
            }
        if (segmentIndex == -1) return withTool
        val oldSegment = segments[segmentIndex]
        val oldToolCall = oldSegment.toolCall ?: return withTool
        segments[segmentIndex] =
            oldSegment.copy(
                toolCall =
                    oldToolCall.copy(
                        title = oldToolCall.title.ifBlank { event.title ?: "Permission Request" },
                        status = "awaiting_permission",
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
        mutable[index] = message.copy(segments = segments)
        return mutable
    }

    private fun clearToolCallPermission(
        messages: List<ChatMessage>,
        toolCallId: String,
    ): List<ChatMessage> {
        val index =
            messages.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT &&
                    it.segments.any { segment -> segment.toolCall?.toolCallId == toolCallId }
            }
        if (index == -1) return messages
        val mutable = messages.toMutableList()
        val message = mutable[index]
        val segments =
            message.segments.map { segment ->
                if (segment.kind == AssistantSegment.Kind.TOOL_CALL && segment.toolCall?.toolCallId == toolCallId) {
                    val oldToolCall = segment.toolCall
                    segment.copy(
                        toolCall =
                            oldToolCall?.copy(
                                permissionOptions = null,
                                permissionRequestId = null,
                                status =
                                    if (oldToolCall.status ==
                                        "awaiting_permission"
                                    ) {
                                        "running"
                                    } else {
                                        oldToolCall.status
                                    },
                            ),
                    )
                } else {
                    segment
                }
            }
        mutable[index] = message.copy(segments = segments)
        return mutable
    }

    fun finishStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        val index = messages.indexOfLast { it.isStreaming }
        if (index == -1) return messages

        val mutableMessages = messages.toMutableList()
        val message = mutableMessages[index]
        mutableMessages[index] = message.copy(isStreaming = false)
        return mutableMessages
    }
}
