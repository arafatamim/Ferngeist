package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
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
 * Tests for [ChatViewModel] [ChatIntent.LoadGitDiff] handling: loading state visibility,
 * error retention, stale-response rejection, and the no-connection path.
 *
 * The diff is fetched through [ChatSessionFacade], so these drive the fake facade's
 * gateway-workspace knobs rather than a gateway repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLoadGitDiffTest : ChatViewModelTestBase() {
    @Test
    fun `LoadGitDiff sets loading then loaded state with the first returned diff and records the path`() =
        runTest {
            val diff = testDiff("val old = 1\n", "val new = 1\n", "src/Main.kt")
            val gate = CompletableDeferred<Unit>()
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffResult = listOf(diff)
                    fetchGitDiffGate = gate
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            // The fetch is still in flight, so the loading transition is observable.
            var state = viewModel.state.value
            assertTrue(state.git.isFileDiffLoading)
            assertEquals("src/Main.kt", state.git.fileDiffPath)

            gate.complete(Unit)
            advanceUntilIdle()

            state = viewModel.state.value
            assertFalse(state.git.isFileDiffLoading)
            assertEquals("src/Main.kt", state.git.fileDiffPath)
            assertEquals(diff, state.git.fileDiff?.single())
            assertEquals("src/Main.kt", facade.gitDiffRequests.single())
        }

    @Test
    fun `LoadGitDiff with facade exception retains path, clears diff, shows nonblank error`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffError = IllegalStateException("gateway unreachable")
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("src/Main.kt", state.git.fileDiffPath)
            assertNull(state.git.fileDiff)
            assertFalse(state.git.isFileDiffLoading)
            assertTrue(
                state.git.fileDiffError
                    .isNullOrBlank()
                    .not(),
            )
            assertEquals("src/Main.kt", facade.gitDiffRequests.single())
        }

    @Test
    fun `LoadGitDiff without gateway workspace keeps path, clears diff, shows nonblank error`() =
        runTest {
            val facadeFactory = FakeChatSessionFacadeFactory()
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("src/Main.kt", state.git.fileDiffPath)
            assertNull(state.git.fileDiff)
            assertFalse(state.git.isFileDiffLoading)
            assertTrue(
                state.git.fileDiffError
                    .isNullOrBlank()
                    .not(),
            )
            // The facade has no workspace connection, so it answers null without asking a gateway.
            assertTrue(
                facadeFactory.lastFacade.value!!
                    .gitDiffRequests
                    .isEmpty(),
            )
        }

    @Test
    fun `stale LoadGitDiff response cannot overwrite the latest requested path`() =
        runTest {
            val staleDiff = testDiff("val old = 1\n", "val stale = 1\n", "src/Stale.kt")
            val latestDiff = testDiff("val old = 2\n", "val latest = 2\n", "src/Latest.kt")
            val staleGate = CompletableDeferred<Unit>()
            val latestGate = CompletableDeferred<Unit>()
            val (viewModel, _) =
                gatewayViewModel {
                    fetchGitDiffGates["src/Stale.kt"] = staleGate
                    fetchGitDiffGates["src/Latest.kt"] = latestGate
                    fetchGitDiffResults["src/Stale.kt"] = listOf(staleDiff)
                    fetchGitDiffResults["src/Latest.kt"] = listOf(latestDiff)
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Stale.kt"))
            advanceUntilIdle()

            // The first request is in flight; a second, newer request supersedes it.
            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Latest.kt"))
            advanceUntilIdle()
            assertEquals("src/Latest.kt", viewModel.state.value.git.fileDiffPath)
            assertTrue(viewModel.state.value.git.isFileDiffLoading)

            // The latest request completes first and its diff becomes the state.
            latestGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("src/Latest.kt", viewModel.state.value.git.fileDiffPath)
            assertEquals(
                latestDiff,
                viewModel.state.value.git.fileDiff
                    ?.single(),
            )

            // The stale request then completes; it must NOT overwrite the newer state.
            staleGate.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.git.isFileDiffLoading)
            assertEquals("src/Latest.kt", state.git.fileDiffPath)
            assertEquals(latestDiff, state.git.fileDiff?.single())
            assertTrue(state.git.fileDiffError.isNullOrBlank())
        }

    @Test
    fun `LoadTurnFileDiff stores the diff under its path and answers once per path`() =
        runTest {
            val diff = testDiff("old\n", "new\n", "src/New.kt")
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffResult = listOf(diff)
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()
            // A second request for the answered path must not fetch again.
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(diff, state.git.turnDiffs["src/New.kt"]?.single())
            assertTrue(state.git.turnDiffsLoading.isEmpty())
            assertTrue(state.git.turnDiffsErrors.isEmpty())
            assertEquals(listOf("src/New.kt"), facade.gitDiffRequests)
        }

    @Test
    fun `LoadTurnFileDiff with facade exception records a per-path error`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffError = IllegalStateException("gateway unreachable")
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()
            // A failed path retries on the next request.
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.git.turnDiffs["src/New.kt"])
            assertTrue(state.git.turnDiffsLoading.isEmpty())
            assertTrue(
                state.git.turnDiffsErrors["src/New.kt"]
                    .isNullOrBlank()
                    .not(),
            )
            assertEquals(listOf("src/New.kt", "src/New.kt"), facade.gitDiffRequests)
        }

    @Test
    fun `LoadTurnFileDiff without gateway workspace records a per-path error`() =
        runTest {
            val facadeFactory = FakeChatSessionFacadeFactory()
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.git.turnDiffs["src/New.kt"])
            assertTrue(state.git.turnDiffsLoading.isEmpty())
            assertTrue(
                state.git.turnDiffsErrors["src/New.kt"]
                    .isNullOrBlank()
                    .not(),
            )
            // The facade has no workspace connection, so it answers null without asking a gateway.
            assertTrue(
                facadeFactory.lastFacade.value!!
                    .gitDiffRequests
                    .isEmpty(),
            )
        }

    @Test
    fun `LoadTurnFileDiff in flight is not refetched for the same revision`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffGates["src/New.kt"] = gate
                    fetchGitDiffResult = listOf(testDiff("old\n", "new\n", "src/New.kt"))
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt", revision = "M:1:0"))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt", revision = "M:1:0"))
            advanceUntilIdle()
            assertEquals(setOf("src/New.kt"), viewModel.state.value.git.turnDiffsLoading)
            gate.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.git.turnDiffsLoading.isEmpty())
            assertEquals(1, state.git.turnDiffs["src/New.kt"]?.size)
            assertEquals(listOf("src/New.kt"), facade.gitDiffRequests)
        }

    @Test
    fun `LoadTurnFileDiff for a newer revision replaces the stale answer`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    fetchGitDiffResult = listOf(testDiff("old\n", "new\n", "src/New.kt"))
                }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt", revision = "M:1:0"))
            advanceUntilIdle()
            facade.fetchGitDiffResult = listOf(testDiff("old\n", "newer\n", "src/New.kt"))
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt", revision = "M:2:0"))
            advanceUntilIdle()

            assertEquals(2, facade.gitDiffRequests.size)
            val state = viewModel.state.value
            assertEquals(
                "newer\n",
                state.git.turnDiffs["src/New.kt"]
                    ?.single()
                    ?.newText,
            )
            assertTrue(state.git.turnDiffsLoading.isEmpty())
        }

    @Test
    fun `LoadTurnFileDiff stores an empty answer so the path is not retried`() =
        runTest {
            val (viewModel, facade) = gatewayViewModel { fetchGitDiffResult = emptyList() }
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt"))
            advanceUntilIdle()

            assertEquals(emptyList<Any>(), viewModel.state.value.git.turnDiffs["src/New.kt"])
            assertEquals(1, facade.gitDiffRequests.size)
        }

    @Test
    fun `a git status refresh re-reads the turn diffs already loaded`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(changed = listOf(ChatChangedFile(path = "src/New.kt", status = "M")))
                    fetchGitDiffResult = listOf(testDiff("old\n", "new\n", "src/New.kt"))
                }
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadTurnFileDiff("src/New.kt", revision = "M:1:0"))
            advanceUntilIdle()

            // Same counts, different text: only a re-read can notice.
            facade.fetchGitDiffResult = listOf(testDiff("old\n", "newer\n", "src/New.kt"))
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            assertEquals(2, facade.gitDiffRequests.size)
            assertEquals(
                "newer\n",
                viewModel.state.value.git.turnDiffs["src/New.kt"]
                    ?.single()
                    ?.newText,
            )
        }

    // region: Helpers

    /** A standard gateway connection used across all LoadGitDiff tests. */
    private val testConnection =
        GatewayWorkspaceConnection(
            runtimeId = "rt-1",
            scheme = "http",
            host = "10.0.0.2:5788",
            gatewayCredential = "plain-token",
        )

    /** Creates a [ToolCallContent.Diff] with the given parameters. */
    private fun testDiff(
        oldText: String,
        newText: String,
        path: String,
    ): ToolCallContent.Diff =
        ToolCallContent.Diff(
            oldText = oldText,
            newText = newText,
            path = path,
        )

    /**
     * Creates a view model whose facade already holds [testConnection] as its gateway
     * workspace connection and carries the git-diff behavior [configure] sets. The facade
     * is created synchronously inside the view model's constructor, so it is available
     * here to configure before any intent runs.
     */
    private fun gatewayViewModel(
        configure: FakeChatSessionFacade.() -> Unit = {},
    ): Pair<ChatViewModel, FakeChatSessionFacade> {
        val facadeFactory = FakeChatSessionFacadeFactory()
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        val facade = facadeFactory.lastFacade.value!!
        facade.emitGatewayWorkspaceConnection(testConnection)
        facade.configure()
        return viewModel to facade
    }

    // endregion
}
