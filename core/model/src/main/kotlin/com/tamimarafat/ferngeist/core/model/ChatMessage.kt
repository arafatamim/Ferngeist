package com.tamimarafat.ferngeist.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role = Role.USER,
    val content: String = "",
    val segments: List<AssistantSegment> = emptyList(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val images: List<ChatImageData> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    @Serializable
    enum class Role {
        USER, ASSISTANT, SYSTEM
    }
}

@Serializable
data class AssistantSegment(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.MESSAGE,
    val text: String = "",
    val toolCall: ToolCallDisplay? = null,
) {
    @Serializable
    enum class Kind {
        MESSAGE, THOUGHT, TOOL_CALL, PLAN
    }
}

@Serializable
data class ToolCallDisplay(
    val toolCallId: String? = null,
    val title: String = "",
    val kind: String? = null,
    val status: String? = null,
    val output: String? = null,
    val rawOutput: String? = null,
    val permissionOptions: List<AcpPermissionOption>? = null,
    val permissionRequestId: String? = null,
)

@Serializable
data class AcpPermissionOption(
    val id: String,
    val label: String,
    val kind: String,
)

@Serializable
data class ChatImageData(
    val base64: String,
    val mimeType: String = "image/jpeg",
)
