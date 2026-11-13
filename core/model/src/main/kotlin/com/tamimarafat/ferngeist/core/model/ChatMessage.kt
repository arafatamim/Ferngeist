package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.serialization.PersistentListSerializer
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role = Role.USER,
    val content: String = "",
    @Serializable(with = PersistentListSerializer::class)
    val segments: PersistentList<AssistantSegment> = persistentListOf(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val images: List<ChatImageData> = emptyList(),
    val files: List<ChatFileData> = emptyList(),
    /** Host files a user prompt linked rather than inlined (ACP `resource_link`). */
    val links: List<ChatResourceLink> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val status: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
    val clientId: String? = null,
    /**
     * Proof this prompt reached the agent: its turn's reply, or the gateway's numbered echo of it.
     * A local copy carries [clientId] too, so the id alone proves nothing.
     */
    val deliveryConfirmed: Boolean = false,
    /** When the turn this reply answers was sent and finished; on a reply once it closes. */
    val turn: TurnTiming? = null,
    /** ACP message id that identifies which streamed message this bubble belongs to. */
    val messageId: String? = null,
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM,
    }
}

/** Wall-clock times a turn ran between: its prompt sent, its reply closed. */
@Serializable
data class TurnTiming(
    val startedAt: Long,
    val finishedAt: Long,
)

/**
 * A turn's place in its session: the nth prompt, and a hash of that prompt's text. Replayed
 * history carries new message ids, so a turn is found again by position, and the hash keeps a
 * replay that numbers its prompts differently from claiming another turn's time.
 */
data class TurnKey(
    val index: Int,
    val promptHash: Int,
)

@Serializable
data class AssistantSegment(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.MESSAGE,
    val text: String = "",
    val toolCall: ToolCallDisplay? = null,
    val planEntries: List<PlanEntry>? = null,
    /** The host file a [Kind.RESOURCE_LINK] segment points at. */
    val resourceLink: ChatResourceLink? = null,
    /** The picture an [Kind.IMAGE] segment carries. */
    val image: ChatImageData? = null,
    /** `elapsedRealtime` the segment arrived at; null for hydrated history. */
    val receivedAtMs: Long? = null,
) {
    enum class Kind {
        MESSAGE,
        THOUGHT,
        TOOL_CALL,
        PLAN,
        RESOURCE_LINK,
        IMAGE,
    }
}

/**
 * A host file an ACP `resource_link` names, at its absolute [path], with what the agent said of it.
 * [mimeType] and [size] are the agent's word and may be stale; the host's own stat replaces them.
 */
@Serializable
data class ChatResourceLink(
    val path: String,
    val name: String,
    val mimeType: String? = null,
    val size: Long? = null,
)

@Serializable
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
    /** Files the agent says the call touches (ACP `locations`), as paths. */
    val locations: List<String>? = null,
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

@Serializable
data class ChatFileData(
    val name: String,
    val base64: String,
    val mimeType: String,
    val sizeBytes: Long,
)
