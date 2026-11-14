package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.QueuedPromptRecord
import com.tamimarafat.ferngeist.core.model.SteerOutcome
import com.tamimarafat.ferngeist.core.model.content
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
            val sent = mutableListOf<String>()
            val facadeFactory =
                TestFacadeFactory {
                    object : TestFacade(sendResult = true) {
                        override suspend fun sendMessage(
                            text: String,
                            images: List<ChatImageData>,
                            files: List<ChatFileData>,
                            clientId: String,
                        ): Boolean {
                            sent += text
                            return true
                        }
                    }
                }
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

                assertEquals(listOf("first", "second"), sent)

                state = viewModel.state.value
                assertEquals(2, state.pendingMessages.size)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[0].status)
                assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[1].status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a prompt queued during a running turn waits for the turn to end`() =
        runTest {
            val sent = mutableListOf<String>()
            val facadeFactory =
                TestFacadeFactory {
                    object : TestFacade(sendResult = true) {
                        override suspend fun sendMessage(
                            text: String,
                            images: List<ChatImageData>,
                            files: List<ChatFileData>,
                            clientId: String,
                        ): Boolean {
                            sent += text
                            return true
                        }
                    }
                }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()
            val facade = facadeFactory.lastFacade.value!!
            facade.emitSnapshot(readySnapshot(isStreaming = true))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SendMessage("next"))
            facade.emitSessionReady()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), sent)
            val queued = viewModel.state.value.pendingMessages
            assertEquals(MessageDeliveryStatus.QUEUED, queued.single().status)

            facade.emitSnapshot(readySnapshot(isStreaming = false))
            advanceUntilIdle()
            assertEquals(listOf("next"), sent)
        }

    @Test
    fun `a prompt queued mid-turn is steered into it without waiting for the turn`() =
        runTest {
            val facade = SteeringFacade(SteerOutcome.INJECTED)
            val viewModel = steeringViewModel(facade)

            assertEquals(listOf("nudge"), facade.steered)
            facade.emitSnapshot(readySnapshot(messages = listOf(viewModel.echoOf("nudge")), isStreaming = true))
            advanceUntilIdle()
            facade.emitSnapshot(readySnapshot(isStreaming = false))
            advanceUntilIdle()

            assertEquals(emptyList<String>(), facade.sent)
            assertTrue(
                viewModel.state.value.pendingMessages
                    .isEmpty(),
            )
        }

    @Test
    fun `a prompt queued during this screen's own turn is steered into it`() =
        runTest {
            // A real send holds until its turn ends; the next prompt must not wait behind it.
            val facade = SteeringFacade(SteerOutcome.INJECTED, holdSends = true)
            val viewModel = createViewModel(facadeFactory = TestFacadeFactory { facade })
            advanceUntilIdle()
            facade.connect()
            viewModel.dispatch(ChatIntent.SendMessage("go"))
            advanceUntilIdle()
            facade.emitSessionReady()
            advanceUntilIdle()
            facade.emitSnapshot(readySnapshot(isStreaming = true))
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SendMessage("nudge"))
            advanceUntilIdle()

            assertEquals(listOf("go"), facade.sent)
            assertEquals(listOf("nudge"), facade.steered)
            viewModel.clearForTest()
            advanceUntilIdle()
        }

    @Test
    fun `a steer the agent does not take waits for the turn like any queued prompt`() =
        runTest {
            val facade = SteeringFacade(SteerOutcome.NOT_CONSUMED)
            val viewModel = steeringViewModel(facade)

            assertEquals(listOf("nudge"), facade.steered)
            assertEquals(
                MessageDeliveryStatus.QUEUED,
                viewModel.state.value.pendingMessages
                    .single()
                    .status,
            )
            assertEquals(emptyList<String>(), facade.sent)

            facade.emitSnapshot(readySnapshot(isStreaming = false))
            advanceUntilIdle()
            assertEquals(listOf("nudge"), facade.sent)
        }

    @Test
    fun `a force-sent prompt stops the turn and goes out with those queued ahead of it`() =
        runTest {
            val facade = CancellingFacade()
            val viewModel = createViewModel(facadeFactory = TestFacadeFactory { facade })
            advanceUntilIdle()
            facade.emitSnapshot(readySnapshot(isStreaming = true))
            advanceUntilIdle()
            listOf("first", "second", "third").forEach { viewModel.dispatch(ChatIntent.SendMessage(it)) }
            facade.emitSessionReady()
            advanceUntilIdle()

            val pending = viewModel.state.value.pendingMessages
            viewModel.dispatch(ChatIntent.ForceSendMessage(pending[1].clientId!!))
            advanceUntilIdle()

            assertEquals(1, facade.cancels)
            facade.emitSnapshot(readySnapshot(isStreaming = false))
            advanceUntilIdle()
            // The folded prompt opens the next turn; the one behind it follows as usual.
            assertEquals(listOf("first\n\nsecond", "third"), facade.sent)
        }

    @Test
    fun `a removed prompt is never sent and leaves no durable copy`() =
        runTest {
            val facade = SteeringFacade(SteerOutcome.NOT_CONSUMED)
            val store = InMemoryPendingPromptStore()
            val viewModel = steeringViewModel(facade, store)

            viewModel.dispatch(
                ChatIntent.RemoveQueuedMessage(
                    viewModel.state.value.pendingMessages
                        .single()
                        .clientId!!,
                ),
            )
            advanceUntilIdle()
            facade.emitSnapshot(readySnapshot(isStreaming = false))
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.pendingMessages
                    .isEmpty(),
            )
            assertEquals(emptyList<String>(), facade.sent)
            assertTrue(store.restore("server_1", "session_1").isEmpty())
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

                facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(viewModel.echoOf("hello world")))
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
    fun `a send that fails after its local copy reconciled the bubble leaves it FAILED`() =
        runTest {
            val facadeFactory =
                TestFacadeFactory {
                    object : TestFacade(sendResult = false) {
                        override suspend fun sendMessage(
                            text: String,
                            images: List<ChatImageData>,
                            files: List<ChatFileData>,
                            clientId: String,
                        ): Boolean {
                            // The runtime's local copy lands first, then the failure rolls it back.
                            emitSnapshot(snapshotWithEcho(echoMessage(content = text, clientId = clientId)))
                            delay(1)
                            emitSnapshot(readySnapshot())
                            return false
                        }
                    }
                }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()

            viewModel.dispatch(ChatIntent.SendMessage("lost"))
            facadeFactory.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            val pending =
                viewModel.state.value.pendingMessages
                    .single()
            assertEquals(MessageDeliveryStatus.FAILED, pending.status)
            assertEquals("lost", pending.content)
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
            val viewModel =
                createViewModel(facadeFactory = facadeFactory, stores = testStores(pendingPromptStore = store))
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
            val viewModel =
                createViewModel(facadeFactory = facadeFactory, stores = testStores(pendingPromptStore = store))
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

    @Test
    fun `a prompt cut off mid-turn whose delivery the transcript proves is not resent`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "once")

            val reopened = TestFacade(sendResult = true)
            val delivered = echoMessage(content = "once", clientId = clientId).copy(deliveryConfirmed = true)
            val viewModel = reopenOver(store, reopened, transcript = listOf(delivered), dropsRepeatPrompts = false)

            assertEquals(emptyList<String>(), reopened.sentClientIds)
            assertEquals(emptyList<ChatMessage>(), viewModel.state.value.pendingMessages)
            assertEquals(emptyList<QueuedPromptRecord>(), store.restore("server_1", "session_1"))
        }

    @Test
    fun `an unconfirmed prompt cut off mid-turn is resent under its id to a gateway that refuses repeats`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "once")

            // The saved local bubble carries the id too, so it proves nothing about delivery.
            val reopened = TestFacade(sendResult = true)
            val transcript = listOf(echoMessage(content = "once", clientId = clientId))
            reopenOver(store, reopened, transcript, dropsRepeatPrompts = true)

            assertEquals(listOf(clientId), reopened.sentClientIds)
        }

    @Test
    fun `an unconfirmed prompt cut off mid-turn is left to the user where a resend could run it twice`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "once")

            val reopened = TestFacade(sendResult = true)
            val transcript = listOf(echoMessage(content = "once", clientId = clientId))
            val viewModel = reopenOver(store, reopened, transcript, dropsRepeatPrompts = false)

            assertEquals(emptyList<String>(), reopened.sentClientIds)
            val pending =
                viewModel.state.value.pendingMessages
                    .single()
            assertEquals(clientId to MessageDeliveryStatus.FAILED, pending.clientId to pending.status)
        }

    @Test
    fun `an unconfirmed prompt waits for the attach to learn what the gateway remembers`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "once")

            val reopened = TestFacade(sendResult = true)
            reopenOver(store, reopened, transcript = emptyList(), dropsRepeatPrompts = null)
            assertEquals("restored, not yet attached", emptyList<String>(), reopened.sentClientIds)

            reopened.emitSnapshot(readySnapshot(dropsRepeatPrompts = true))
            advanceUntilIdle()

            assertEquals(listOf(clientId), reopened.sentClientIds)
        }

    @Test
    fun `enqueue during attach settlement keeps the restored prompt durable after cancellation`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "first")
            val reopened = TestFacade(sendResult = true)
            val viewModel = reopenOver(store, reopened, transcript = emptyList(), dropsRepeatPrompts = null)

            viewModel.dispatch(ChatIntent.SendMessage("second"))
            advanceUntilIdle()
            viewModel.clearForTest()
            advanceUntilIdle()

            val records = store.restore("server_1", "session_1")
            assertEquals(listOf("first", "second"), records.map { it.text })
            assertEquals(clientId, records.first().clientId)
            assertTrue(records.first().dispatched)
        }

    @Test
    fun `a prompt cut off mid-turn shows once while its turn still runs after reopen`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            val clientId = sendCutOffMidTurn(store, "once")

            val reopened = TestFacade(sendResult = true)
            val viewModel =
                createViewModel(
                    facadeFactory = TestFacadeFactory { reopened },
                    stores = testStores(pendingPromptStore = store),
                )
            advanceUntilIdle()
            val transcript = listOf(echoMessage(content = "once", clientId = clientId))
            reopened.emitSnapshot(readySnapshot(messages = transcript, isStreaming = true))
            advanceUntilIdle()

            val pending = viewModel.state.value.pendingMessages
            assertEquals("the transcript's copy is the only bubble", emptyList<ChatMessage>(), pending)
            viewModel.clearForTest()
            advanceUntilIdle()
        }

    @Test
    fun `a restored prompt is sent once a quiet load makes the session ready`() =
        runTest {
            val record = QueuedPromptRecord("c-1", "later", emptyList(), emptyList(), dispatched = false)
            val store = InMemoryPendingPromptStore(mapOf(("server_1" to "session_1") to listOf(record)))
            val facade =
                object : TestFacade(sendResult = true) {
                    init {
                        connectionStateFlow.value = ChatConnectionState.Connected
                    }
                }
            createViewModel(
                facadeFactory = TestFacadeFactory { facade },
                stores = testStores(pendingPromptStore = store),
            )
            advanceUntilIdle()

            // A session/load announces no sessionReady: the ready snapshot is the only cue.
            facade.emitSnapshot(readySnapshot())
            advanceUntilIdle()

            assertEquals(listOf("c-1"), facade.sentClientIds)
        }

    /** Sends [text] on a screen that closes while its turn runs; returns the prompt's client id. */
    private suspend fun TestScope.sendCutOffMidTurn(
        store: InMemoryPendingPromptStore,
        text: String,
    ): String {
        // The real sendMessage returns only when the agent's turn ends.
        val facadeFactory = TestFacadeFactory { SuspendingSendFacade() }
        val viewModel = createViewModel(facadeFactory = facadeFactory, stores = testStores(pendingPromptStore = store))
        advanceUntilIdle()
        viewModel.dispatch(ChatIntent.SendMessage(text))
        advanceUntilIdle()
        facadeFactory.lastFacade.value?.emitSessionReady()
        advanceUntilIdle()
        viewModel.clearForTest()
        advanceUntilIdle()
        val record = store.restore("server_1", "session_1").single()
        assertTrue("kept until its turn ends", record.dispatched)
        return record.clientId
    }

    /**
     * Reopens the chat over [store] with [transcript] caught up by an attach that found
     * [dropsRepeatPrompts], then lets the queue flush. The screen stays open: a flush still
     * waiting must not be cut short.
     */
    private suspend fun TestScope.reopenOver(
        store: InMemoryPendingPromptStore,
        facade: TestFacade,
        transcript: List<ChatMessage>,
        dropsRepeatPrompts: Boolean?,
    ): ChatViewModel {
        val viewModel =
            createViewModel(
                facadeFactory = TestFacadeFactory { facade },
                stores = testStores(pendingPromptStore = store),
            )
        advanceUntilIdle()
        facade.emitSnapshot(readySnapshot(messages = transcript, dropsRepeatPrompts = dropsRepeatPrompts))
        advanceUntilIdle()
        facade.emitSessionReady()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a prompt left by a torn-down new chat is not replayed into the next new chat`() =
        runTest {
            val store = InMemoryPendingPromptStore()
            // Chat A: create-on-arrival, minted by the transport, torn down mid-send.
            val facadeA = TestFacadeFactory { SuspendingSendFacade() }
            val chatA =
                createViewModel(
                    facadeFactory = facadeA,
                    savedStateHandle = newChatSavedStateHandle(),
                    stores = testStores(pendingPromptStore = store),
                )
            advanceUntilIdle()
            facadeA.lastFacade.value?.emitLiveChatId("server_1/session_a")
            advanceUntilIdle()

            chatA.dispatch(ChatIntent.SendMessage("wipe the workspace"))
            advanceUntilIdle()
            facadeA.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()
            chatA.clearForTest()
            advanceUntilIdle()

            // Chat B: the next create-on-arrival chat of the same server, which must
            // not adopt chat A's prompt and dispatch it into its own session.
            val facadeB = TestFacadeFactory { TestFacade(sendResult = true) }
            val chatB =
                createViewModel(
                    facadeFactory = facadeB,
                    savedStateHandle = newChatSavedStateHandle(),
                    stores = testStores(pendingPromptStore = store),
                )
            advanceUntilIdle()
            facadeB.lastFacade.value?.emitLiveChatId("server_1/session_b")
            facadeB.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            assertEquals(
                emptyList<String>(),
                chatB.state.value.pendingMessages
                    .map { it.content },
            )
            assertEquals(emptyList<String>(), store.restore("server_1", "session_b").map { it.text })
            // Nothing is left staged under the shared sentinel either.
            assertEquals(emptyList<String>(), store.restore("server_1", NEW_SESSION_ARG).map { it.text })

            // The prompt stays durable under the id chat A minted, so dropping it from
            // the sentinel loses nothing.
            assertEquals(
                listOf("wipe the workspace"),
                store.restore("server_1", "session_a").map { it.text },
            )
        }

    // region: Helpers

    /** Nav args for a create-on-arrival chat, whose nav arg is the [NEW_SESSION_ARG] sentinel. */
    private fun newChatSavedStateHandle(): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "serverId" to "server_1",
                "sessionId" to NEW_SESSION_ARG,
                "cwd" to "/",
            ),
        )

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
        facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(viewModel.echoOf("B", id = "echo-b")))
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
        facadeFactory.lastFacade.value?.emitSnapshot(snapshotWithEcho(viewModel.echoOf("A", id = "echo-a")))
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

    /** A view model mid-turn on a steering-capable agent, with "nudge" queued. */
    private suspend fun TestScope.steeringViewModel(
        facade: SteeringFacade,
        store: InMemoryPendingPromptStore = InMemoryPendingPromptStore(),
    ): ChatViewModel {
        val viewModel =
            createViewModel(
                facadeFactory = TestFacadeFactory { facade },
                stores = testStores(pendingPromptStore = store),
            )
        advanceUntilIdle()
        facade.emitSnapshot(readySnapshot(isStreaming = true))
        advanceUntilIdle()
        viewModel.dispatch(ChatIntent.SendMessage("nudge"))
        facade.emitSessionReady()
        advanceUntilIdle()
        return viewModel
    }

    // endregion
}

private class CancellingFacade : TestFacade() {
    val sent = mutableListOf<String>()
    var cancels = 0

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String,
    ): Boolean {
        sent += text
        return true
    }

    override suspend fun cancelStreaming() {
        cancels++
    }
}

private class SteeringFacade(
    private val outcome: SteerOutcome,
    private val holdSends: Boolean = false,
) : TestFacade() {
    val sent = mutableListOf<String>()
    val steered = mutableListOf<String>()

    init {
        agentCapabilitiesFlow.value = ChatAgentCapabilities(supportsSteering = true)
    }

    fun connect() {
        connectionStateFlow.value = ChatConnectionState.Connected
    }

    override suspend fun sendMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String,
    ): Boolean {
        sent += text
        if (holdSends) awaitCancellation()
        return true
    }

    override suspend fun steerMessage(
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        clientId: String,
    ): SteerOutcome {
        steered += text
        return outcome
    }
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
        clientId: String,
    ): Boolean = awaitCancellation()
}
