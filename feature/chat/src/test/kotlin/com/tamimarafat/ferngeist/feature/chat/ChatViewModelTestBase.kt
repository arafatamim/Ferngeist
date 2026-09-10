package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.ActiveChatStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
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
    ): ChatViewModel =
        ChatViewModel(
            sessionFacadeFactory = facadeFactory,
            sessionRepository = sessionRepository,
            chatScrollStateStore = chatScrollStateStore,
            recentSelectionStore = FakeRecentSelectionStore(),
            activeChatStore = ActiveChatStore(),
            chatConnectionHub = ChatConnectionHub(gatewayRepository = null),
            gatewayRepository = gatewayRepository,
            savedStateHandle = savedStateHandle,
        )
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
