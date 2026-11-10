package com.tamimarafat.ferngeist.feature.sessionlist.git

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.core.model.git.gitTarget
import com.tamimarafat.ferngeist.feature.sessionlist.cwd.WorktreeScope

/** A list must identify one checkout on one gateway before it can show Git. */
internal fun scopedGitTarget(
    gateway: GatewaySource?,
    repo: String?,
    scope: WorktreeScope,
): ProjectGitTarget? {
    if (gateway == null || repo.isNullOrBlank()) return null
    val cwd =
        when (scope) {
            WorktreeScope.All, WorktreeScope.Main -> repo
            is WorktreeScope.Worktree -> scope.path.takeIf { it.isNotBlank() } ?: return null
        }
    return gateway.gitTarget(cwd)
}
