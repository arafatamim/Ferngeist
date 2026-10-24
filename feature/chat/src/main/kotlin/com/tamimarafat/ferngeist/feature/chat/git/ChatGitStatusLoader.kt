package com.tamimarafat.ferngeist.feature.chat.git

import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import com.tamimarafat.ferngeist.core.model.qualified
import com.tamimarafat.ferngeist.feature.chat.GitDiffStats
import com.tamimarafat.ferngeist.feature.chat.GitRefsState
import com.tamimarafat.ferngeist.feature.chat.GitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Reads the gateway-backed working tree's git status, the compare picker's refs and the working
 * tree against the compare ref into [GitState], keeping the in-flight request bookkeeping out of
 * the chat view model.
 */
internal class ChatGitStatusLoader(
    private val scope: CoroutineScope,
    private val facade: ChatWorkspaceReader,
    private val update: (GitState.() -> GitState) -> Unit,
    /** The ACP session this chat shows, scoping workspace calls to its cwd; null before it is minted. */
    private val acpSessionId: () -> String?,
    private val git: () -> GitState,
    /** Re-reads the turn diffs already shown; git's counts can hold still while their text changes. */
    private val onStatusRefreshed: () -> Unit,
) {
    // Chat open asks for these from several places in a burst (workspace appearing, session ready,
    // the load finishing); a status refresh costs a pair of gateway reads, so a burst becomes one
    // pair plus at most one rerun.
    private val statusRefresh = Coalesced(scope) { fetchStatus() }
    private val refsLoad = Coalesced(scope) { fetchRefs() }

    /**
     * Numbers compare-status fetches so only the newest lands. Superseded requests are left to
     * finish rather than cancelled, for the same reason as in [ChatGitHistoryLoader].
     */
    private var compareRequest = 0

    /**
     * Fetches the git status for a gateway-backed working tree and stores line-based
     * add/delete totals in [GitState.diffStats]. The gateway returns per-file
     * `added`/`removed` counts (from `git diff --numstat`, with untracked files counted
     * from disk), so the totals are git's own authoritative numbers.
     *
     * The worktree is read in the same pass and applied in the same state write: the indicator
     * draws "clean" from the status and ahead/behind from the worktree, so applying them
     * separately let a clean status land first and flash a check before the worktree replaced it.
     *
     * Errors keep the previous value so a transient poll failure does not clear the
     * indicator.
     */
    fun refresh() {
        // Nothing to scope to until create-on-arrival mints a session; sessionReady retries.
        if (acpSessionId() == null) return
        statusRefresh.run()
    }

    private suspend fun fetchStatus() {
        val (status, worktree) = fetchSnapshot()
        if (status == null) return
        val stats =
            GitDiffStats(
                additions = status.changed.sumOf { it.added },
                deletions = status.changed.sumOf { it.removed },
            )
        update {
            copy(
                status = status,
                diffStats = stats,
                // A failed read keeps the last answer: a transient fault must not make it vanish.
                worktree = worktree.getOrElse { this.worktree },
            )
        }
        onStatusRefreshed()
    }

    /** The status and the worktree requested together, so a burst still costs one pair of reads. */
    private suspend fun fetchSnapshot(): Pair<ChatGitStatus?, Result<ChatWorktree?>> =
        coroutineScope {
            val status = async { runCatching { facade.fetchGitStatus() } }
            val worktree = async { runCatching { facade.fetchWorktree() } }
            status.await().getOrNull() to worktree.await()
        }

    /**
     * Fetches the working tree against the compare ref into the compare slots only, so the
     * top-bar pill and the transcript's rows never see branch-relative data. Also called with no
     * ref selected, to retire an in-flight request. A reply is dropped when a newer request
     * superseded it, before anything else: otherwise a late `main` reply after the reader picked
     * `dev` would show as an error. A reply that does not echo the ref this request sent means the
     * gateway ignored the parameter, so there is nothing honest to render.
     */
    fun refreshCompare() {
        val request = ++compareRequest
        val ref = git().compareRef?.qualified ?: return
        if (acpSessionId() == null) return
        update { copy(isCompareStatusLoading = true, compareStatusError = null) }
        scope.launch {
            val result = runCatching { facade.fetchGitStatus(ref) }
            if (request != compareRequest) return@launch
            val status = result.getOrNull()
            val error =
                when {
                    result.isFailure -> result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: COMPARE_FAILED
                    status == null -> NO_COMPARE_WORKSPACE
                    status.compare != ref -> COMPARE_NOT_HONOURED
                    else -> null
                }
            update {
                copy(
                    compareStatus = status.takeIf { error == null },
                    isCompareStatusLoading = false,
                    compareStatusError = error,
                )
            }
        }
    }

    /** Loads the compare picker's refs; a failed reload keeps the list already held. */
    fun loadRefs() {
        if (acpSessionId() == null) return
        refsLoad.run()
    }

    private suspend fun fetchRefs() {
        runCatching { facade.fetchGitRefs() }
            .onSuccess { refs ->
                if (refs == null) return@onSuccess
                update { copy(refs = GitRefsState(default = refs.default, refs = refs.refs, isLoaded = true)) }
            }.onFailure { throwable ->
                if (throwable is GitRefsUnsupportedException) {
                    update {
                        copy(
                            refs = refs.copy(unsupported = true),
                        )
                    }
                }
            }
    }

    private companion object {
        const val COMPARE_FAILED = "Failed to load the comparison"
        const val NO_COMPARE_WORKSPACE = "No gateway workspace connection to load the comparison"
        const val COMPARE_NOT_HONOURED = "The gateway did not apply the comparison"
    }
}
