package com.tamimarafat.ferngeist.core.model

/** [cwd] without surrounding whitespace or trailing separators. */
fun normalizeCwd(cwd: String): String = cwd.trim().trimEnd('\\', '/')

/** True when [a] and [b] name the same directory, whatever their separators or case. */
fun isSameCwd(
    a: String,
    b: String,
): Boolean = cwdKey(a) == cwdKey(b)

/** [cwd] reduced to what [isSameCwd] compares, for keying lookups by directory. */
fun cwdKey(cwd: String): String = normalizeCwd(cwd.replace('\\', '/')).lowercase()

/** The folder names of [path], split on either separator; empty for a blank path or a bare root. */
fun pathSegments(path: String): List<String> = path.split('/', '\\').filter { it.isNotBlank() }

/** Gateway-managed worktrees live at `<repo>/.worktrees/<branch>`, so this is the join point. */
const val WORKTREES_SEGMENT = "/.worktrees/"

/** The last folder name of [path], which is what a compact label calls the project. */
fun projectName(path: String): String = pathSegments(path).lastOrNull() ?: path.trim()

/**
 * The repo a gateway worktree path belongs to, i.e. `/repo/.worktrees/feat-x` -> `/repo`.
 * Null when [cwd] is not inside a `.worktrees` folder; a relative path can never be, so a
 * match at index 0 is rejected rather than yielding an empty repo.
 */
fun worktreeRepoOf(cwd: String): String? {
    val marker = cwd.replace('\\', '/').indexOf(WORKTREES_SEGMENT, ignoreCase = true)
    return if (marker > 0) cwd.take(marker) else null
}

/** True when [cwd] is [dir] or a subdirectory of it; `/r/a` is not inside `/r/ab`. */
fun isInside(
    cwd: String,
    dir: String,
): Boolean {
    val c = cwdKey(cwd)
    val d = cwdKey(dir)
    return c == d || c.startsWith("$d/")
}

/** The branch a gateway worktree [cwd] runs, or null when [cwd] is not inside a `.worktrees` folder. */
fun worktreeBranchOf(cwd: String): String? {
    val normalized = cwd.replace('\\', '/')
    val marker = normalized.indexOf(WORKTREES_SEGMENT, ignoreCase = true)
    if (marker <= 0) return null
    val start = marker + WORKTREES_SEGMENT.length
    val end = normalized.indexOf('/', start).takeIf { it >= 0 } ?: normalized.length
    return normalized.substring(start, end).ifBlank { null }
}

/**
 * The gateway worktree [cwd] sits in, from the gateway's [worktrees] listing, or else from the
 * `<repo>/.worktrees/<branch>` convention. Null when [cwd] is in neither.
 *
 * The listing comes first because a worktree directory's own name is its branch, never the
 * repo: only the gateway says which repo the checkout belongs to.
 */
fun worktreeOf(
    cwd: String,
    worktrees: List<WorktreeRef>,
): WorktreeRef? {
    worktrees.firstOrNull { isInside(cwd, it.path) }?.let { listed ->
        return listed.copy(branch = listed.branch.ifBlank { pathSegments(listed.path).lastOrNull().orEmpty() })
    }
    val repo = worktreeRepoOf(cwd) ?: return null
    val branch = worktreeBranchOf(cwd) ?: return null
    return WorktreeRef(repo = repo, path = cwd, branch = branch)
}

/** The project [cwd] belongs to: the repo of the worktree it sits in, else [cwd] itself. */
fun projectRootOf(
    cwd: String,
    worktrees: List<WorktreeRef>,
): String = worktreeOf(cwd, worktrees)?.repo ?: cwd
