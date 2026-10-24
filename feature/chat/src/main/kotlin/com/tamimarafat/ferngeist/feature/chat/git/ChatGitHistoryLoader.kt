package com.tamimarafat.ferngeist.feature.chat.git

import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import com.tamimarafat.ferngeist.feature.chat.GitHistoryState
import com.tamimarafat.ferngeist.feature.chat.canLoadMore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reads commit history and a commit's file list into the chat state's [GitHistoryState]. A newer
 * request supersedes an older one by number, so a stale page or file list can never overwrite it.
 *
 * The walk is always `HEAD`'s own history; which commits belong to this chat's branch is the
 * view's business, marked against [GitHistoryState.base].
 *
 * Superseded requests are left to finish rather than cancelled: cancelling one on the main
 * thread makes Ktor's Android engine close the response stream there, which Android rejects
 * with NetworkOnMainThreadException.
 */
internal class ChatGitHistoryLoader(
    private val scope: CoroutineScope,
    private val facade: ChatWorkspaceReader,
    private val history: () -> GitHistoryState,
    private val update: (GitHistoryState.() -> GitHistoryState) -> Unit,
) {
    private var logRequest = 0
    private var commitRequest = 0

    /**
     * Page 1 when ![more], keeping the current list on screen until the reply replaces it.
     * [more] appends the next page from the pinned head, and is a no-op while any page is in
     * flight or when there is nothing more. Page 1 while page 1 is in flight is a no-op too.
     */
    fun loadLog(more: Boolean) {
        val current = history()
        if (if (more) !current.canLoadMore else current.isLoading) return
        val request = ++logRequest
        markLogLoading(more)
        scope.launch {
            val result = runCatching { fetchLog(current, more) }
            if (request != logRequest) return@launch
            result
                .onSuccess { page -> onPage(page, more) }
                .onFailure { error -> onLogFailure(error, more) }
        }
    }

    private fun markLogLoading(more: Boolean) {
        update {
            if (more) {
                copy(
                    isLoadingMore = true,
                    moreError = null,
                )
            } else {
                copy(isLoading = true, isLoadingMore = false, error = null)
            }
        }
    }

    /** Pages past the pinned head when [more], otherwise the first page from the current head. */
    private suspend fun fetchLog(
        current: GitHistoryState,
        more: Boolean,
    ): ChatGitLogPage? =
        facade.fetchGitLog(
            if (more) current.head else null,
            if (more) current.commits.size else 0,
        )

    private fun onPage(
        page: ChatGitLogPage?,
        more: Boolean,
    ) {
        if (page == null) {
            onLogFailure(IllegalStateException(NO_WORKSPACE), more)
            return
        }
        update {
            // A refresh that still meets the loaded list keeps the pages past it, so new commits
            // join the top instead of cutting the list back to one page. A rewrite starts over.
            val joinsAt =
                if (more) {
                    -1
                } else {
                    page.commits.lastOrNull()?.let { last ->
                        commits.indexOfFirst {
                            it.sha ==
                                last.sha
                        }
                    }
                        ?: -1
                }
            val tail = if (joinsAt >= 0) commits.drop(joinsAt + 1) else emptyList()
            copy(
                head = page.head,
                commits = if (more) commits + page.commits else page.commits + tail,
                hasMore = if (tail.isEmpty()) page.hasMore else hasMore,
                remoteUrl = page.remoteUrl,
                // Every page reports the whole walk, but a gateway that predates the field sends
                // none: keep the number page one read rather than dropping the badge on a later page.
                total = page.total ?: total,
                // Likewise the base, which marks this branch's own commits: a page without one
                // (an unmanaged checkout) must not unmark a list already drawn.
                base = page.base ?: base,
                isLoaded = true,
                isLoading = false,
                isLoadingMore = false,
            )
        }
    }

    private fun onLogFailure(
        error: Throwable,
        more: Boolean,
    ) {
        when {
            error is GitHistoryUnsupportedException ->
                update { copy(unsupported = true, isLoading = false, isLoadingMore = false) }
            // A rewrite collected the pinned head, so its offsets mean nothing now: start over.
            error is GitHistoryHeadGoneException -> {
                update { copy(isLoadingMore = false) }
                loadLog(more = false)
            }
            more -> update { copy(isLoadingMore = false, moreError = error.messageOr(LOG_FAILED)) }
            else -> update { copy(isLoading = false, error = error.messageOr(LOG_FAILED)) }
        }
    }

    /** Loads the files [sha] changed; its error keeps [sha] so the page can retry it. */
    fun loadCommit(sha: String) {
        val request = ++commitRequest
        update { copy(commitSha = sha, commitFiles = null, isCommitLoading = true, commitError = null) }
        scope.launch {
            val result = runCatching { facade.fetchGitCommitFiles(sha) }
            if (request != commitRequest) return@launch
            val files = result.getOrNull()
            update {
                copy(
                    commitFiles = files,
                    isCommitLoading = false,
                    commitError =
                        if (files != null) null else result.exceptionOrNull().messageOr(COMMIT_FAILED),
                )
            }
        }
    }

    private fun Throwable?.messageOr(fallback: String): String = this?.message?.takeIf { it.isNotBlank() } ?: fallback

    private companion object {
        const val NO_WORKSPACE = "No gateway workspace connection to load history"
        const val LOG_FAILED = "Failed to load history"
        const val COMMIT_FAILED = "Failed to load this commit"
    }
}
