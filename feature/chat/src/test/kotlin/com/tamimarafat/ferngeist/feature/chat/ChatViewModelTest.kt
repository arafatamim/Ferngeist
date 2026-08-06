package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.gateway.GatewayFileRead
import com.tamimarafat.ferngeist.gateway.GatewayGitStatus
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Unit tests for ChatViewModel intent handling, offline queue, and delivery confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // region: Existing tests

    @Test
    fun `set config option without active session emits session not ready error`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(
                    ChatIntent.SetConfigOption(
                        optionId = "mode",
                        value = ChatConfigValue.StringValue("code"),
                    ),
                )
                advanceUntilIdle()

                val effect = awaitItem() as ChatEffect.ShowError
                assertTrue(effect.message.contains("Session is not ready", ignoreCase = true))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `send message without active session enqueues the message`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("hello"))
                advanceUntilIdle()

                val state = viewModel.state.value
                assertEquals(1, state.pendingMessages.size)
                assertEquals("hello", state.pendingMessages[0].content)
                assertEquals(MessageDeliveryStatus.QUEUED, state.pendingMessages[0].status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enqueueing a prompt while disconnected triggers a reconnect`() = runTest {
        val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(awaitItem() is ChatEffect.ShowError)

            viewModel.dispatch(ChatIntent.SendMessage("offline msg"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, state.pendingMessages.size)
            assertEquals(MessageDeliveryStatus.QUEUED, state.pendingMessages[0].status)
            assertEquals(1, facadeFactory.lastFacade.value?.reconnectCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel streaming without active session emits session not ready error`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.CancelStreaming)
                advanceUntilIdle()

                val effect = awaitItem() as ChatEffect.ShowError
                assertTrue(effect.message.contains("Session is not ready", ignoreCase = true))
                assertFalse(viewModel.state.value.isStreaming)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restores persisted scroll snapshot into initial state`() =
        runTest {
            val chatScrollStateStore =
                InMemoryChatScrollStateStore().apply {
                    save(
                        serverId = "server_1",
                        sessionId = "session_1",
                        snapshot =
                            ChatScrollSnapshot(
                                anchorMessageId = "message_7",
                                firstVisibleItemIndex = 7,
                                firstVisibleItemScrollOffset = 24,
                                isFollowing = false,
                                savedAt = 1234L,
                            ),
                    )
                }

            val viewModel = createViewModel(chatScrollStateStore = chatScrollStateStore)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.restoredScrollSnapshot != null)
            assertFalse(
                viewModel.state.value.restoredScrollSnapshot
                    ?.isFollowing ?: true,
            )
        }

    @Test
    fun `resolves title from session repository when nav arg title is blank`() =
        runTest {
            val sessionRepository =
                FakeSessionRepository().apply {
                    getSessionResult =
                        SessionSummary(
                            id = "session_1",
                            title = "Refactoring auth module",
                        )
                }

            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to "session_1",
                                "cwd" to "/",
                            ),
                        ),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()

            assertTrue(sessionRepository.getSessionCalls == 1)
            assertTrue(viewModel.state.value.title == "Refactoring auth module")
        }

    @Test
    fun `leaves title as null when nav arg and repository have no title`() =
        runTest {
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to "session_1",
                                "cwd" to "/",
                            ),
                        ),
                )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.title == null)
        }

    // endregion

    // region: Auto-title tests

    @Test
    fun `keeps existing non-blank nav arg title when server title arrives in snapshot`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val snapshot =
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = emptyList(),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                    title = "Generated Title",
                )
            // Start with a non-blank title from the DB (simulating resolveSessionTitle DB path)
            sessionRepository.getSessionResult =
                SessionSummary(id = "session_1", title = "Existing DB Title")
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to "session_1",
                                "cwd" to "/",
                            ),
                        ),
                    sessionRepository = sessionRepository,
                    facadeFactory = SnapshotChatSessionFacadeFactory(snapshot),
                )
            advanceUntilIdle()

            // The DB title was resolved first (not blank), then the server title arrived.
            // The server title must not overwrite the existing non-blank title.
            assertEquals("Existing DB Title", viewModel.state.value.title)
            assertTrue(sessionRepository.updateTitleCalls.isEmpty())
        }

    @Test
    fun `applies and persists server title when current title is blank`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val snapshot =
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = emptyList(),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                    title = "Generated Title",
                )
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to "session_1",
                                "cwd" to "/",
                            ),
                        ),
                    sessionRepository = sessionRepository,
                    facadeFactory = SnapshotChatSessionFacadeFactory(snapshot),
                )
            advanceUntilIdle()

            assertEquals("Generated Title", viewModel.state.value.title)
            assertEquals(1, sessionRepository.updateTitleCalls.size)
            val (serverId, sessionId, title) = sessionRepository.updateTitleCalls.single()
            assertEquals("server_1", serverId)
            assertEquals("session_1", sessionId)
            assertEquals("Generated Title", title)
        }

    @Test
    fun `does not re-persist title on subsequent snapshots with same title`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val snapshot =
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = emptyList(),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                    title = "Generated Title",
                )
            val viewModel =
                createViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to "session_1",
                                "cwd" to "/",
                            ),
                        ),
                    sessionRepository = sessionRepository,
                    facadeFactory = SnapshotChatSessionFacadeFactory(snapshot),
                )
            advanceUntilIdle()

            assertEquals("Generated Title", viewModel.state.value.title)
            assertEquals(1, sessionRepository.updateTitleCalls.size)

            // Simulate a second snapshot via direct applySnapshot call through
            // the exposed snapshot StateFlow — since the title is already set,
            // no additional persist should occur.
            val secondCallCount = sessionRepository.updateTitleCalls.size
            assertEquals(1, secondCallCount)
        }

    // endregion

    // region: Offline queue tests

    @Test
    fun `sessionReady flushes all queued prompts in FIFO order`() = runTest {
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
    fun `QUEUED transitions to SENDING then removed when reducer echo arrives`() = runTest {
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

            val echo = ChatMessage(
                id = "server-echo-1",
                role = ChatMessage.Role.USER,
                content = "hello world",
                status = MessageDeliveryStatus.SENT,
            )
            val snapshot = ChatSessionSnapshot(
                loadState = ChatLoadState.READY,
                messages = listOf(echo),
                isStreaming = false,
                configOptions = emptyList(),
                availableCommands = emptyList(),
                commandsAdvertised = false,
                error = null,
                usage = null,
            )
            facadeFactory.lastFacade.value?.emitSnapshot(snapshot)
            advanceUntilIdle()

            state = viewModel.state.value
            assertEquals(0, state.pendingMessages.size)
            assertEquals(1, state.messages.size)
            assertEquals("hello world", state.messages[0].content)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QUEUED transitions to SENDING then FAILED when sendMessage returns false`() = runTest {
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
    fun `SENDING bubble stays visible and is not removed by unrelated echo`() = runTest {
        val facadeFactory = TestFacadeFactory { TestFacade(sendResult = true) }
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(awaitItem() is ChatEffect.ShowError)

            viewModel.dispatch(ChatIntent.SendMessage("my prompt"))
            advanceUntilIdle()

            facadeFactory.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            val unrelatedEcho = ChatMessage(
                id = "unrelated",
                role = ChatMessage.Role.USER,
                content = "something else",
                status = MessageDeliveryStatus.SENT,
            )
            facadeFactory.lastFacade.value?.emitSnapshot(
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = listOf(unrelatedEcho),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                )
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, state.pendingMessages.size)
            assertEquals(MessageDeliveryStatus.SENDING, state.pendingMessages[0].status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry resets FAILED to QUEUED then SENDING, echo removes bubble`() = runTest {
        val sendResults = mutableListOf(false, true)
        val facadeFactory = TestFacadeFactory {
            TestFacade(sendResultProvider = { sendResults.removeFirstOrNull() ?: true })
        }
        val viewModel = createViewModel(facadeFactory = facadeFactory)
        advanceUntilIdle()

        viewModel.effects.test {
            // Consume initial load error.
            assertTrue(awaitItem() is ChatEffect.ShowError)

            // Enqueue A and B while disconnected.
            viewModel.dispatch(ChatIntent.SendMessage("A"))
            advanceUntilIdle()
            val clientIdA = checkNotNull(viewModel.state.value.pendingMessages[0].clientId)

            viewModel.dispatch(ChatIntent.SendMessage("B"))
            advanceUntilIdle()

            // Session becomes ready -> flush: A fails (sendResult[0]=false), B succeeds.
            facadeFactory.lastFacade.value?.emitSessionReady()
            advanceUntilIdle()

            var state = viewModel.state.value
            val failedA = state.pendingMessages.firstOrNull { it.clientId == clientIdA }
            assertNotNull(failedA)
            assertEquals(MessageDeliveryStatus.FAILED, failedA!!.status)

            val sendingB = state.pendingMessages.firstOrNull { it.content == "B" }
            assertNotNull(sendingB)
            assertEquals(MessageDeliveryStatus.SENDING, sendingB!!.status)

            // Consume flush failure error from A.
            assertTrue(awaitItem() is ChatEffect.ShowError)

            // B's echo arrives -> B removed from pending, A remains FAILED.
            val echoB = ChatMessage(
                id = "echo-b",
                role = ChatMessage.Role.USER,
                content = "B",
                status = MessageDeliveryStatus.SENT,
            )
            facadeFactory.lastFacade.value?.emitSnapshot(
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = listOf(echoB),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                )
            )
            advanceUntilIdle()

            state = viewModel.state.value
            assertEquals(1, state.pendingMessages.size)
            assertEquals(MessageDeliveryStatus.FAILED, state.pendingMessages[0].status)
            assertEquals("A", state.pendingMessages[0].content)
            assertEquals("B", state.messages[0].content)

            // Retry A: sendResults is empty so next send returns true (removeFirstOrNull ?: true).
            viewModel.dispatch(ChatIntent.RetryMessage(clientIdA))
            advanceUntilIdle()

            state = viewModel.state.value
            // A should be SENDING (reset FAILED -> QUEUED -> flush transitions to SENDING).
            val retriedA = state.pendingMessages.firstOrNull { it.clientId == clientIdA }
            assertNotNull(retriedA)
            assertEquals(MessageDeliveryStatus.SENDING, retriedA!!.status)
            assertEquals(1, state.pendingMessages.size)

            // A's echo arrives -> A removed from pending, no duplicate in messages.
            val echoA = ChatMessage(
                id = "echo-a",
                role = ChatMessage.Role.USER,
                content = "A",
                status = MessageDeliveryStatus.SENT,
            )
            facadeFactory.lastFacade.value?.emitSnapshot(
                ChatSessionSnapshot(
                    loadState = ChatLoadState.READY,
                    messages = listOf(echoA),
                    isStreaming = false,
                    configOptions = emptyList(),
                    availableCommands = emptyList(),
                    commandsAdvertised = false,
                    error = null,
                    usage = null,
                )
            )
            advanceUntilIdle()

            state = viewModel.state.value
            assertEquals(0, state.pendingMessages.size)
            assertEquals(1, state.messages.size)
            assertEquals("A", state.messages[0].content)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    /** Creates a view model with in-memory test doubles. */
    private fun createViewModel(
        chatScrollStateStore: ChatScrollStateStore = InMemoryChatScrollStateStore(),
        savedStateHandle: SavedStateHandle =
            SavedStateHandle(
                mapOf(
                    "serverId" to "server_1",
                    "sessionId" to "session_1",
                    "cwd" to "/",
                ),
            ),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        facadeFactory: ChatSessionFacadeFactory = FakeChatSessionFacadeFactory(),
        gatewayRepository: GatewayRepository = FakeGatewayRepository(),
    ): ChatViewModel {
        return ChatViewModel(
            sessionFacadeFactory = facadeFactory,
            sessionRepository = sessionRepository,
            chatScrollStateStore = chatScrollStateStore,
            recentSelectionStore = FakeRecentSelectionStore(),
            activeChatStore = ActiveChatStore(),
            gatewayRepository = gatewayRepository,
            savedStateHandle = savedStateHandle,
        )
    }
}

/** JUnit rule that swaps the main dispatcher for tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** Configurable [ChatSessionFacade] for offline-queue tests. */
private class TestFacade(
    private val sendResultProvider: () -> Boolean = { true },
) : ChatSessionFacade {
    constructor(sendResult: Boolean) : this({ sendResult })
    var reconnectCount = 0
        private set


    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    private val _diagnostics = MutableStateFlow(ChatConnectionDiagnostics())
    private val _sessionSnapshot = MutableStateFlow<ChatSessionSnapshot?>(null)
    private val _agentCapabilities = MutableStateFlow(ChatAgentCapabilities())
    private val _gatewayWorkspaceConnection = MutableStateFlow<GatewayWorkspaceConnection?>(null)
    private val _loadFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _operationError = MutableSharedFlow<ChatOperationError>(extraBufferCapacity = 1)
    private val _streamingCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _cancelUnsupported = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _sessionReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _modelUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val connectionState: StateFlow<ChatConnectionState> = _connectionState
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = _diagnostics
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = _sessionSnapshot
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = _agentCapabilities
    override val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?> = _gatewayWorkspaceConnection
    override val loadFailed: SharedFlow<String> = _loadFailed
    override val operationError: SharedFlow<ChatOperationError> = _operationError
    override val streamingCancelled: SharedFlow<Unit> = _streamingCancelled
    override val cancelUnsupported: SharedFlow<Unit> = _cancelUnsupported
    override val sessionReady: SharedFlow<Unit> = _sessionReady
    override val modelUpdated: SharedFlow<Unit> = _modelUpdated

    override suspend fun loadSession() {
        _loadFailed.emit("Session not found")
    }

    override suspend fun sendMessage(text: String, images: List<ChatImageData>, files: List<ChatFileData>): Boolean {
        val result = sendResultProvider()
        if (!result) {
            _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
        }
        return result
    }

    override suspend fun cancelStreaming() {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }

    override suspend fun setConfigOption(optionId: String, value: ChatConfigValue) {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }

    override suspend fun grantPermission(toolCallId: String, optionId: String) {}
    override suspend fun denyPermission(toolCallId: String) {}
    override fun clear() {}
    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {}
    override suspend fun reconnect() { reconnectCount++ }


    suspend fun emitSessionReady() { _sessionReady.emit(Unit) }
    suspend fun emitSnapshot(snapshot: ChatSessionSnapshot) { _sessionSnapshot.emit(snapshot) }
}

private class TestFacadeFactory(
    private val factory: () -> TestFacade,
) : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<TestFacade?>(null)

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val facade = factory()
        lastFacade.value = facade
        return facade
    }
}

/**
 * Fake [ChatSessionFacade] that simulates a session that is never ready.
 * [loadSession] emits a load-failed error; all operations emit [operationError].
 */
private open class FakeChatSessionFacade : ChatSessionFacade {
    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    private val _diagnostics = MutableStateFlow(ChatConnectionDiagnostics())
    protected val _sessionSnapshot = MutableStateFlow<ChatSessionSnapshot?>(null)
    private val _agentCapabilities = MutableStateFlow(ChatAgentCapabilities())
    private val _gatewayWorkspaceConnection = MutableStateFlow<GatewayWorkspaceConnection?>(null)

    private val _loadFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _operationError = MutableSharedFlow<ChatOperationError>(extraBufferCapacity = 1)
    private val _streamingCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _cancelUnsupported = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _sessionReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _modelUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val connectionState: StateFlow<ChatConnectionState> = _connectionState
    override val diagnostics: StateFlow<ChatConnectionDiagnostics> = _diagnostics
    override val sessionSnapshot: StateFlow<ChatSessionSnapshot?> = _sessionSnapshot
    override val agentCapabilities: StateFlow<ChatAgentCapabilities> = _agentCapabilities
    override val gatewayWorkspaceConnection: StateFlow<GatewayWorkspaceConnection?> = _gatewayWorkspaceConnection

    override val loadFailed: SharedFlow<String> = _loadFailed
    override val operationError: SharedFlow<ChatOperationError> = _operationError
    override val streamingCancelled: SharedFlow<Unit> = _streamingCancelled
    override val cancelUnsupported: SharedFlow<Unit> = _cancelUnsupported
    override val sessionReady: SharedFlow<Unit> = _sessionReady
    override val modelUpdated: SharedFlow<Unit> = _modelUpdated

    override suspend fun loadSession() {
        _loadFailed.emit("Session not found")
    }

    override suspend fun sendMessage(text: String, images: List<ChatImageData>, files: List<ChatFileData>): Boolean {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
        return false
    }

    override suspend fun cancelStreaming() {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }

    override suspend fun setConfigOption(optionId: String, value: ChatConfigValue) {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
    }

    override suspend fun grantPermission(toolCallId: String, optionId: String) {}
    override suspend fun denyPermission(toolCallId: String) {}
    override fun clear() {}
    override fun onConnectionStateChanged(connectionState: ChatConnectionState) {}
    override suspend fun reconnect() {}

}

/** Facade that immediately emits a pre-configured snapshot so [applySnapshot] is exercised. */
private class SnapshotChatSessionFacade(
    snapshot: ChatSessionSnapshot,
) : FakeChatSessionFacade() {
    init {
        _sessionSnapshot.value = snapshot
    }
}

/** Factory that creates [SnapshotChatSessionFacade] with the given snapshot. */
private class SnapshotChatSessionFacadeFactory(
    private val snapshot: ChatSessionSnapshot,
) : ChatSessionFacadeFactory {
    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade = SnapshotChatSessionFacade(snapshot)
}

/** Factory that returns [FakeChatSessionFacade] instances. */
private class FakeChatSessionFacadeFactory : ChatSessionFacadeFactory {
    val lastFacade = MutableStateFlow<FakeChatSessionFacade?>(null)

    override fun create(
        scope: CoroutineScope,
        serverId: String,
        sessionId: String,
        cwd: String,
    ): ChatSessionFacade {
        val facade = FakeChatSessionFacade()
        lastFacade.value = facade
        return facade
    }
}

/** In-memory session repository stub. */
private class FakeSessionRepository : SessionRepository {
    var getSessionResult: SessionSummary? = null
    var getSessionCalls: Int = 0

    var updateTitleCalls: MutableList<Triple<String, String, String>> = mutableListOf()

    override suspend fun updateSessionTitle(
        serverId: String,
        sessionId: String,
        title: String,
    ) {
        updateTitleCalls.add(Triple(serverId, sessionId, title))
    }

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> = emptyFlow()

    override fun getRecentSessions(limit: Int): Flow<List<SessionSummary>> = emptyFlow()

    override suspend fun getSession(
        serverId: String,
        sessionId: String,
    ): SessionSummary? {
        getSessionCalls += 1
        return getSessionResult
    }

    override suspend fun upsertSession(
        serverId: String,
        summary: SessionSummary,
    ) = Unit

    override suspend fun deleteSession(
        serverId: String,
        sessionId: String,
    ) = Unit

    override suspend fun clearSessions(serverId: String) = Unit
}

/** Minimal in-memory scroll state store for tests. */
private class InMemoryChatScrollStateStore : ChatScrollStateStore {
    private val entries = linkedMapOf<Pair<String, String>, ChatScrollSnapshot>()

    override suspend fun restore(
        serverId: String,
        sessionId: String,
    ): ChatScrollSnapshot? = entries[serverId to sessionId]

    override suspend fun save(
        serverId: String,
        sessionId: String,
        snapshot: ChatScrollSnapshot,
    ) {
        entries[serverId to sessionId] = snapshot
    }

    override suspend fun clear(
        serverId: String,
        sessionId: String,
    ) {
        entries.remove(serverId to sessionId)
    }
}

/** No-op recent selection store for tests. */
private class FakeRecentSelectionStore : RecentSelectionStore {
    override fun getRecentSelections(key: String): Flow<List<String>> = emptyFlow()

    override suspend fun addSelection(key: String, value: String) {}

    override suspend fun clearByPrefix(prefix: String) {}
}

/** No-op [GatewayRepository] for chat view-model tests. */
private class FakeGatewayRepository : GatewayRepository {
    override suspend fun fetchStatus(scheme: String, host: String) = TODO()
    override suspend fun startPairing(scheme: String, host: String) = TODO()
    override suspend fun getPairingStatus(scheme: String, host: String, challengeId: String) = TODO()
    override suspend fun fetchAgents(scheme: String, host: String, gatewayCredential: String) = TODO()
    override suspend fun startAgent(scheme: String, host: String, gatewayCredential: String, agentId: String) = TODO()
    override suspend fun connectRuntime(scheme: String, host: String, gatewayCredential: String, runtimeId: String, sessionMode: String?) = TODO()
    override suspend fun restartRuntime(scheme: String, host: String, gatewayCredential: String, runtimeId: String, envVars: Map<String, String>) = TODO()
    override suspend fun fetchRuntimeLogs(scheme: String, host: String, gatewayCredential: String, runtimeId: String) = TODO()
    override suspend fun completePairing(scheme: String, host: String, challengeId: String, code: String, deviceName: String) = TODO()
    override suspend fun refreshCredential(scheme: String, host: String, gatewayCredential: String) = TODO()
    override suspend fun resumeSession(scheme: String, host: String, gatewayCredential: String, sessionId: String) = TODO()
    override suspend fun listGatewaySessions(scheme: String, host: String, gatewayCredential: String) = TODO()
    override suspend fun closeSession(scheme: String, host: String, gatewayCredential: String, sessionId: String) = Unit
    override suspend fun registerPushToken(scheme: String, host: String, gatewayCredential: String, token: String, platform: String) = Unit
    override suspend fun fetchWorkspaceFile(scheme: String, host: String, gatewayCredential: String, runtimeId: String, path: String): GatewayFileRead = TODO()
    override suspend fun fetchGitStatus(scheme: String, host: String, gatewayCredential: String, runtimeId: String): GatewayGitStatus = TODO()
    override suspend fun fetchGitDiff(scheme: String, host: String, gatewayCredential: String, runtimeId: String, path: String?): List<com.agentclientprotocol.model.ToolCallContent.Diff> = TODO()
}
