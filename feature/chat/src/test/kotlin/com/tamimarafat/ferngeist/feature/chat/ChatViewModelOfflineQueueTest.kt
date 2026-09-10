package com.tamimarafat.ferngeist.feature.chat

import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ChatViewModel] offline-queue behavior: enqueuing while disconnected,
 * flushing on session-ready events, FAILED→RETRY transitions, and echo
 * deduplication of unrelated or stale messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelOfflineQueueTest : ChatViewModelTestBase() {
    @Test
    fun `sessionReady flushes all queued prompts in FIFO order`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("first"))
                advanceUntilIdle()
                viewModel.dispatch(ChatIntent.SendMessage("second"))
                advanceUntilIdle()

                var state = viewModel.state.value
                assertEquals(2, state.pendingMessages.size)
                assertEquals("first", state.pendingMessages[0].content)
                assertEquals("second", state.pendingMessages[1].content)
                assertEquals(MessageDeliveryStatus.QUEUED, state.pendingMessages[0].status)
                assertEquals(MessageDeliveryStatus.QUEUED, state.pendingMessages[1].status)

                facadeFactory.lastFacade.value?.emitSessionReady()
                advanceUntilIdle()

                state = viewModel.state.value
                assertEquals(2, state.pendingMessages.size)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[0].status)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[1].status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `QUEUED transitions to SENDING then removed when reducer echo arrives`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("hello world"))
                advanceUntilIdle()

                facadeFactory.lastFacade.value?.emitSessionReady()
                advanceUntilIdle()

                var state = viewModel.state.value
                assertEquals(1, state.pendingMessages.size)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[0].status)

                facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(echoMessage(content = "hello world")))
                advanceUntilIdle()

                state = viewModel.state.value
                assertEquals(0, state.pendingMessages.size)
                assertEquals(1, state.messages.size)
                assertEquals("hello world", state.messages[0].content)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `QUEUED transitions to SENDING then FAILED when sendMessage returns false`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade(sendResult = false) }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("no bridge"))
                advanceUntilIdle()

                facadeFactory.lastFacade.value?.emitSessionReady()
                advanceUntilIdle()

                val state = viewModel.state.value
                assertEquals(1, state.pendingMessages.size)
                assertEquals(MessageDeliveryStatus.FAILED, state.pendingMessages[0].status)
                assertEquals("no bridge", state.pendingMessages[0].content)

                val errorEffect = awaitItem()
                assertTrue(errorEffect is ChatEffect.ShowError)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SENDING bubble stays visible and is not removed by unrelated echo`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("my prompt"))
                advanceUntilIdle()

                facadeFactory.lastFacade.value?.emitSessionReady()
                advanceUntilIdle()

                val unrelatedEcho = echoMessage(id = "unrelated", content = "something else")
                facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(unrelatedEcho))
                advanceUntilIdle()

                val state = viewModel.state.value
                assertEquals(1, state.pendingMessages.size)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[0].status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `retry resets FAILED to QUEUED then SENDING, echo removes bubble`() =
        runTest {
            val sendResults = mutableListOf(false, true)
            val facadeFactory =
                TestFacadeFactory {
                    TestFacade(sendResultProvider = { sendResults.removeFirstOrNull() ?: true })
                }
            val viewModel = createFlushedOfflineQueue(facadeFactory = facadeFactory)

            viewModel.effects.test {
                // Consume initial load error + flush failure error from A.
                assertTrue(awaitItem() is ChatEffect.ShowError)
                assertTrue(awaitItem() is ChatEffect.ShowError)

                val state = viewModel.state.value
                val failedA = state.pendingMessages.single { it.content == "A" }
                val clientIdA = failedA.clientId!!
                assertEquals(MessageDeliveryStatus.FAILED, failedA.status)
                assertEquals("A", state.pendingMessages[0].content)
                assertEquals("B", state.messages[0].content)

                retryAndFlushEcho(viewModel, facadeFactory, clientIdA)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a delivered prompt leaves no durable copy behind`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
            val viewModel = createViewModel(facadeFactory = facadeFactory, pendingPromptStore = store)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SendMessage("delivered"))
            advanceUntilIdle()
            assertEquals(1, store.restore("server_1", "session_1").size)

            facadeFactory.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            // The store removes the key once the queue is drained.
            assertEquals(
                emptyList<String>(),
                store.restore("server_1", "session_1").map { it.text },
            )
            val messages = viewModel.state.value.pendingMessages
            assertEquals(MessageDeliveryStatus.SENDING, messages.single().status)
        }

    @Test
    fun `a flush cancelled mid-send keeps the prompt durable`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val facadeFactory = TestFacadeFactory { SuspendingSendFacade() }
            val viewModel = createViewModel(facadeFactory = facadeFactory, pendingPromptStore = store)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SendMessage("survive"))
            advanceUntilIdle()
            assertEquals(1, store.restore("server_1", "session_1").size)

            // Flush starts and blocks inside sendMessage, so the queue is empty in
            // memory while the prompt is still the only thing holding it on disk.
            facadeFactory.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            // Tearing the screen down cancels the in-flight send.
            viewModel.clearForTest()
            advanceUntilIdle()

            assertEquals(
                listOf("survive"),
                store.restore("server_1", "session_1").map { it.text },
            )
        }

    // region: Helpers

    /**
     * Creates a view model, dispatches two messages ("A" then "B") while disconnected,
     * flushes the queue via [TestFacade.emitSessionReady], and returns the view model in
     * the post-flush state where A has FAILED and B has been echoed and removed.
     */
    private suspend fun TestScope.createFlushedOfflineQueue(facadeFactory: TestFacadeFactory): ChatViewModel {
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        advanceUntilIdle()

        viewModel.dispatch(ChatIntent.SendMessage("A"))
        advanceUntilIdle()
        viewModel.dispatch(ChatIntent.SendMessage("B"))
        advanceUntilIdle()

        // Session becomes ready -> flush: A fails, B succeeds.
        facadeFactory.lastFacade.value?.emitSessionReady()
        advanceUntilIdle()

        // B's echo arrives -> B removed from pending, A remains FAILED.
        facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(echoMessage(id = "echo-b", content = "B")))
        advanceUntilIdle()

        return viewModel
    }

    /** Retries the failed message identified by [clientId], then emits its echo and returns the final state. */
    private suspend fun TestScope.retryAndFlushEcho(
        viewModel: ChatViewModel,
        facadeFactory: TestFacadeFactory,
        clientId: String,
    ) {
        // Retry A: sendResults is empty so next send returns true.
        viewModel.dispatch(ChatIntent.RetryMessage(clientId))
        advanceUntilIdle()

        assertPendingMessage(viewModel, clientId, MessageDeliveryStatus.SENDING)

        // A's echo arrives -> A removed from pending, no duplicate in messages.
        facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(echoMessage(id = "echo-a", content = "A")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0, state.pendingMessages.size)
        assertEquals(1, state.messages.size)
        assertEquals("A", state.messages[0].content)
    }

    /** Asserts there is exactly one pending message with the given [clientId] and [status]. */
    private fun assertPendingMessage(
        viewModel: ChatViewModel,
        clientId: String,
        status: MessageDeliveryStatus,
    ) {
        val state = viewModel.state.value
        val msg = state.pendingMessages.single { it.clientId == clientId }
        assertEquals(status, msg.status)
        assertEquals(1, state.pendingMessages.size)
    }

    // endregion
}

/**
 * Facade whose [ChatSessionFacade.sendMessage] never returns, so a test can cancel the
 * screen while a prompt is mid-dispatch. Models a transport that has been handed the
 * prompt but has not yet resolved it.
 */
private class SuspendingSendFacade : TestFacade() {
    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): Boolean = awaitCancellation()
}
