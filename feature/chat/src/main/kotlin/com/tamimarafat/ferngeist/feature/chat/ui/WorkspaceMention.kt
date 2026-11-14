package com.tamimarafat.ferngeist.feature.chat.ui

internal fun slashCommandQuery(
    text: String,
    cursor: Int,
): String? {
    if (!text.startsWith('/') || cursor !in 1..text.length) return null
    if (cursor < text.length && !text[cursor].isWhitespace()) return null
    val query = text.substring(1, cursor)
    return query.takeUnless { it.any(Char::isWhitespace) }
}

internal fun workspaceMentionQuery(
    text: String,
    cursor: Int,
): Pair<Int, String>? {
    if (cursor !in 0..text.length) return null
    val start = text.lastIndexOf('@', cursor - 1)
    if (start < 0 || (start > 0 && !text[start - 1].isWhitespace())) return null
    val query = text.substring(start + 1, cursor)
    if (query.any(Char::isWhitespace)) return null
    return start to query
}
