package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.ChatWorktree
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which gateway worktree a chat runs in, as read by [ChatIntent.RefreshGitStatus]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelWorktreeTest : ChatViewModelTestBase() {
    private val featX = ChatWorktree(branch = "feat-x", ahead = 2, dirty = true)

    @Test
    fun `a refresh stores the worktree the gateway reports`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat-x")
                    worktree = { featX }
                }

            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            assertEquals(featX, viewModel.state.value.worktree)
        }

    @Test
    fun `a failed refresh keeps the last known worktree`() =
        runTest {
            var failing = false
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat-x")
                    worktree = { if (failing) error("offline") else featX }
                }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            failing = true
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            assertEquals(featX, viewModel.state.value.worktree)
        }

    @Test
    fun `a refresh publishes the status and its worktree in one update`() =
        runTest {
            val facadeFactory = FakeChatSessionFacadeFactory()
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            val facade = facadeFactory.lastFacade.value!!
            val states = mutableListOf<ChatState>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.toList(states) }

            facade.emitGatewayWorkspaceConnection(
                GatewayWorkspaceConnection("rt-1", "http", "10.0.0.2:5788", "token"),
            )
            facade.gitStatus = ChatGitStatus(branch = "feat-x")
            facade.worktree = { featX }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(featX, viewModel.state.value.worktree)
            // The pill draws "clean" from the status and ahead/behind from the worktree: a status
            // published ahead of its worktree showed a check, then replaced it with the arrows.
            assertTrue(states.none { it.gitStatus != null && it.worktree == null })
        }

    @Test
    fun `a chat outside any worktree has none`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitStatus = ChatGitStatus(branch = "main") }

            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            assertNull(viewModel.state.value.worktree)
        }

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
