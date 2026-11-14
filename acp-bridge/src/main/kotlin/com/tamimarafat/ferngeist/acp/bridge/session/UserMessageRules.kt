package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.content
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

internal fun appendUserText(
    received: List<ChatMessage>,
    content: UserContent,
    append: Boolean,
    timestampMs: Long?,
): List<ChatMessage> {
    if (content.text.isEmpty() && !content.hasAttachments) return received
    // Numbered echoes identify their optimistic prompt even while a reply placeholder follows it.
    applyPromptEcho(received, content)?.let { return it }
    val messages = received
    val mutableMessages = messages.mutableCopy()
    val lastMessage = mutableMessages.lastOrNull()

    // A new ACP message id is a hard message boundary, but an absent id keeps the
    // previous merge behavior. An id on an as-yet-unidentified local/optimistic bubble
    // claims that bubble instead of duplicating it.
    if (!mutableMessages.startsNewMessage(content.messageId)) {
        // Dedup against the already-present user bubble (or the streaming placeholder that
        // follows it) before appending a new one. Returns non-null when the caller should
        // use the returned list instead of appending.
        deduplicateOrMergeUserMessage(mutableMessages, lastMessage, content, append)?.let {
            return it.toPersistentList().withMessageId(content.messageId)
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

    mutableMessages.add(
        ChatMessage(
            role = ChatMessage.Role.USER,
            contentBlocks = content.blocks,
            createdAt = timestampMs ?: System.currentTimeMillis(),
            messageId = content.messageId,
            clientId = content.clientId,
            deliveryConfirmed = content.clientId != null,
            echoStarted = true,
        ),
    )
    return mutableMessages.build()
}

/** The first echo replaces optimistic content; later chunks append without value-based deduplication. */
private fun applyPromptEcho(
    received: List<ChatMessage>,
    content: UserContent,
): List<ChatMessage>? {
    val clientId = content.clientId
    val index = received.indexOfLast { clientId != null && it.clientId == clientId }
    if (index < 0) return null
    val last = received.last()
    val followedByPlaceholder =
        index == received.lastIndex - 1 &&
            last.role == ChatMessage.Role.ASSISTANT &&
            last.isStreaming &&
            last.segments.isEmpty()
    if (index != received.lastIndex && !followedByPlaceholder) return received.confirmingDelivery(clientId)
    val prompt = received[index]
    if (content.messageId != null && prompt.messageId != null && content.messageId != prompt.messageId) return null
    val blocks = if (prompt.echoStarted) prompt.contentBlocks + content.blocks else content.blocks
    return received
        .mutableCopy()
        .apply {
            this[index] =
                prompt.copy(
                    contentBlocks = blocks,
                    messageId = prompt.messageId ?: content.messageId,
                    deliveryConfirmed = true,
                    echoStarted = true,
                )
        }.build()
}

private fun List<ChatMessage>.startsNewMessage(messageId: String?): Boolean {
    val lastUserId = lastOrNull { it.role == ChatMessage.Role.USER }?.messageId
    return messageId != null && lastUserId != null && lastUserId != messageId
}

/** Claims an unidentified user bubble for [messageId] after an echo/merge. */
private fun List<ChatMessage>.withMessageId(messageId: String?): List<ChatMessage> {
    if (messageId == null) return this
    val userIndex = indexOfLast { it.role == ChatMessage.Role.USER }
    if (userIndex == -1 || this[userIndex].messageId != null) return this
    return toMutableList().also { it[userIndex] = it[userIndex].copy(messageId = messageId) }
}

/** Marks [clientId]'s prompt as having reached the agent; no id names no prompt. */
internal fun List<ChatMessage>.confirmingDelivery(clientId: String?): List<ChatMessage> =
    if (clientId == null) {
        this
    } else {
        map { if (it.clientId == clientId && !it.deliveryConfirmed) it.copy(deliveryConfirmed = true) else it }
    }

/**
 * When [append] is true this handles the server echo path: exact-match dedup against a
 * USER bubble, image-merge for image chunks, and placeholder dedup. When false it handles
 * the local optimistic insertion dedup. Returns null when a new message should be appended.
 */
internal fun deduplicateOrMergeUserMessage(
    mutableMessages: MutableList<ChatMessage>,
    lastMessage: ChatMessage?,
    content: UserContent,
    append: Boolean,
): List<ChatMessage>? {
    if (append && lastMessage?.role == ChatMessage.Role.USER && lastMessage.clientId == content.clientId) {
        // A continued user bubble owns all its streamed chunks, including repeated text.
        // Another prompt's echo never merges; the first numbered local echo was handled above.
        return mergeIntoUserBubble(mutableMessages, lastMessage, content)
    }
    // append=false: local optimistic insertion (or redundant source) — dedup against the
    // last USER bubble or against a streaming placeholder whose preceding USER matches
    if (!append && lastMessage?.role == ChatMessage.Role.USER && lastMessage.content == content.text) {
        return mutableMessages
    }
    val placeholder = placeholderMatchesPreviousUser(mutableMessages, lastMessage, content.text, exact = !append)
    return mutableMessages.takeIf { placeholder }
}

/**
 * Returns the merged message list when [lastMessage] is a USER bubble, or null when the
 * chunk should be appended as a new message. Every chunk contributes its ordered blocks;
 * matching prompt text is not proof of a duplicate (repeated text is valid streamed content).
 */
internal fun mergeIntoUserBubble(
    mutableMessages: MutableList<ChatMessage>,
    lastMessage: ChatMessage,
    content: UserContent,
): List<ChatMessage> {
    mutableMessages[mutableMessages.lastIndex] =
        lastMessage.copy(
            contentBlocks = lastMessage.contentBlocks + content.blocks,
        )
    return mutableMessages
}

internal fun placeholderMatchesPreviousUser(
    mutableMessages: List<ChatMessage>,
    lastMessage: ChatMessage?,
    text: String,
    exact: Boolean,
): Boolean {
    if (mutableMessages.size < 2) return false
    val previousMessage = mutableMessages[mutableMessages.lastIndex - 1]
    return if (exact) {
        isStreamingPlaceholderWithExactPreviousUser(lastMessage, previousMessage, text)
    } else {
        isStreamingPlaceholderWithPreviousUser(lastMessage, previousMessage, text)
    }
}

/** True when [lastMessage] is an empty streaming assistant placeholder whose preceding USER
 * message starts with or equals [text] (echo dedup on the server-echo path). */
internal fun isStreamingPlaceholderWithPreviousUser(
    lastMessage: ChatMessage?,
    previousMessage: ChatMessage?,
    text: String,
): Boolean =
    lastMessage?.role == ChatMessage.Role.ASSISTANT &&
        lastMessage.isStreaming &&
        lastMessage.content.isBlank() &&
        lastMessage.segments.isEmpty() &&
        previousMessage?.role == ChatMessage.Role.USER &&
        (previousMessage.content.startsWith(text) || previousMessage.content == text)

/** True when [lastMessage] is an empty streaming assistant placeholder whose preceding USER
 * message content exactly matches [text] (echo dedup on the local-insertion path). */
internal fun isStreamingPlaceholderWithExactPreviousUser(
    lastMessage: ChatMessage?,
    previousMessage: ChatMessage?,
    text: String,
): Boolean =
    lastMessage?.role == ChatMessage.Role.ASSISTANT &&
        lastMessage.isStreaming &&
        lastMessage.content.isBlank() &&
        lastMessage.segments.isEmpty() &&
        previousMessage?.role == ChatMessage.Role.USER &&
        previousMessage.content == text

/**
 * A mutable view that shares structure with [this] list. Every event edits one message near
 * the tail, so copying the whole list per event made a `session/load` replay
 * O(events x messages) - long transcripts outran the load deadline.
 */
internal fun List<ChatMessage>.mutableCopy(): PersistentList.Builder<ChatMessage> = toPersistentList().builder()
