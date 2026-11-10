package com.tamimarafat.ferngeist.core.git.state

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.git.GitDiffStats
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.auth.AuthenticationRequest

/**
 * The chat's git view: the working tree's status and counts, the sheet's single-file diff, the
 * newest turn's inline fallback diffs, and the compare and history tabs.
 */
data class GitState(
    val status: ChatGitStatus? = null,
    /** The gateway worktree this chat runs in; null when it is not one or the gateway has not said yet. */
    val worktree: ChatWorktree? = null,
    val diffStats: GitDiffStats? = null,
    val fileDiff: List<ToolCallContent.Diff>? = null,
    val fileDiffPath: String? = null,
    val isFileDiffLoading: Boolean = false,
    val fileDiffError: String? = null,
    /** The commit [fileDiff] was read from; null for the working tree. */
    val fileDiffCommit: String? = null,
    /** The qualified compare ref [fileDiff] was read against; null for the working tree or a commit. */
    val fileDiffCompare: String? = null,
    /**
     * Working-tree diffs backing the latest turn's files whose tool calls reported
     * none, keyed by git-relative path. Read only for the newest turn: git
     * describes the tree as it is now, so an older turn must never borrow them.
     */
    val turnDiffs: Map<String, List<ToolCallContent.Diff>> = emptyMap(),
    val turnDiffsLoading: Set<String> = emptySet(),
    val turnDiffsErrors: Map<String, String> = emptyMap(),
    /** Git paths already changed when the newest turn started; null when git was not known then. */
    val dirtyAtStart: Set<String>? = null,
    val history: GitHistoryState = GitHistoryState(),
    /** The branch the git sheet compares against; null is `Working tree`. Lives for this view model only. */
    val compareRef: ChatGitRef? = null,
    val refs: GitRefsState = GitRefsState(),
    /**
     * The working tree against [compareRef], kept apart from [status] and [diffStats]: those
     * feed the top-bar pill and the transcript's per-turn rows, which stay working-tree-relative.
     */
    val compareStatus: ChatGitStatus? = null,
    val isCompareStatusLoading: Boolean = false,
    val compareStatusError: String? = null,
)

/** What the compare picker lists. A failed reload keeps [refs]; [unsupported] hides the whole affordance. */
data class GitRefsState(
    /** The repo's main branch name, sorting the picker and preselected from any other branch; "" when unknown. */
    val default: String = "",
    val refs: List<ChatGitRef> = emptyList(),
    /** `/git/refs` has answered at least once. */
    val isLoaded: Boolean = false,
    val unsupported: Boolean = false,
)

/** The git sheet's History tab and the commit it has open. */
data class GitHistoryState(
    /** The sha page 1 was read from, pinned for later pages; null before then or for an empty repository. */
    val head: String? = null,
    val commits: List<ChatGitCommit> = emptyList(),
    val hasMore: Boolean = false,
    val remoteUrl: String? = null,
    /** The whole walk's size, shown as the History tab's badge; null when the gateway predates it. */
    val total: Int? = null,
    /** The commit the gateway reads this workspace against, marking the branch's own commits; null when there is none. */
    val base: String? = null,
    /** Page 1 has answered at least once, so an empty [commits] means an empty repository. */
    val isLoaded: Boolean = false,
    val authentication: AuthenticationRequest? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Page 1 failed. */
    val error: String? = null,
    /** A later page failed; the list stays and offers a retry at its end. */
    val moreError: String? = null,
    /** The gateway predates history, so the sheet hides the tab. */
    val unsupported: Boolean = false,
    val commitSha: String? = null,
    val commitFiles: List<ChatChangedFile>? = null,
    val isCommitLoading: Boolean = false,
    val commitError: String? = null,
)

/** A next page exists from a pinned head and no page is in flight. */
val GitHistoryState.canLoadMore: Boolean get() = hasMore && head != null && !isLoading && !isLoadingMore
