package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatMessage

/**
 * An individual text match found during transcript search.
 *
 * @property messageId The id of the [ChatMessage] containing this match.
 * @property messageIndex The index of the message in the full message list.
 * @property matchStart Character offset (inclusive) of the match within the
 *   message's searchable text.
 * @property matchEnd Character offset (exclusive) of the match within the
 *   message's searchable text.
 */
data class TranscriptMatch(
    val messageId: String,
    val messageIndex: Int,
    val matchStart: Int,
    val matchEnd: Int,
)

/**
 * Returns the searchable plain-text representation of this message.
 *
 * For [ChatMessage.Role.ASSISTANT] messages the segment texts are joined with
 * a space separator, mirroring how they are rendered as consecutive blocks.
 * For all other roles [ChatMessage.content] is used directly.
 */
fun ChatMessage.searchableText(): String = when (role) {
    ChatMessage.Role.ASSISTANT -> segments.joinToString(" ") { it.text }
    else -> content
}

/**
 * Searches [messages] for case-insensitive occurrences of [query] and returns
 * every match in message-list order.
 *
 * Matches are never overlapping: after a match is found the scan resumes
 * immediately after the match end.
 *
 * @param messages The full list of chat messages, newest last.
 * @param query The search term.  Leading / trailing whitespace is trimmed;
 *   queries shorter than 2 characters or blank after trimming produce an
 *   empty result.
 * @return Every occurrence of [query] (case-insensitive), ordered by message
 *   index then by character offset within each message's [searchableText].
 */
fun findTranscriptMatches(
    messages: List<ChatMessage>,
    query: String,
): List<TranscriptMatch> {
    val trimmed = query.trim()
    if (trimmed.length < 2) return emptyList()

    val lowerQuery = trimmed.lowercase()
    val results = mutableListOf<TranscriptMatch>()

    messages.forEachIndexed { index, message ->
        val text = message.searchableText()
        if (text.isBlank()) return@forEachIndexed

        val lowerText = text.lowercase()
        var from = 0
        while (from <= lowerText.length - lowerQuery.length) {
            val found = lowerText.indexOf(lowerQuery, from)
            if (found == -1) break
            results.add(
                TranscriptMatch(
                    messageId = message.id,
                    messageIndex = index,
                    matchStart = found,
                    matchEnd = found + lowerQuery.length,
                ),
            )
            from = found + lowerQuery.length
        }
    }
    return results
}

/**
 * Returns the next match index with wrap-around.
 *
 * When [matchCount] is zero the result is always `0`.
 */
fun nextMatchIndex(current: Int, matchCount: Int): Int {
    if (matchCount == 0) return 0
    return (current + 1) % matchCount
}

/**
 * Returns the previous match index with wrap-around.
 *
 * When [matchCount] is zero the result is always `0`.
 */
fun previousMatchIndex(current: Int, matchCount: Int): Int {
    if (matchCount == 0) return 0
    return if (current == 0) matchCount - 1 else current - 1
}
