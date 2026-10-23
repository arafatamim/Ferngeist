package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the gateway-backed working tree's git status, the per-file diff behind the git sheet, and
 * the latest turn's inline fallback diffs into [ChatState], keeping the in-flight request
 * bookkeeping out of [ChatViewModel].
 */
internal class ChatGitDiffLoader(
    private val scope: CoroutineScope,
    private val facade: ChatSessionFacade,
    private val update: (ChatState.() -> ChatState) -> Unit,
    /** The ACP session this chat shows, scoping workspace calls to its cwd; null before it is minted. */
    private val acpSessionId: () -> String?,
    /** The branch the git sheet compares against; null reads the working tree as is. */
    private val compareRef: () -> ChatGitRef?,
) {
    /**
     * In-flight diff fetch. Cancelling it on a newer request (and checking it after the
     * suspend call) guarantees a stale response can never overwrite the state a newer
     * request set.
     */
    private var job: Job? = null

    // Chat open asks for these from several places in a burst (workspace appearing, session ready,
    // the load finishing); a status refresh costs a pair of gateway reads, so a burst becomes one
    // pair plus at most one rerun.
    private val statusRefresh = Coalesced(scope) { fetchStatus() }
    private val refsLoad = Coalesced(scope) { fetchRefs() }

    /**
     * The latest turn's inline fallback fetches, one per git-relative path and independent of the
     * sheet's single-file [job]. A path's in-flight fetch is replaced only by a request for a newer
     * [revision]; [turnLoaded] remembers the revision each path's stored diff was read at. Touched
     * only on [scope]'s (main) thread and without suspending between check and write, so the
     * [mutex] is not needed here.
     */
    private class TurnFetch(
        val job: Job,
        val revision: String,
    )

    private val turnInFlight = mutableMapOf<String, TurnFetch>()

    /**
     * Numbers compare-status fetches so only the newest lands. Superseded requests are left to
     * finish rather than cancelled, for the same reason as in [ChatGitHistoryLoader].
     */
    private var compareRequest = 0

    /** Each loaded path's request (old path and revision), so a status refresh can re-read it. */
    private val turnLoaded = mutableMapOf<String, Pair<String?, String>>()

    /**
     * Serializes request setup (job cancellation, state initialization, job assignment)
     * and the post-fetch identity check, so rapid intents cannot race through those steps.
     * `withLock` keeps the holder cancellable, and the identity guard inside the lock
     * retains latest-wins.
     */
    private val mutex = Mutex()

    /**
     * Fetches the git status for a gateway-backed working tree and stores line-based
     * add/delete totals in [ChatState.gitDiffStats]. The gateway returns per-file
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
                gitStatus = status,
                gitDiffStats = stats,
                // A failed read keeps the last answer: a transient fault must not make it vanish.
                worktree = worktree.getOrElse { this.worktree },
            )
        }
        // Git's counts can stay put while the text changes, so every refresh re-reads
        // the turn's diffs already shown.
        turnLoaded.toMap().forEach { (path, request) ->
            turnLoaded.remove(path)
            loadTurnFile(path, request.first, request.second)
        }
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
        val ref = compareRef()?.qualified ?: return
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
                update { copy(gitRefs = GitRefsState(default = refs.default, refs = refs.refs, isLoaded = true)) }
            }.onFailure { throwable ->
                if (throwable is GitRefsUnsupportedException) {
                    update {
                        copy(
                            gitRefs = gitRefs.copy(unsupported = true),
                        )
                    }
                }
            }
    }

    /**
     * Fetches the diff for a single file in the gateway-backed working tree, or in
     * [commit] against its first parent, and stores it in [ChatState.gitFileDiff] (the UI renders the first
     * item).
     *
     * When the chat has no gateway workspace, or the fetch throws, the requested
     * path is retained with a nonblank error so the UI can retry the same file.
     */
    suspend fun load(
        path: String,
        commit: String? = null,
        oldPath: String? = null,
    ) {
        // A commit's file is read against its own parent; only the working-tree side follows the target.
        val compare = if (commit == null) compareRef()?.qualified else null
        mutex.withLock {
            job?.cancel()
            update {
                copy(
                    gitFileDiffPath = path,
                    gitFileDiffCommit = commit,
                    gitFileDiffCompare = compare,
                    isGitFileDiffLoading = true,
                    gitFileDiffError = null,
                    gitFileDiff = null,
                )
            }
            job =
                scope.launch {
                    val currentJob = coroutineContext[Job]
                    val result = runCatching { facade.fetchGitDiff(path, commit, oldPath, compare) }
                    mutex.withLock {
                        if (currentJob != job) return@withLock
                        handleResult(result, path)
                    }
                }
        }
    }

    private fun handleResult(
        result: Result<List<ToolCallContent.Diff>?>,
        path: String,
    ) {
        result
            .onSuccess { diffs ->
                if (diffs == null) {
                    // No gateway workspace to read: keep the path so the UI can retry
                    // the same file once a runtime is up.
                    update {
                        copy(
                            gitFileDiff = null,
                            isGitFileDiffLoading = false,
                            gitFileDiffError = noWorkspaceMessage(path),
                        )
                    }
                    return@onSuccess
                }
                update {
                    copy(
                        gitFileDiff = diffs,
                        isGitFileDiffLoading = false,
                        gitFileDiffError = null,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return
                val message = failureMessage(throwable, path)
                update {
                    copy(
                        gitFileDiff = null,
                        isGitFileDiffLoading = false,
                        gitFileDiffError = message,
                    )
                }
            }
    }

    /**
     * Fetches one latest-turn file's working-tree diff into [ChatState.turnGitDiffs], for inline
     * display when its tool calls reported none. [revision] identifies the git entry the request
     * was made for (e.g. its status and counts): the same revision is never fetched twice, whether
     * answered or in flight, and a newer one replaces the stored diff, so the diff cannot go stale
     * behind counts that moved on. A failed path retries on the next call.
     */
    fun loadTurnFile(
        path: String,
        oldPath: String? = null,
        revision: String = "",
    ) {
        if (path.isBlank() || turnLoaded[path]?.second == revision) return
        val inFlight = turnInFlight[path]
        if (inFlight?.revision == revision) return
        inFlight?.job?.cancel()
        update {
            copy(
                turnGitDiffsLoading = turnGitDiffsLoading + path,
                turnGitDiffsErrors = turnGitDiffsErrors - path,
            )
        }
        // Lazy so the entry is registered before the body can run: on an immediate dispatcher the
        // body would otherwise check the map before this call had stored its job.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result = runCatching { facade.fetchGitDiff(path, null, oldPath) }
                // Replaced or cleared while fetching: a newer request owns the path's state.
                if (turnInFlight[path]?.job !== coroutineContext[Job]) return@launch
                turnInFlight.remove(path)
                handleTurnResult(result, path, oldPath, revision)
            }
        turnInFlight[path] = TurnFetch(job, revision)
        job.start()
    }

    private fun handleTurnResult(
        result: Result<List<ToolCallContent.Diff>?>,
        path: String,
        oldPath: String?,
        revision: String,
    ) {
        result
            .onSuccess { diffs ->
                if (diffs == null) {
                    update {
                        copy(
                            turnGitDiffsLoading = turnGitDiffsLoading - path,
                            turnGitDiffsErrors = turnGitDiffsErrors + (path to noWorkspaceMessage(path)),
                        )
                    }
                    return@onSuccess
                }
                turnLoaded[path] = oldPath to revision
                update {
                    copy(
                        turnGitDiffs = turnGitDiffs + (path to diffs),
                        turnGitDiffsLoading = turnGitDiffsLoading - path,
                    )
                }
            }.onFailure { throwable ->
                // Only the whole scope dying cancels a fetch that still owns its path.
                if (throwable is CancellationException) return@onFailure
                update {
                    copy(
                        turnGitDiffsLoading = turnGitDiffsLoading - path,
                        turnGitDiffsErrors = turnGitDiffsErrors + (path to failureMessage(throwable, path)),
                    )
                }
            }
    }

    /**
     * Drops the inline fallback diffs when a new turn starts: they describe the
     * tree as it is now, so the next turn must not borrow them. Also notes which files
     * were already changed, whose git diff would mix earlier work into this turn's.
     */
    fun clearTurnDiffs() {
        turnInFlight.values.forEach { it.job.cancel() }
        turnInFlight.clear()
        turnLoaded.clear()
        update {
            copy(
                turnGitDiffs = emptyMap(),
                turnGitDiffsLoading = emptySet(),
                turnGitDiffsErrors = emptyMap(),
                turnDirtyAtStart = gitStatus?.changed?.mapTo(HashSet()) { it.path },
            )
        }
    }

    private companion object {
        const val COMPARE_FAILED = "Failed to load the comparison"
        const val NO_COMPARE_WORKSPACE = "No gateway workspace connection to load the comparison"
        const val COMPARE_NOT_HONOURED = "The gateway did not apply the comparison"
    }

    private fun noWorkspaceMessage(path: String) = "No gateway workspace connection to load git diff for $path"

    private fun failureMessage(
        throwable: Throwable,
        path: String,
    ) = throwable.message?.takeIf { it.isNotBlank() } ?: "Failed to load git diff for $path"
}

/**
 * Runs [block] one at a time. A [run] made while it is in flight does not start a second copy; it
 * marks the result stale and reruns once after, so a late trigger (the session becoming ready after
 * an early attempt raced its setup) is never dropped. Used on [scope]'s main thread only.
 */
internal class Coalesced(
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit,
) {
    private var job: Job? = null
    private var stale = false

    fun run() {
        if (job?.isActive == true) {
            stale = true
            return
        }
        job =
            scope.launch {
                do {
                    stale = false
                    block()
                } while (stale)
            }
    }
}
