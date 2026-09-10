package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Shared infrastructure for [ChatViewModel] tests: a JUnit rule that swaps the main dispatcher
 * for a test dispatcher, a factory method for creating the view model with in-memory fakes,
 * and helper functions for building snapshots and echo messages.
 *
 * Individual test classes extend this base to avoid re-declaring the same fakes and helpers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class ChatViewModelTestBase {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Creates a view model with in-memory test doubles. */
    protected fun createViewModel(
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
        chatConnectionHub: ChatConnectionHub = newChatConnectionHub(),
        pendingPromptStore: PendingPromptStore = InMemoryPendingPromptStore(),
    ): ChatViewModel =
        ChatViewModel(
            sessionFacadeFactory = facadeFactory,
            sessionRepository = sessionRepository,
            chatScrollStateStore = chatScrollStateStore,
            pendingPromptStore = pendingPromptStore,
            recentSelectionStore = FakeRecentSelectionStore(),
            chatConnectionHub = chatConnectionHub,
            gatewayRepository = gatewayRepository,
            savedStateHandle = savedStateHandle,
        )
}

/** Builds a hub over the test dispatcher scope, like the pre-refactor default. */
private fun newChatConnectionHub(): ChatConnectionHub =
    ChatConnectionHub(
        gatewayRepository = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

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
 * Builds a [ChatSessionSnapshot] with [ChatLoadState.READY] and no error, suitable as a
 * baseline that individual tests override via [ChatSessionFacade.applySnapshot].
 */
internal fun readySnapshot(
    title: String? = null,
    messages: List<ChatMessage> = emptyList(),
    isStreaming: Boolean = false,
    configOptions: List<ChatConfigOption> = emptyList(),
    availableCommands: List<ChatCommand> = emptyList(),
    commandsAdvertised: Boolean = false,
    usage: UsageState? = null,
    error: String? = null,
): ChatSessionSnapshot =
    ChatSessionSnapshot(
        loadState = ChatLoadState.READY,
        messages = messages,
        isStreaming = isStreaming,
        configOptions = configOptions,
        availableCommands = availableCommands,
        commandsAdvertised = commandsAdvertised,
        error = error,
        usage = usage,
        title = title,
    )

/** Convenience overload that accepts a single [ChatMessage] for the [ChatSessionSnapshot.messages] field. */
internal fun readySnapshot(message: ChatMessage): ChatSessionSnapshot = readySnapshot(messages = listOf(message))

/**
 * Builds an echo [ChatMessage] with [MessageDeliveryStatus.SENT], simulating the server-side
 * echo that confirms a pending message was received.
 */
internal fun echoMessage(
    id: String = "server-echo-1",
    content: String = "hello world",
): ChatMessage =
    ChatMessage(
        id = id,
        role = ChatMessage.Role.USER,
        content = content,
        status = MessageDeliveryStatus.SENT,
    )

/** Builds a [ChatSessionSnapshot] containing a single echo message. */
internal fun snapshotWithEcho(echo: ChatMessage): ChatSessionSnapshot = readySnapshot(message = echo)

/**
 * Screen-presence tests for [ChatViewModel] against a real [ChatConnectionHub].
 *
 * The view model is the hub's screen-presence writer: opening a real session
 * publishes [ChatPresence] (screen open); a create-on-arrival chat stays absent
 * until the facade mints a real [ChatSessionFacade.liveChatId]; clearing drops
 * on-screen presence while a transport-attached entry survives pooled as the
 * notification tap target.
 *
 * ponytail: co-located in the shared base file (instead of its own *Test.kt)
 * so the Task-4 commit can stay limited to the three brief-listed files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPresenceTest : ChatViewModelTestBase() {
    @Test
    fun `opening a real session chat publishes hub presence`() =
        runTest {
            val hub = newPresenceHub()
            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
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

            assertTrue(hub.isTracked("server_1", "session_1"))
            assertEquals(
                ChatPresence(serverId = "server_1", sessionId = "session_1", cwd = "/"),
                hub.onScreenChat.value,
            )

            viewModel.clearForTest()
        }

    @Test
    fun `create on arrival chat publishes presence only after a real liveChatId arrives`() =
        runTest {
            val hub = newPresenceHub()
            val facadeFactory = FakeChatSessionFacadeFactory()
            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
                    facadeFactory = facadeFactory,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "serverId" to "server_1",
                                "sessionId" to NEW_SESSION_ARG,
                                "cwd" to "/",
                            ),
                        ),
                )
            advanceUntilIdle()

            // The __new__ sentinel is a nav placeholder, not a chat identity:
            // nothing may be tracked or on screen until the facade mints a real id.
            assertFalse(hub.isTracked("server_1", NEW_SESSION_ARG))
            assertNull(hub.onScreenChat.value)

            facadeFactory.lastFacade.value!!.emitLiveChatId("server_1/session_minted")
            advanceUntilIdle()

            assertTrue(hub.isTracked("server_1", "session_minted"))
            assertEquals(
                ChatPresence(serverId = "server_1", sessionId = "session_minted", cwd = "/"),
                hub.onScreenChat.value,
            )

            viewModel.clearForTest()
        }

    @Test
    fun `clearing the screen drops on screen presence but keeps a transport attached entry pooled`() =
        runTest {
            val hub = newPresenceHub()
            // The real flow attaches the transport first (facade register); the
            // view model's init then flips the same entry to screen-open.
            hub.register(
                serverId = "server_1",
                sessionId = "session_1",
                gatewaySessionId = "gateway-session-1",
                gatewaySourceId = "gateway-1",
                agentId = "agent-1",
                isConnected = { false },
                isStreaming = { false },
                manager = hub.acquireChatManager(),
            )
            val viewModel =
                createViewModel(
                    chatConnectionHub = hub,
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

            assertTrue(hub.isTracked("server_1", "session_1"))
            assertEquals("session_1", hub.onScreenChat.value?.sessionId)

            viewModel.clearForTest()

            // Leaving the screen drops screen presence only; the pooled transport
            // entry survives as the notification tap target until evict/close.
            assertNull(hub.onScreenChat.value)
            assertTrue(hub.isTracked("server_1", "session_1"))
            assertEquals("session_1", hub.tapTarget.value?.sessionId)
        }
}

/** Builds a hub with a connectivity observer so presence tests can attach real transports. */
private fun newPresenceHub(): ChatConnectionHub =
    ChatConnectionHub(
        gatewayRepository = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        connectivityObserver = FakeConnectivityObserver(),
    )

/**
 * Invokes the protected [androidx.lifecycle.ViewModel.onCleared] the way a
 * [androidx.lifecycle.ViewModelStore] clear would, so close semantics are
 * testable. [ChatViewModel] is final and onCleared is protected, so reflection
 * is the deterministic seam (mirrors the hub test suite's manager-state helper).
 */
internal fun ChatViewModel.clearForTest() {
    val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared")
    onCleared.isAccessible = true
    onCleared.invoke(this)
}
