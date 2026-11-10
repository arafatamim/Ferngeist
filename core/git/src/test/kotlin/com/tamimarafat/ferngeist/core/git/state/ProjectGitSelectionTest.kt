package com.tamimarafat.ferngeist.core.git.state

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GitWorkspaceReader
import com.tamimarafat.ferngeist.core.model.git.ProjectGitReaderFactory
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectGitSelectionTest {
    private val main = ProjectGitTarget("http", "gateway", "paired", "/repo")

    @Test
    fun switchingCheckoutDiscardsStateAndIgnoresThePreviousInflightReply() =
        runTest {
            val firstReply = CompletableDeferred<ChatGitStatus?>()
            val selection = ProjectGitSelection(this, readerFactory(firstReply))
            val first = requireNotNull(selection.select(main))
            first.refresh()
            runCurrent()
            val next = requireNotNull(selection.select(main.copy(cwd = "/trees/fix")))
            assertNull(next.state.value.status)
            next.refresh()
            runCurrent()
            assertEquals(
                "fix",
                next.state.value.status
                    ?.branch,
            )
            firstReply.complete(ChatGitStatus(branch = "main"))
            runCurrent()
            assertEquals(
                "fix",
                next.state.value.status
                    ?.branch,
            )
            assertEquals(
                "main",
                first.state.value.status
                    ?.branch,
            )
        }

    @Test
    fun allDiscardsTheCheckoutAndReturningStartsFresh() =
        runTest {
            val reply = CompletableDeferred<ChatGitStatus?>(ChatGitStatus(branch = "main"))
            val selection = ProjectGitSelection(this, readerFactory(reply))
            val previous = requireNotNull(selection.select(main))
            previous.refresh()
            runCurrent()
            assertEquals(
                "main",
                previous.state.value.status
                    ?.branch,
            )
            assertNull(selection.select(null))
            val returned = requireNotNull(selection.select(main))
            assertNotSame(previous, returned)
            assertNull(returned.state.value.status)
            assertEquals(
                emptyList<ChatChangedFile>(),
                returned.state.value.history.commitFiles
                    .orEmpty(),
            )
        }

    private fun readerFactory(reply: CompletableDeferred<ChatGitStatus?>) =
        object : ProjectGitReaderFactory {
            override fun create(target: ProjectGitTarget): GitWorkspaceReader =
                Reader {
                    if (target.cwd == main.cwd) reply.await() else ChatGitStatus(branch = "fix")
                }
        }
}

private class Reader(
    private val status: suspend () -> ChatGitStatus?,
) : GitWorkspaceReader {
    override suspend fun fetchGitStatus(compare: String?): ChatGitStatus? = status()

    override suspend fun fetchWorktree(): ChatWorktree? = null

    override suspend fun fetchGitDiff(
        path: String,
        commit: String?,
        oldPath: String?,
        compare: String?,
    ): List<ToolCallContent.Diff>? = null

    override suspend fun fetchGitLog(
        head: String?,
        skip: Int,
    ): ChatGitLogPage? = null

    override suspend fun fetchGitRefs(): ChatGitRefs? = null

    override suspend fun fetchGitCommitFiles(sha: String): List<ChatChangedFile>? = null

    override suspend fun fetchFile(path: String): ChatFileContents? = null
}
