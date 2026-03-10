package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.core.model.ServerConfig
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.ServerRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `set mode without active session emits session not ready error`() = runTest {
        val viewModel = createViewModel()

        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(awaitItem() is ChatEffect.ShowError)

            viewModel.dispatch(ChatIntent.SetMode("code"))
            advanceUntilIdle()

            val effect = awaitItem() as ChatEffect.ShowError
            assertTrue(effect.message.contains("Session is not ready", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `send message without active session emits session not ready error`() = runTest {
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
    fun `cancel streaming without active session emits session not ready error`() = runTest {
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
    fun `toggle tool call expansion adds and removes ids`() = runTest {
        val viewModel = createViewModel()

        advanceUntilIdle()

        viewModel.dispatch(ChatIntent.ToggleToolCallExpansion("tool_1"))
        advanceUntilIdle()
        assertTrue("tool_1" in viewModel.state.value.expandedToolCalls)

        viewModel.dispatch(ChatIntent.ToggleToolCallExpansion("tool_1"))
        advanceUntilIdle()
        assertFalse("tool_1" in viewModel.state.value.expandedToolCalls)
    }

    private fun createViewModel(): ChatViewModel {
        val connectivityObserver = ConnectivityObserverStub(initialState = false)
        val manager = AcpConnectionManager(
            connectivityObserver = connectivityObserver,
            scope = CoroutineScope(Dispatchers.Main),
        )
        val serverRepository = FakeServerRepository()
        val sessionRepository = FakeSessionRepository()
        val handle = SavedStateHandle(
            mapOf(
                "serverId" to "server_1",
                "sessionId" to "session_1",
                "cwd" to "/",
            )
        )

        return ChatViewModel(
            connectionManager = manager,
            serverRepository = serverRepository,
            sessionRepository = sessionRepository,
            savedStateHandle = handle,
        )
    }
}

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

private class ConnectivityObserverStub(
    initialState: Boolean,
) : ConnectivityObserver {
    private val isConnectedFlow = MutableStateFlow(initialState)
    override val isConnected: Flow<Boolean> = isConnectedFlow
}

private class FakeServerRepository : ServerRepository {
    override fun getServers(): Flow<List<ServerConfig>> = emptyFlow()
    override suspend fun addServer(config: ServerConfig) = Unit
    override suspend fun updateServer(config: ServerConfig) = Unit
    override suspend fun deleteServer(id: String) = Unit
    override suspend fun getServer(id: String): ServerConfig? = null
}

private class FakeSessionRepository : SessionRepository {
    override fun getSessions(serverId: String): Flow<List<SessionSummary>> = emptyFlow()
    override suspend fun upsertSession(serverId: String, summary: SessionSummary) = Unit
    override suspend fun deleteSession(serverId: String, sessionId: String) = Unit
    override suspend fun clearSessions(serverId: String) = Unit
}
