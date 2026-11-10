package com.tamimarafat.ferngeist.core.git.state

import com.tamimarafat.ferngeist.core.git.git.ChatGitFileDiffLoader
import com.tamimarafat.ferngeist.core.git.git.ChatGitHistoryLoader
import com.tamimarafat.ferngeist.core.git.git.ChatGitStatusLoader
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.GitWorkspaceReader
import com.tamimarafat.ferngeist.core.model.qualified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One checkout's Git experience. A new target gets a new controller, isolating late replies. */
class ProjectGitController(
    scope: CoroutineScope,
    reader: GitWorkspaceReader,
) {
    private val mutableState = MutableStateFlow(GitState())
    val state = mutableState.asStateFlow()
    private var compareChosen = false
    private val status =
        ChatGitStatusLoader(
            scope,
            reader,
            update = { reduce ->
                mutableState.update(reduce)
                preselectCompare()
            },
            git = { state.value },
            onStatusRefreshed = {},
        )
    private val history =
        ChatGitHistoryLoader(
            scope,
            reader,
            history = { state.value.history },
            update = { reduce -> mutableState.update { it.copy(history = it.history.reduce()) } },
        )
    private val diff =
        ChatGitFileDiffLoader(
            scope,
            reader,
            update = { reduce -> mutableState.update(reduce) },
            git = { state.value },
        )

    fun refresh() {
        status.refresh()
        status.refreshCompare()
        if (state.value.history.isLoaded) history.loadLog(more = false)
    }

    fun open() {
        refresh()
        status.loadRefs()
    }

    fun loadLog(more: Boolean) = history.loadLog(more)

    fun loadCommit(sha: String) = history.loadCommit(sha)

    suspend fun loadDiff(
        path: String,
        commit: String?,
        oldPath: String?,
    ) = diff.load(path, commit, oldPath)

    fun selectCompare(ref: ChatGitRef?) {
        compareChosen = true
        applyCompare(ref)
    }

    private fun applyCompare(ref: ChatGitRef?) {
        if (ref == state.value.compareRef) return
        mutableState.update {
            it.copy(compareRef = ref, compareStatus = null, isCompareStatusLoading = false, compareStatusError = null)
        }
        status.refreshCompare()
    }

    private fun preselectCompare() {
        val git = state.value
        val refs = git.refs
        if (compareChosen || git.compareRef != null || refs.unsupported) return
        if (refs.default.isBlank()) return
        val branch = git.status?.branch.orEmpty()
        if (branch == refs.default || refs.refs.none { it.kind == ChatGitRef.Kind.BRANCH && it.name == branch }) return
        refs.refs
            .filter { it.name == refs.default && it.qualified != null && !it.isHead }
            .minByOrNull { it.kind.ordinal }
            ?.let(::applyCompare)
    }
}
