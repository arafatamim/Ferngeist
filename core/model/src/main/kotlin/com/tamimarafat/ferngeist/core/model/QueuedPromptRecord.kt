package com.tamimarafat.ferngeist.core.model

import kotlinx.serialization.Serializable

/**
 * Durable form of a prompt that has been queued but not yet delivered to the agent.
 *
 * Mirrors the in-memory offline-queue entry (client id, text, attachments) so a
 * prompt typed while offline survives the chat screen's view model being cleared
 * and can be restored as a [ChatMessage] with [MessageDeliveryStatus.QUEUED].
 */
@Serializable
data class QueuedPromptRecord(
    val clientId: String,
    val text: String,
    val images: List<ChatImageData> = emptyList(),
    val files: List<ChatFileData> = emptyList(),
)
