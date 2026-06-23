package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.ChatAgentCapabilities
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatOperationError
import com.tamimarafat.ferngeist.core.model.ChatSessionFacade
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Unit tests for ChatViewModel intent handling and initial state. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun `send message without active session emits session not ready error`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.effects.test {
                assertTrue(awaitItem() is ChatEffect.ShowError)

                viewModel.dispatch(ChatIntent.SendMessage("hello"))
                advanceUntilIdle()

                val effect = awaitItem() as ChatEffect.ShowError
                assertTrue(effect.message.contains("Session is not ready", ignoreCase = true))
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
    ): ChatViewModel {
        val facadeFactory =
            FakeChatSessionFacadeFactory()
        return ChatViewModel(
            sessionFacadeFactory = facadeFactory,
            sessionRepository = sessionRepository,
            chatScrollStateStore = chatScrollStateStore,
            recentSelectionStore = FakeRecentSelectionStore(),
            activeChatStore = ActiveChatStore(),
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

/**
 * Fake [ChatSessionFacade] that simulates a session that is never ready.
 * [loadSession] emits a load-failed error; all operations emit [operationError].
 */
private class FakeChatSessionFacade : ChatSessionFacade {
    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Disconnected)
    private val _diagnostics = MutableStateFlow(ChatConnectionDiagnostics())
    private val _sessionSnapshot = MutableStateFlow<ChatSessionSnapshot?>(null)
    private val _agentCapabilities = MutableStateFlow(ChatAgentCapabilities())

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

    override val loadFailed: SharedFlow<String> = _loadFailed
    override val operationError: SharedFlow<ChatOperationError> = _operationError
    override val streamingCancelled: SharedFlow<Unit> = _streamingCancelled
    override val cancelUnsupported: SharedFlow<Unit> = _cancelUnsupported
    override val sessionReady: SharedFlow<Unit> = _sessionReady
    override val modelUpdated: SharedFlow<Unit> = _modelUpdated

    override suspend fun loadSession() {
        _loadFailed.emit("Session not found")
    }

    override suspend fun sendMessage(text: String, images: List<ChatImageData>) {
        _operationError.emit(ChatOperationError("Session is not ready. Please retry in a moment.", false))
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

    override fun getSessions(serverId: String): Flow<List<SessionSummary>> = emptyFlow()

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
