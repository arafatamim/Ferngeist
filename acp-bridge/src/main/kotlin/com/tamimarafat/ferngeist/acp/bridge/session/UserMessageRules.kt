package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

internal fun appendUserText(
    messages: List<ChatMessage>,
    content: UserContent,
    append: Boolean,
    timestampMs: Long?,
): List<ChatMessage> {
    if (content.text.isEmpty() && content.images.isEmpty() && content.files.isEmpty()) return messages
    val mutableMessages = messages.mutableCopy()
    val lastMessage = mutableMessages.lastOrNull()

    // Dedup against the already-present user bubble (or the streaming placeholder that
    // follows it) before appending a new one. Returns non-null when the caller should
    // use the returned list instead of appending.
    deduplicateOrMergeUserMessage(mutableMessages, lastMessage, content, append)?.let {
        return it.toPersistentList()
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
            content = content.text,
            images = content.images,
            files = content.files,
            createdAt = timestampMs ?: System.currentTimeMillis(),
        ),
    )
    return mutableMessages.build()
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
    if (append && lastMessage?.role == ChatMessage.Role.USER) {
        // Exact-match dedup when the last message is already a USER bubble.
        // Image chunks arrive as separate UserMessageChunks with empty text — merge
        // images even when the text is empty or unchanged.
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
 * chunk should be appended as a new message. Merges when content/images/files differ,
 * and returns the unchanged list on an exact duplicate.
 */
internal fun mergeIntoUserBubble(
    mutableMessages: MutableList<ChatMessage>,
    lastMessage: ChatMessage,
    content: UserContent,
): List<ChatMessage> {
    val dedup = lastMessage.content == content.text && content.images.isEmpty() && content.files.isEmpty()
    if (dedup) return mutableMessages
    mutableMessages[mutableMessages.lastIndex] =
        lastMessage.copy(
            content = lastMessage.content + content.text,
            images = lastMessage.images + content.images,
            files = lastMessage.files + content.files,
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
