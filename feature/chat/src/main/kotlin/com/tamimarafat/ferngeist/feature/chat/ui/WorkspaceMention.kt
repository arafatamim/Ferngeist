package com.tamimarafat.ferngeist.feature.chat.ui

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

internal fun matchingWorkspaceFiles(
    paths: List<String>,
    query: String,
): List<String> =
    paths.filter { path ->
        var index = 0
        path.forEach { character ->
            if (index < query.length && character.equals(query[index], ignoreCase = true)) index++
        }
        index == query.length
    }

internal fun moveMentionSelection(
    selected: Int,
    delta: Int,
    count: Int,
): Int = (selected + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
