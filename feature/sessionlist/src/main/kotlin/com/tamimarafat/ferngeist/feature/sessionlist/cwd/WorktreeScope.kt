package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.cwdKey
import com.tamimarafat.ferngeist.core.model.isSameCwd
import com.tamimarafat.ferngeist.gateway.GatewayWorktree

/** Which part of the chosen repo the session list shows, and where New chat starts. */
sealed interface WorktreeScope {
    /** Every chat of the repo, worktrees included; New chat starts in the repo itself. */
    data object All : WorktreeScope

    /** Only chats in the repo's own checkout. */
    data object Main : WorktreeScope

    /** Chats in the worktree at [path] or below it. */
    data class Worktree(
        val path: String,
    ) : WorktreeScope
}

/** A scope picked for [repo]; it means nothing once the repo filter moves elsewhere. */
internal data class ScopeSelection(
    val repo: String,
    val scope: WorktreeScope,
)

/** The scope in force for the filter [cwd]: the picked one, or [WorktreeScope.All] for another repo. */
internal fun effectiveScope(
    selection: ScopeSelection?,
    cwd: String?,
): WorktreeScope =
    if (selection != null && !cwd.isNullOrBlank() && isSameCwd(selection.repo, cwd)) {
        selection.scope
    } else {
        WorktreeScope.All
    }

/**
 * The chats of [repo]: those in it or under its `.worktrees` folder, plus those in any of
 * [worktreePaths] — a worktree made by git or another tool can live anywhere, so its chats
 * would otherwise drop out of the repo.
 */
internal fun sessionsInRepo(
    sessions: List<SessionSummary>,
    repo: String?,
    worktreePaths: List<String>,
): List<SessionSummary> {
    val inRepo = filterSessionsByCwd(sessions, repo)
    if (repo.isNullOrBlank() || worktreePaths.isEmpty()) return inRepo
    val ids = inRepo.mapTo(HashSet()) { it.id }
    return sessions.filter { s ->
        s.id in ids || worktreePaths.any { path -> s.cwd?.let { isInside(it, path) } == true }
    }
}

/** A worktree [path] of the git repo [repo]. */
internal data class WorktreeLocation(
    val repo: String,
    val path: String,
)

/**
 * The worktree [cwd] sits in, or null when it is in none we know of: one the gateway lists, or
 * one under a repo's `.worktrees` folder.
 */
internal fun worktreeOf(
    cwd: String,
    managed: List<GatewayWorktree>,
): WorktreeLocation? {
    managed.firstOrNull { isInside(cwd, it.path) }?.let { return WorktreeLocation(it.repo, it.path) }
    val root = worktreeRepoOf(cwd) ?: return null
    val nameStart = root.length + WORKTREES_SEGMENT.length
    val nameEnd = cwd.replace('\\', '/').indexOf('/', nameStart).takeIf { it >= 0 } ?: cwd.length
    return WorktreeLocation(root, cwd.substring(0, nameEnd))
}

/** The project [cwd] belongs to: the repo of the worktree it sits in, else [cwd] itself. */
internal fun projectRoot(
    cwd: String,
    managed: List<GatewayWorktree>,
): String = worktreeOf(cwd, managed)?.repo ?: cwd

/**
 * Every project [sessions] and [recentCwds] ran in, most recently used chats first.
 *
 * A project whose folder [missing] lists is left out: recents outlive the folders they name,
 * and a chat's old checkout can be gone while its session is still cached. A worktree that is
 * gone still folds into its repo, so the repo stays as long as it exists.
 */
internal fun knownProjects(
    sessions: List<SessionSummary>,
    recentCwds: List<String>,
    managed: List<GatewayWorktree>,
    missing: Set<String> = emptySet(),
): List<String> =
    (sessions.sortedByDescending { it.updatedAt ?: 0L }.mapNotNull { it.cwd } + recentCwds)
        .filter { it.isNotBlank() }
        .map { projectRoot(it, managed) }
        .filter { root -> missing.none { isInside(root, it) } }
        .distinctBy(::cwdKey)

/** Narrows the repo's [sessions] to [scope]. */
internal fun filterSessionsByScope(
    sessions: List<SessionSummary>,
    repo: String?,
    scope: WorktreeScope,
): List<SessionSummary> =
    when (scope) {
        WorktreeScope.All -> sessions
        WorktreeScope.Main -> sessions.filter { s -> s.cwd?.let { isSameCwd(it, repo.orEmpty()) } == true }
        is WorktreeScope.Worktree -> sessions.filter { s -> s.cwd?.let { isInside(it, scope.path) } == true }
    }

/** Where a new chat starts for [scope] in [repo]. */
internal fun scopeCwd(
    repo: String,
    scope: WorktreeScope,
): String = if (scope is WorktreeScope.Worktree) scope.path else repo

/** True when [cwd] is [dir] or a subdirectory of it; `/r/a` is not inside `/r/ab`. */
internal fun isInside(
    cwd: String,
    dir: String,
): Boolean {
    val c = cwdKey(cwd)
    val d = cwdKey(dir)
    return c == d || c.startsWith("$d/")
}

/** One worktree the scope menu offers, with how many chats run in it. */
internal data class ScopeOption(
    val worktree: GatewayWorktree,
    val chats: Int,
) {
    val path: String get() = worktree.path
    val branch: String get() = worktree.displayName
}

/** The branch, or the folder's name on a detached `HEAD`. */
internal val GatewayWorktree.displayName: String
    get() = branch.ifBlank { path.replace('\\', '/').trimEnd('/').substringAfterLast('/') }

/** The repo's worktrees for the scope menu, with how many of [sessions] run in each. */
internal fun worktreeScopeOptions(
    repo: String,
    worktrees: List<GatewayWorktree>,
    sessions: List<SessionSummary>,
): List<ScopeOption> =
    worktrees
        .filter { isSameCwd(it.repo, repo) }
        .map { ScopeOption(it, filterSessionsByScope(sessions, repo, WorktreeScope.Worktree(it.path)).size) }
