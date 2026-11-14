package com.tamimarafat.ferngeist.core.model

import kotlinx.serialization.Serializable

/**
 * Durable form of a prompt that has been queued but not yet delivered to the agent.
 *
 * Mirrors the in-memory offline-queue entry (client id, text, attachments) so a
 * prompt typed while offline survives the chat screen's view model being cleared
 * and can be restored as a [ChatMessage] with [MessageDeliveryStatus.QUEUED].
 * [dispatched] marks one handed to the transport whose turn had not ended: it may have
 * reached the agent, so a restore drops it once the transcript confirms delivery, resends it
 * only to a gateway that refuses repeats, and otherwise leaves the resend to the user.
 * Records saved before the flag existed decode as queued.
 */
@Serializable
data class QueuedPromptRecord(
    val clientId: String,
    val text: String,
    val images: List<ChatImageData> = emptyList(),
    val files: List<ChatFileData> = emptyList(),
    val dispatched: Boolean = false,
    val audios: List<ChatAudioData> = emptyList(),
    val resourceLinks: List<ChatPromptResourceLink> = emptyList(),
)
