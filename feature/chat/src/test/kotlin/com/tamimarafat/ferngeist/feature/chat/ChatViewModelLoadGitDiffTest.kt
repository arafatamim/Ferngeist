package com.tamimarafat.ferngeist.feature.chat

import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.GatewayRepository
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelLoadGitDiffTest : ChatViewModelTestBase() {
    @Test
    fun `LoadGitDiff sets loading then loaded state with the first returned diff and records the path`() =
        runTest {
            val diff = testDiff("val old = 1\n", "val new = 1\n", "src/Main.kt")
            val gate = CompletableDeferred<Unit>()
            val gatewayRepository =
                FakeGatewayRepository().apply {
                    fetchGitDiffResult = listOf(diff)
                    fetchGitDiffGate = gate
                }
            val viewModel = createViewModelWithGatewayConnection(gatewayRepository = gatewayRepository)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            // The fetch is still in flight, so the loading transition is observable.
            var state = viewModel.state.value
            assertTrue(state.isGitFileDiffLoading)
            assertEquals("src/Main.kt", state.gitFileDiffPath)

            gate.complete(Unit)
            advanceUntilIdle()

            state = viewModel.state.value
            assertFalse(state.isGitFileDiffLoading)
            assertEquals("src/Main.kt", state.gitFileDiffPath)
            assertEquals(diff, state.gitFileDiff?.single())
            assertEquals(
                "src/Main.kt",
                gatewayRepository.gitDiffRequests.single().first,
            )
        }

    @Test
    fun `LoadGitDiff with repository exception retains path, clears diff, shows nonblank error`() =
        runTest {
            val gatewayRepository =
                FakeGatewayRepository().apply {
                    fetchGitDiffError = IllegalStateException("gateway unreachable")
                }
            val viewModel = createViewModelWithGatewayConnection(gatewayRepository = gatewayRepository)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("src/Main.kt", state.gitFileDiffPath)
            assertNull(state.gitFileDiff)
            assertFalse(state.isGitFileDiffLoading)
            assertTrue(state.gitFileDiffError.isNullOrBlank().not())
            assertEquals(
                "src/Main.kt",
                gatewayRepository.gitDiffRequests.single().first,
            )
        }

    @Test
    fun `LoadGitDiff without gateway workspace keeps path, clears diff, shows nonblank error`() =
        runTest {
            val gatewayRepository = FakeGatewayRepository()
            val viewModel = createViewModel(gatewayRepository = gatewayRepository)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Main.kt"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("src/Main.kt", state.gitFileDiffPath)
            assertNull(state.gitFileDiff)
            assertFalse(state.isGitFileDiffLoading)
            assertTrue(state.gitFileDiffError.isNullOrBlank().not())
            // No fetch is attempted when there is no connection to fetch through.
            assertTrue(gatewayRepository.gitDiffRequests.isEmpty())
        }

    @Test
    fun `stale LoadGitDiff response cannot overwrite the latest requested path`() =
        runTest {
            val staleDiff = testDiff("val old = 1\n", "val stale = 1\n", "src/Stale.kt")
            val latestDiff = testDiff("val old = 2\n", "val latest = 2\n", "src/Latest.kt")
            val staleGate = CompletableDeferred<Unit>()
            val latestGate = CompletableDeferred<Unit>()
            val gatewayRepository =
                FakeGatewayRepository().apply {
                    fetchGitDiffGates["src/Stale.kt"] = staleGate
                    fetchGitDiffGates["src/Latest.kt"] = latestGate
                    fetchGitDiffResults["src/Stale.kt"] = listOf(staleDiff)
                    fetchGitDiffResults["src/Latest.kt"] = listOf(latestDiff)
                }
            val viewModel = createViewModelWithGatewayConnection(gatewayRepository = gatewayRepository)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Stale.kt"))
            advanceUntilIdle()

            // The first request is in flight; a second, newer request supersedes it.
            viewModel.dispatch(ChatIntent.LoadGitDiff("src/Latest.kt"))
            advanceUntilIdle()
            assertEquals("src/Latest.kt", viewModel.state.value.gitFileDiffPath)
            assertTrue(viewModel.state.value.isGitFileDiffLoading)

            // The latest request completes first and its diff becomes the state.
            latestGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("src/Latest.kt", viewModel.state.value.gitFileDiffPath)
            assertEquals(
                latestDiff,
                viewModel.state.value.gitFileDiff
                    ?.single(),
            )

            // The stale request then completes; it must NOT overwrite the newer state.
            staleGate.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isGitFileDiffLoading)
            assertEquals("src/Latest.kt", state.gitFileDiffPath)
            assertEquals(latestDiff, state.gitFileDiff?.single())
            assertTrue(state.gitFileDiffError.isNullOrBlank())
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

    /** Creates a view model whose gateway workspace connection is already [testConnection]. */
    private suspend fun createViewModelWithGatewayConnection(
        gatewayRepository: GatewayRepository = FakeGatewayRepository(),
    ): ChatViewModel {
        val facadeFactory = FakeChatSessionFacadeFactory()
        val viewModel =
            createViewModel(
                gatewayRepository = gatewayRepository,
                facadeFactory = facadeFactory,
            )
        // The facade is created synchronously inside ChatViewModel's constructor, so it is
        // available here to emit the connection from the test's coroutine context.
        facadeFactory.lastFacade.value?.emitGatewayWorkspaceConnection(testConnection)
        return viewModel
    }

    // endregion
}
