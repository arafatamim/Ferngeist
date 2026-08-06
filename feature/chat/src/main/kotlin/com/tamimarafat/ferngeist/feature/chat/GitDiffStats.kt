package com.tamimarafat.ferngeist.feature.chat

/**
 * Line-based diff statistics for the gateway working tree, matching the
 * "+N" / "-M" semantics of the edit tool-call diff summary (git diff line counts).
 *
 * The values come straight from the gateway's git status response, where each
 * changed file carries `added`/`removed` counts from `git diff --numstat`
 * (untracked files counted from disk by the gateway).
 */
data class GitDiffStats(
    val additions: Int,
    val deletions: Int,
)
