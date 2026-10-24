package com.tamimarafat.ferngeist.feature.chat.git

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.core.model.qualified
import com.tamimarafat.ferngeist.feature.chat.GitState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the per-file diff behind the git sheet into [GitState], keeping the in-flight request
 * bookkeeping out of the chat view model.
 */
internal class ChatGitFileDiffLoader(
    private val scope: CoroutineScope,
    private val facade: ChatWorkspaceReader,
    private val update: (GitState.() -> GitState) -> Unit,
    private val git: () -> GitState,
) {
    /**
     * In-flight diff fetch. Cancelling it on a newer request (and checking it after the
     * suspend call) guarantees a stale response can never overwrite the state a newer
     * request set. This loader takes the cancelling side of the platform limit described in
     * [ChatGitHistoryLoader].
     */
    private var job: Job? = null

    /**
     * Serializes request setup (job cancellation, state initialization, job assignment)
     * and the post-fetch identity check, so rapid intents cannot race through those steps.
     * `withLock` keeps the holder cancellable, and the identity guard inside the lock
     * retains latest-wins.
     */
    private val mutex = Mutex()

    /**
     * Fetches the diff for a single file in the gateway-backed working tree, or in
     * [commit] against its first parent, and stores it in [GitState.fileDiff] (the UI renders the first
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
        val compare = if (commit == null) git().compareRef?.qualified else null
        mutex.withLock {
            job?.cancel()
            update {
                copy(
                    fileDiffPath = path,
                    fileDiffCommit = commit,
                    fileDiffCompare = compare,
                    isFileDiffLoading = true,
                    fileDiffError = null,
                    fileDiff = null,
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
                            fileDiff = null,
                            isFileDiffLoading = false,
                            fileDiffError = noWorkspaceMessage(path),
                        )
                    }
                    return@onSuccess
                }
                update {
                    copy(
                        fileDiff = diffs,
                        isFileDiffLoading = false,
                        fileDiffError = null,
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return
                val message = failureMessage(throwable, path)
                update {
                    copy(
                        fileDiff = null,
                        isFileDiffLoading = false,
                        fileDiffError = message,
                    )
                }
            }
    }
}

/** The error for a file whose chat has no workspace to read it from. */
internal fun noWorkspaceMessage(path: String) = "No gateway workspace connection to load git diff for $path"

/** The error a failed file diff fetch reports, falling back to [path]'s own message. */
internal fun failureMessage(
    throwable: Throwable,
    path: String,
) = throwable.message?.takeIf { it.isNotBlank() } ?: "Failed to load git diff for $path"
