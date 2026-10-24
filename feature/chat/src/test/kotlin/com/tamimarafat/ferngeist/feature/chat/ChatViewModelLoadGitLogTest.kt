package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.GitHistoryHeadGoneException
import com.tamimarafat.ferngeist.core.model.GitHistoryUnsupportedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatIntent.LoadGitLog], [ChatIntent.LoadGitCommit], and commit-mode [ChatIntent.LoadGitDiff].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLoadGitLogTest : ChatViewModelTestBase() {
    @Test
    fun `later pages pin page one's head and append, and stop once there is no more`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, skip -> if (head == null) page("h", true, "a") else page("h", false, "b") }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.git.history
            assertEquals(listOf("a", "b"), history.commits.map { it.sha })
            assertFalse(history.hasMore)
            assertTrue(history.isLoaded)
            assertEquals(listOf(null to 0, "h" to 1), facade.gitLogRequests)
        }

    @Test
    fun `the walk's total reaches the tab badge and a later page cannot clear it`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        if (head == null) page("h", true, "a", total = 5) else page("h", false, "b")
                    }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            assertEquals(5, viewModel.state.value.git.history.total)
        }

    @Test
    fun `a gateway that omits the total leaves the badge off`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitLog = { _, _ -> page("h", false, "a") } }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            assertNull(viewModel.state.value.git.history.total)
        }

    @Test
    fun `a refresh adds new commits on top and keeps the pages already loaded`() =
        runTest {
            var refreshed = false
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        when {
                            head != null -> page("h", true, "c", "d")
                            refreshed -> page("h2", true, "new", "a")
                            else -> page("h", true, "a", "b")
                        }
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            refreshed = true
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            val history = viewModel.state.value.git.history
            assertEquals(listOf("new", "a", "b", "c", "d"), history.commits.map { it.sha })
            assertEquals("h2", history.head)
            assertTrue(history.hasMore)
        }

    @Test
    fun `a git refresh reloads history only once it has been opened`() =
        runTest {
            val (viewModel, facade) = gatewayViewModel { gitLog = { _, _ -> page("h", false, "a") } }

            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()
            assertEquals(0, facade.gitLogRequests.size)

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()
            assertEquals(listOf(null to 0, null to 0), facade.gitLogRequests)
        }

    @Test
    fun `a vanished head on a later page reloads page one instead of failing`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        if (head == null) page("h2", true, "x") else throw GitHistoryHeadGoneException(Exception())
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.git.history
            assertEquals(listOf(null to 0, "h2" to 1, null to 0), facade.gitLogRequests)
            assertNull(history.moreError)
            assertFalse(history.isLoadingMore)
        }

    @Test
    fun `a gateway without history marks the tab unsupported`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitLog = { _, _ -> throw GitHistoryUnsupportedException() } }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            val history = viewModel.state.value.git.history
            assertTrue(history.unsupported)
            assertFalse(history.isLoading)
            assertNull(history.error)
        }

    @Test
    fun `a reload drops the late reply of the page it replaced, and a repeat reload is not sent`() =
        runTest {
            val pageTwo = CompletableDeferred<Unit>()
            var reloads = 0
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        if (head == null) {
                            page("h", true, "p1-${reloads++}")
                        } else {
                            pageTwo.await()
                            page("h", false, "stale")
                        }
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            pageTwo.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf("p1-1"),
                viewModel.state.value.git.history.commits
                    .map { it.sha },
            )
            assertEquals(listOf(null to 0, "h" to 1, null to 0), facade.gitLogRequests)
        }

    @Test
    fun `asking for page one while it is in flight sends nothing more`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val (viewModel, facade) =
                gatewayViewModel {
                    gitLog = { _, _ ->
                        gate.await()
                        page("h", false, "a")
                    }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, facade.gitLogRequests.size)
            assertEquals(
                listOf("a"),
                viewModel.state.value.git.history.commits
                    .map { it.sha },
            )
        }

    @Test
    fun `a failed later page keeps the list and offers a retry`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { head, _ -> if (head == null) page("h", true, "a") else error("offline") }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            val history = viewModel.state.value.git.history
            assertEquals(listOf("a"), history.commits.map { it.sha })
            assertEquals("offline", history.moreError)
            assertNull(history.error)
        }

    @Test
    fun `a commit loads its files, and a file of it diffs against that commit`() =
        runTest {
            val renamed = ChatChangedFile(path = "b.kt", status = "R", oldPath = "a.kt")
            val (viewModel, facade) = gatewayViewModel { gitCommitFiles = { listOf(renamed) } }

            viewModel.dispatch(ChatIntent.LoadGitCommit("abc"))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitDiff("b.kt", commit = "abc", oldPath = "a.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("abc", state.git.history.commitSha)
            assertEquals(listOf(renamed), state.git.history.commitFiles)
            assertEquals("abc", state.git.fileDiffCommit)
            assertEquals(listOf("abc" to "a.kt"), facade.gitDiffCommitRequests)
        }

    @Test
    fun `the page's base reaches state, so the list can mark this branch's own commits`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { _, _ -> page("h", false, "a").copy(base = "base123") }
                }

            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            assertEquals("base123", viewModel.state.value.git.history.base)
        }

    @Test
    fun `a page with no base leaves a base already known in place`() =
        runTest {
            var withBase = true
            val (viewModel, _) =
                gatewayViewModel {
                    gitLog = { head, _ ->
                        page("h", head == null, if (head == null) "a" else "b")
                            .copy(base = if (withBase) "base123" else null)
                    }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            withBase = false
            viewModel.dispatch(ChatIntent.LoadGitLog(more = true))
            advanceUntilIdle()

            assertEquals("base123", viewModel.state.value.git.history.base)
        }

    @Test
    fun `the session becoming ready after a load asks for git status again`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            val facade = facadeFactory.lastFacade.value!!
            // Before session/load the gateway has no directory for this session, so status is empty.
            facade.emitGatewayWorkspaceConnection(GatewayWorkspaceConnection("rt-1", "http", "10.0.0.2:5788", "token"))
            advanceUntilIdle()
            assertNull(viewModel.state.value.git.status)

            facade.gitStatus = ChatGitStatus(branch = "main")
            facade.emitSnapshot(readySnapshot())
            advanceUntilIdle()

            assertEquals(
                "main",
                viewModel.state.value.git.status
                    ?.branch,
            )
        }

    private fun page(
        head: String,
        hasMore: Boolean,
        vararg shas: String,
        total: Int? = null,
    ) = ChatGitLogPage(
        head = head,
        commits =
            shas.map {
                ChatGitCommit(
                    it,
                    emptyList(),
                    it,
                    authorName = "A",
                    authorEmail = "",
                    authorTimeMillis = 0,
                )
            },
        hasMore = hasMore,
        remoteUrl = null,
        total = total,
    )

    private fun gatewayViewModel(
        configure: FakeChatSessionFacade.() -> Unit,
    ): Pair<ChatViewModel, FakeChatSessionFacade> {
        val facadeFactory = FakeChatSessionFacadeFactory()
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        val facade = facadeFactory.lastFacade.value!!
        facade.emitGatewayWorkspaceConnection(GatewayWorkspaceConnection("rt-1", "http", "10.0.0.2:5788", "token"))
        facade.configure()
        return viewModel to facade
    }
}
