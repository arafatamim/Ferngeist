package com.tamimarafat.ferngeist.feature.chat.git

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.feature.chat.GitState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reads the latest turn's inline fallback diffs into [GitState], for files whose tool calls
 * reported none. A path's superseded fetch is cancelled, the cancelling side of the platform
 * limit described in [ChatGitHistoryLoader].
 */
internal class ChatGitTurnDiffLoader(
    private val scope: CoroutineScope,
    private val facade: ChatWorkspaceReader,
    private val update: (GitState.() -> GitState) -> Unit,
    private val git: () -> GitState,
) {
    /**
     * A path's fetch and the [revision] it was made for. A path's in-flight fetch is replaced only
     * by a request for a newer [revision]; [turnLoaded] remembers the revision each path's stored
     * diff was read at. Touched only on [scope]'s (main) thread and without suspending between
     * check and write, so no lock is needed here.
     */
    private class TurnFetch(
        val job: Job,
        val revision: String,
    )

    private val turnInFlight = mutableMapOf<String, TurnFetch>()

    /** Each loaded path's request (old path and revision), so a status refresh can re-read it. */
    private val turnLoaded = mutableMapOf<String, Pair<String?, String>>()

    /**
     * Fetches one latest-turn file's working-tree diff into [GitState.turnDiffs], for inline
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
                turnDiffsLoading = turnDiffsLoading + path,
                turnDiffsErrors = turnDiffsErrors - path,
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

    /** Re-reads every stored path's diff, for a status refresh that cannot see the text change. */
    fun rereadLoaded() {
        turnLoaded.toMap().forEach { (path, request) ->
            turnLoaded.remove(path)
            loadTurnFile(path, request.first, request.second)
        }
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
                            turnDiffsLoading = turnDiffsLoading - path,
                            turnDiffsErrors = turnDiffsErrors + (path to noWorkspaceMessage(path)),
                        )
                    }
                    return@onSuccess
                }
                turnLoaded[path] = oldPath to revision
                update {
                    copy(
                        turnDiffs = turnDiffs + (path to diffs),
                        turnDiffsLoading = turnDiffsLoading - path,
                    )
                }
            }.onFailure { throwable ->
                // Only the whole scope dying cancels a fetch that still owns its path.
                if (throwable is CancellationException) return@onFailure
                update {
                    copy(
                        turnDiffsLoading = turnDiffsLoading - path,
                        turnDiffsErrors = turnDiffsErrors + (path to failureMessage(throwable, path)),
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
                turnDiffs = emptyMap(),
                turnDiffsLoading = emptySet(),
                turnDiffsErrors = emptyMap(),
                dirtyAtStart = git().status?.changed?.mapTo(HashSet()) { it.path },
            )
        }
    }
}
