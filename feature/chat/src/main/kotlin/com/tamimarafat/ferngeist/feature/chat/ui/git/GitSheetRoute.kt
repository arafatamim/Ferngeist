package com.tamimarafat.ferngeist.feature.chat.ui.git

import androidx.compose.runtime.saveable.Saver

/** Where the git sheet is. The parent is derived from the route itself, so there is no back stack. */
internal sealed interface GitSheetRoute {
    val depth: Int
    val parent: GitSheetRoute?

    /** The commit this route shows or reads from; null on the working-tree side. */
    val commitSha: String? get() = (this as? Commit)?.sha ?: (this as? FileDiff)?.commit

    /** Keys the page's saved scroll; data-class text is unique per route and fits a Bundle. */
    val stateKey: String get() = toString()

    data object Changes : GitSheetRoute {
        override val depth = 0
        override val parent = null
    }

    data object History : GitSheetRoute {
        override val depth = 0
        override val parent = null
    }

    data class Commit(
        val sha: String,
    ) : GitSheetRoute {
        override val depth = 1
        override val parent get() = History
    }

    /** A working-tree file when [commit] is null, else a file of that commit. */
    data class FileDiff(
        val path: String,
        val commit: String?,
        val oldPath: String?,
    ) : GitSheetRoute {
        override val depth = 2
        override val parent get() = commit?.let(::Commit) ?: Changes
    }
}

internal val gitSheetRouteSaver =
    Saver<GitSheetRoute, List<String?>>(
        save = { route ->
            when (route) {
                GitSheetRoute.Changes -> listOf("changes")
                GitSheetRoute.History -> listOf("history")
                is GitSheetRoute.Commit -> listOf("commit", route.sha)
                is GitSheetRoute.FileDiff -> listOf("diff", route.path, route.commit, route.oldPath)
            }
        },
        restore = { saved ->
            when (saved[0]) {
                "history" -> GitSheetRoute.History
                "commit" -> GitSheetRoute.Commit(checkNotNull(saved[1]))
                "diff" -> GitSheetRoute.FileDiff(checkNotNull(saved[1]), saved[2], saved[3])
                else -> GitSheetRoute.Changes
            }
        },
    )
