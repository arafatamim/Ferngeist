package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonElement
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role = Role.USER,
    val content: String = "",
    val segments: PersistentList<AssistantSegment> = persistentListOf(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val images: List<ChatImageData> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM,
    }
}

data class AssistantSegment(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.MESSAGE,
    val text: String = "",
    val toolCall: ToolCallDisplay? = null,
    val planEntries: List<PlanEntry>? = null,
) {
    enum class Kind {
        MESSAGE,
        THOUGHT,
        TOOL_CALL,
        PLAN,
    }
}

data class ToolCallDisplay(
    val toolCallId: String? = null,
    val title: String = "",
    val kind: ToolKind? = null,
    val status: ToolCallStatus? = null,
    val content: List<ToolCallContent>? = null,
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
    val permissionOptions: List<AcpPermissionOption>? = null,
    val permissionRequestId: String? = null,
)

data class AcpPermissionOption(
    val id: String,
    val label: String,
    val kind: String,
)

data class ChatImageData(
    val base64: String,
    val mimeType: String = "image/jpeg",
)
