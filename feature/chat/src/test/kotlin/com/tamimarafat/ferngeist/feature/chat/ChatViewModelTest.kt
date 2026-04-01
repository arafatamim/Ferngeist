package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.acp.bridge.connection.AcpConnectionManager
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.session.SessionConfigValue
import com.tamimarafat.ferngeist.core.model.LaunchableTarget
import com.tamimarafat.ferngeist.core.model.LaunchableTargetSessionSettings
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetSessionSettingsRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    fun `set config option without active session emits session not ready error`() = runTest {
        val viewModel = createViewModel()

        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(awaitItem() is ChatEffect.ShowError)

            viewModel.dispatch(
                ChatIntent.SetConfigOption(
                    optionId = "mode",
                    value = SessionConfigValue.StringValue("code"),
                )
            )
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
    fun `restores persisted scroll snapshot into initial state`() = runTest {
        val chatScrollStateStore = InMemoryChatScrollStateStore().apply {
            save(
                serverId = "server_1",
                sessionId = "session_1",
                snapshot = ChatScrollSnapshot(
                    anchorMessageId = "message_7",
                    firstVisibleItemIndex = 7,
                    firstVisibleItemScrollOffset = 24,
                    isFollowing = false,
                    savedAt = 1234L,
                ),
            )
        }

        val viewModel = createViewModel(chatScrollStateStore = chatScrollStateStore)

        assertTrue(viewModel.state.value.restoredScrollSnapshot != null)
        assertFalse(viewModel.state.value.restoredScrollSnapshot?.isFollowing ?: true)
    }

    private fun createViewModel(
        chatScrollStateStore: ChatScrollStateStore = InMemoryChatScrollStateStore(),
    ): ChatViewModel {
        val connectivityObserver = ConnectivityObserverStub(initialState = false)
        val manager = AcpConnectionManager(
            connectivityObserver = connectivityObserver,
            scope = CoroutineScope(Dispatchers.Main),
        )
        val targetRepository = FakeLaunchableTargetRepository()
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
            launchableTargetRepository = targetRepository,
            sessionRepository = sessionRepository,
            helperRepository = FakeDesktopHelperRepository(),
            chatScrollStateStore = chatScrollStateStore,
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

private class FakeLaunchableTargetRepository : LaunchableTargetRepository {
    override fun getTargets(): Flow<List<LaunchableTarget>> = emptyFlow()
    override suspend fun getTarget(id: String): LaunchableTarget? = null
    override suspend fun updatePreferredAuthMethod(targetId: String, methodId: String) = Unit
    override suspend fun deleteTarget(id: String) = Unit
}

private class FakeSessionRepository : SessionRepository {
    override fun getSessions(serverId: String): Flow<List<SessionSummary>> = emptyFlow()
    override suspend fun upsertSession(serverId: String, summary: SessionSummary) = Unit
    override suspend fun deleteSession(serverId: String, sessionId: String) = Unit
    override suspend fun clearSessions(serverId: String) = Unit
}

private class FakeDesktopHelperRepository : com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRepository {
    override suspend fun fetchStatus(scheme: String, host: String): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperStatus = throw NotImplementedError()
    override suspend fun fetchAgents(scheme: String, host: String, helperCredential: String): List<com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperAgent> = emptyList()
    override suspend fun startAgent(scheme: String, host: String, helperCredential: String, agentId: String): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperRuntime = throw NotImplementedError()
    override suspend fun connectRuntime(scheme: String, host: String, helperCredential: String, runtimeId: String): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperConnectResponse = throw NotImplementedError()
    override suspend fun restartRuntime(scheme: String, host: String, helperCredential: String, runtimeId: String, envVars: Map<String, String>): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperConnectResponse = throw NotImplementedError()
    override suspend fun fetchRuntimeLogs(scheme: String, host: String, helperCredential: String, runtimeId: String): List<com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperLogEntry> = emptyList()
    override suspend fun startPairing(scheme: String, host: String): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingChallenge = throw NotImplementedError()
    override suspend fun completePairing(scheme: String, host: String, challengeId: String, code: String, deviceName: String): com.tamimarafat.ferngeist.feature.serverlist.helper.DesktopHelperPairingResult = throw NotImplementedError()
}

private class InMemoryChatScrollStateStore : ChatScrollStateStore {
    private val entries = linkedMapOf<Pair<String, String>, ChatScrollSnapshot>()

    override fun restore(serverId: String, sessionId: String): ChatScrollSnapshot? {
        return entries[serverId to sessionId]
    }

    override fun save(serverId: String, sessionId: String, snapshot: ChatScrollSnapshot) {
        entries[serverId to sessionId] = snapshot
    }

    override fun clear(serverId: String, sessionId: String) {
        entries.remove(serverId to sessionId)
    }
}
