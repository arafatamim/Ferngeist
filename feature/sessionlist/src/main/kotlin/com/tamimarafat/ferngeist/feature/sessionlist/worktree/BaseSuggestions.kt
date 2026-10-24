package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import com.tamimarafat.ferngeist.gateway.GatewayGitRef

private const val MAX_BASE_SUGGESTIONS = 8
private val kindOrder = listOf("branch", "remote", "tag")

/**
 * Refs offered under the custom base field: names containing [query] (any case), branches then
 * remotes then tags, without the detached `head` or anything already offered as a chip.
 */
internal fun baseSuggestions(
    refs: List<GatewayGitRef>,
    query: String,
    offered: Collection<String>,
): List<String> {
    val needle = query.trim()
    return refs
        .filter { it.kind in kindOrder && it.name !in offered && it.name.contains(needle, ignoreCase = true) }
        .sortedBy { kindOrder.indexOf(it.kind) }
        .map { it.name }
        .distinct()
        .take(MAX_BASE_SUGGESTIONS)
}
