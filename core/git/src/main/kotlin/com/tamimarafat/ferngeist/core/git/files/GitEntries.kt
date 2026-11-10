package com.tamimarafat.ferngeist.core.git.files

import com.tamimarafat.ferngeist.core.git.ui.diff.DiffStats
import com.tamimarafat.ferngeist.core.model.ChatChangedFile

/**
 * Git's entry for [path], from the working tree as it is now. [path] is usually absolute and git's
 * relative to the repo root, so a suffix on a path boundary is a match; the longest such entry wins,
 * so `src/X.kt` is not mistaken for a root `X.kt`.
 */
fun List<ChatChangedFile>.entryFor(path: String): ChatChangedFile? = matching(path, dirs = false)

/** Git's counts for [path]; see [entryFor] for how it is matched. Unlike it, a directory entry counts. */
fun List<ChatChangedFile>.statsFor(path: String): DiffStats? =
    matching(path, dirs = true)?.let { DiffStats(additions = it.added, deletions = it.removed) }

private fun List<ChatChangedFile>.matching(
    path: String,
    dirs: Boolean,
): ChatChangedFile? {
    val normalized = path.replace('\\', '/')
    return filter {
        !it.binary && (dirs || !it.isDir) && (normalized == it.path || normalized.endsWith("/" + it.path))
    }.maxByOrNull { it.path.length }
}
