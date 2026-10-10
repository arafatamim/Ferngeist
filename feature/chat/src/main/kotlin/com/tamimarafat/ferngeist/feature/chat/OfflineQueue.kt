package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData

/**
 * A pending prompt that has been enqueued for delivery.
 *
 * [clientId] links this prompt to the [com.tamimarafat.ferngeist.core.model.ChatMessage.clientId]
 * in the UI state so status transitions can be matched back to the visible bubble.
 */
data class PendingPrompt(
    val clientId: String,
    val text: String,
    val images: List<ChatImageData> = emptyList(),
    val files: List<ChatFileData> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Ordered in-memory queue of [PendingPrompt]s that have not been successfully
 * delivered to the server.
 *
 * All mutations are synchronous and thread-safe only when called from a single
 * coroutine context (e.g., [kotlinx.coroutines.flow.MutableStateFlow.update] or
 * a confined dispatcher).  This class holds no coroutine state of its own.
 */
class OfflineQueue {
    private val prompts = ArrayDeque<PendingPrompt>()

    /** Number of prompts waiting to be flushed. */
    val size: Int
        get() = prompts.size

    val isEmpty: Boolean
        get() = prompts.isEmpty()

    /** Returns a snapshot of the waiting prompts in FIFO order. */
    fun snapshot(): List<PendingPrompt> = prompts.toList()

    /** Appends a prompt to the back of the queue. */
    fun enqueue(prompt: PendingPrompt) {
        prompts.addLast(prompt)
    }

    /** Puts a prompt back at the front, ahead of everything queued after it. */
    fun enqueueFirst(prompt: PendingPrompt) {
        prompts.addFirst(prompt)
    }

    /**
     * Folds [clientId]'s prompt and every prompt ahead of it into one, under [clientId], and puts
     * it at the front. Returns it with the client ids it swallowed, or null if [clientId] is not
     * queued.
     */
    fun foldThrough(clientId: String): Pair<PendingPrompt, Set<String>>? {
        val index = prompts.indexOfFirst { it.clientId == clientId }
        if (index < 0) return null
        val batch = List(index + 1) { prompts.removeFirst() }
        val folded =
            PendingPrompt(
                clientId = clientId,
                text = batch.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
                images = batch.flatMap { it.images },
                files = batch.flatMap { it.files },
            )
        prompts.addFirst(folded)
        return folded to (batch.mapTo(HashSet()) { it.clientId } - clientId)
    }

    /** Removes and returns the oldest prompt, or null if empty. */
    fun dequeue(): PendingPrompt? = prompts.removeFirstOrNull()

    /** Returns the oldest prompt without removing it. */
    fun peek(): PendingPrompt? = prompts.firstOrNull()

    /**
     * Removes every prompt whose [PendingPrompt.clientId] equals [clientId].
     * Returns true if at least one prompt was removed.
     */
    fun removeByClientId(clientId: String): Boolean = prompts.removeAll { it.clientId == clientId }

    /** Clears all pending prompts. */
    fun clear() {
        prompts.clear()
    }
}
