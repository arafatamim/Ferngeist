package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatChangedFile
import com.tamimarafat.ferngeist.core.model.ChatGitCommit
import com.tamimarafat.ferngeist.core.model.ChatGitLogPage
import com.tamimarafat.ferngeist.core.model.ChatGitRef
import com.tamimarafat.ferngeist.core.model.ChatGitRefs
import com.tamimarafat.ferngeist.core.model.ChatGitStatus
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.GitRefsUnsupportedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The git sheet's branch comparison: [ChatIntent.SelectCompareRef], [ChatIntent.LoadGitRefs] and what they feed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCompareTest : ChatViewModelTestBase() {
    private val main = ChatGitRef("main", ChatGitRef.Kind.BRANCH)
    private val dev = ChatGitRef("dev", ChatGitRef.Kind.BRANCH)
    private val feat = ChatGitRef("feat", ChatGitRef.Kind.BRANCH, isHead = true)

    @Test
    fun `selecting a target fills only the compare slots`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitStatus =
                        ChatGitStatus(branch = "feat", changed = listOf(ChatChangedFile("a.kt", "M", added = 2)))
                    gitCompareStatus = { ref ->
                        ChatGitStatus(
                            branch = "feat",
                            changed = listOf(ChatChangedFile("b.kt", "M", added = 40)),
                            compare = ref,
                        )
                    }
                }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("refs/heads/main"), facade.gitCompareStatusRequests)
            assertEquals(listOf("b.kt"), state.compareStatus?.changed?.map { it.path })
            assertFalse(state.isCompareStatusLoading)
            assertNull(state.compareStatusError)
            // The top-bar pill and the transcript rows stay working-tree-relative.
            assertEquals(listOf("a.kt"), state.gitStatus?.changed?.map { it.path })
            assertEquals(GitDiffStats(additions = 2, deletions = 0), state.gitDiffStats)
        }

    @Test
    fun `a reply that does not echo the requested ref is a failed fetch, not working-tree data`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitCompareStatus = { ChatGitStatus(branch = "feat", compare = null) }
                }

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(main, state.compareRef)
            assertNull(state.compareStatus)
            assertTrue(state.compareStatusError?.isNotBlank() == true)
        }

    @Test
    fun `a failed fetch keeps the chosen target and shows the error`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitCompareStatus = { throw IllegalStateException("boom") } }

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(main, state.compareRef)
            assertNull(state.compareStatus)
            assertEquals("boom", state.compareStatusError)
        }

    @Test
    fun `a superseded reply is dropped before the echo check, so it is no error`() =
        runTest {
            val mainReply = CompletableDeferred<Unit>()
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitCompareStatus = { ref ->
                        if (ref == "refs/heads/main") mainReply.await()
                        ChatGitStatus(branch = "feat", changed = listOf(ChatChangedFile(ref, "M")), compare = ref)
                    }
                }
            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.SelectCompareRef(dev))
            advanceUntilIdle()

            mainReply.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("refs/heads/dev"), state.compareStatus?.changed?.map { it.path })
            assertNull(state.compareStatusError)
        }

    @Test
    fun `choosing the working tree drops a loaded comparison`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitStatus = ChatGitStatus(branch = "feat") }
            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()
            assertEquals(
                "refs/heads/main",
                viewModel.state.value.compareStatus
                    ?.compare,
            )

            viewModel.dispatch(ChatIntent.SelectCompareRef(null))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.compareRef)
            assertNull(state.compareStatus)
        }

    @Test
    fun `a reply still in flight when the working tree is chosen is dropped`() =
        runTest {
            val reply = CompletableDeferred<Unit>()
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitCompareStatus = { ref ->
                        reply.await()
                        ChatGitStatus(branch = "feat", compare = ref)
                    }
                }
            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SelectCompareRef(null))
            reply.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.compareRef)
            assertNull(state.compareStatus)
            assertFalse(state.isCompareStatusLoading)
        }

    @Test
    fun `a git refresh re-reads the comparison only while a target is chosen`() =
        runTest {
            val (viewModel, facade) = gatewayViewModel { gitStatus = ChatGitStatus(branch = "feat") }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()
            assertEquals(0, facade.gitCompareStatusRequests.size)

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()

            assertEquals(2, facade.gitCompareStatusRequests.size)
        }

    @Test
    fun `the sheet's file diff reads against the target, and a commit's file does not`() =
        runTest {
            val (viewModel, facade) = gatewayViewModel { gitStatus = ChatGitStatus(branch = "feat") }
            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.LoadGitDiff("a.kt"))
            advanceUntilIdle()
            assertEquals("refs/heads/main", viewModel.state.value.gitFileDiffCompare)

            viewModel.dispatch(ChatIntent.LoadGitDiff("a.kt", commit = "abc1234"))
            advanceUntilIdle()
            assertNull(viewModel.state.value.gitFileDiffCompare)
            assertEquals(listOf("refs/heads/main", null), facade.gitDiffCompareRequests)
        }

    @Test
    fun `changing the target leaves the loaded history alone`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitLog = { _, _ -> page("h", false, "a") }
                }
            viewModel.dispatch(ChatIntent.LoadGitLog(more = false))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            viewModel.dispatch(ChatIntent.SelectCompareRef(dev))
            advanceUntilIdle()

            // History is always HEAD's own walk, so a new target neither reloads nor filters it.
            val shas =
                viewModel.state.value.gitHistory.commits
                    .map { it.sha }
            assertEquals(listOf("a"), shas)
            assertEquals(listOf(null to 0), facade.gitLogRequests)
        }

    @Test
    fun `a target picked before history was opened does not load it`() =
        runTest {
            val (viewModel, facade) = gatewayViewModel { gitStatus = ChatGitStatus(branch = "feat") }

            viewModel.dispatch(ChatIntent.SelectCompareRef(main))
            advanceUntilIdle()

            assertEquals(0, facade.gitLogRequests.size)
            assertFalse(viewModel.state.value.gitHistory.isLoaded)
        }

    @Test
    fun `refs load into state`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitRefs = { ChatGitRefs("main", listOf(main, dev)) } }

            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            val refs = viewModel.state.value.gitRefs
            assertEquals(listOf(main, dev), refs.refs)
            assertEquals("main", refs.default)
            assertTrue(refs.isLoaded)
        }

    @Test
    fun `a failed refs reload keeps the list already held`() =
        runTest {
            var fail = false
            val (viewModel, _) =
                gatewayViewModel {
                    gitRefs =
                        { if (fail) error("offline") else ChatGitRefs("main", listOf(main)) }
                }
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            fail = true
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            val refs = viewModel.state.value.gitRefs
            assertEquals(listOf(main), refs.refs)
            assertFalse(refs.unsupported)
        }

    @Test
    fun `a gateway without branch comparison marks refs unsupported`() =
        runTest {
            val (viewModel, _) = gatewayViewModel { gitRefs = { throw GitRefsUnsupportedException() } }

            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.gitRefs.unsupported)
        }

    @Test
    fun `a checkout on another branch opens against the default, in the compare slots only`() =
        runTest {
            val viewModel = openedOn("feat", ChatGitRefs("main", listOf(main, feat)))

            val state = viewModel.state.value
            assertEquals(main, state.compareRef)
            assertEquals("refs/heads/main", state.compareStatus?.compare)
            assertNull(state.gitStatus?.compare)
        }

    @Test
    fun `a checkout on the default branch stays on the working tree`() =
        runTest {
            assertNull(
                openedOn("main", ChatGitRefs("main", listOf(main.copy(isHead = true), dev))).state.value.compareRef,
            )
        }

    @Test
    fun `a detached HEAD stays on the working tree`() =
        runTest {
            assertNull(openedOn("(detached)", ChatGitRefs("main", listOf(main, dev))).state.value.compareRef)
        }

    @Test
    fun `a blank default stays on the working tree`() =
        runTest {
            assertNull(openedOn("feat", ChatGitRefs("", listOf(main, feat))).state.value.compareRef)
        }

    @Test
    fun `an unsupported refs endpoint stays on the working tree`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitRefs = { throw GitRefsUnsupportedException() }
                }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            assertNull(viewModel.state.value.compareRef)
            assertTrue(viewModel.state.value.gitRefs.unsupported)
            assertEquals(0, facade.gitCompareStatusRequests.size)
        }

    @Test
    fun `a default shared by a tag and a branch resolves to the branch`() =
        runTest {
            val tag = ChatGitRef("main", ChatGitRef.Kind.TAG)
            assertEquals(main, openedOn("feat", ChatGitRefs("main", listOf(tag, feat, main))).state.value.compareRef)
        }

    @Test
    fun `a default that only exists as a remote resolves to the remote`() =
        runTest {
            val remote = ChatGitRef("origin/main", ChatGitRef.Kind.REMOTE)
            assertEquals(
                remote,
                openedOn("feat", ChatGitRefs("origin/main", listOf(feat, remote))).state.value.compareRef,
            )
        }

    @Test
    fun `choosing the working tree keeps later refreshes off the default`() =
        runTest {
            val viewModel = openedOn("feat", ChatGitRefs("main", listOf(main, feat)))

            viewModel.dispatch(ChatIntent.SelectCompareRef(null))
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            assertNull(viewModel.state.value.compareRef)
        }

    @Test
    fun `a pick made before refs land is left alone`() =
        runTest {
            val (viewModel, _) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitRefs = { ChatGitRefs("main", listOf(main, dev, feat)) }
                }
            viewModel.dispatch(ChatIntent.SelectCompareRef(dev))
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            assertEquals(dev, viewModel.state.value.compareRef)
        }

    @Test
    fun `refs landing after the status select the default exactly once`() =
        runTest {
            val (viewModel, facade) =
                gatewayViewModel {
                    gitStatus = ChatGitStatus(branch = "feat")
                    gitRefs = { ChatGitRefs("main", listOf(main, feat)) }
                }
            viewModel.dispatch(ChatIntent.RefreshGitStatus)
            advanceUntilIdle()
            assertNull(viewModel.state.value.compareRef)

            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()
            viewModel.dispatch(ChatIntent.LoadGitRefs)
            advanceUntilIdle()

            assertEquals(main, viewModel.state.value.compareRef)
            assertEquals(listOf("refs/heads/main"), facade.gitCompareStatusRequests)
        }

    private fun TestScope.openedOn(
        branch: String,
        refs: ChatGitRefs,
    ): ChatViewModel {
        val (viewModel, _) =
            gatewayViewModel {
                gitStatus = ChatGitStatus(branch = branch)
                gitRefs = { refs }
            }
        viewModel.dispatch(ChatIntent.RefreshGitStatus)
        viewModel.dispatch(ChatIntent.LoadGitRefs)
        advanceUntilIdle()
        return viewModel
    }

    private fun page(
        head: String,
        hasMore: Boolean,
        vararg shas: String,
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
