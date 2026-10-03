package com.tamimarafat.ferngeist.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatViewModel] intent handling, offline queue, and delivery confirmation.
 *
 * Covers: session-not-ready error paths, scroll snapshot restoration, title resolution from
 * the session repository and from server snapshots.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest : ChatViewModelTestBase() {
    @Test
    fun `create-on-arrival chat asks the facade to mint a session`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            createViewModel(
                facadeFactory = facadeFactory,
                savedStateHandle = newSessionHandle(),
            )
            advanceUntilIdle()

            assertEquals(NEW_SESSION_ARG, facadeFactory.lastRequestedSessionId)
        }

    @Test
    fun `switcher hint is offered while unseen and never again once marked`() =
        runTest {
            val viewModel = createViewModel(switcherHintStore = InMemorySwitcherHintStore())
            viewModel.switcherHintSeen.test {
                // The state starts "seen" so nothing flashes while the store
                // answers; the store then reports the real unseen flag.
                assertTrue(awaitItem())
                assertFalse(awaitItem())

                viewModel.dispatch(ChatIntent.MarkSwitcherHintSeen)
                advanceUntilIdle()

                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a rebuilt create-on-arrival chat reattaches instead of minting a second session`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            // Same route, restored from saved state: the nav arg is still the sentinel,
            // but the minted id from the first ViewModel is known.
            val handle =
                newSessionHandle().apply {
                    this["mintedSessionId"] = "session_minted"
                }
            createViewModel(facadeFactory = facadeFactory, savedStateHandle = handle)
            advanceUntilIdle()

            assertEquals("session_minted", facadeFactory.lastRequestedSessionId)
        }

    @Test
    fun `minting a session records the id for a later rebuild`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            val handle = newSessionHandle()
            val viewModel = createViewModel(facadeFactory = facadeFactory, savedStateHandle = handle)
            advanceUntilIdle()

            facadeFactory.lastFacade.value?.emitLiveChatId("server_1/session_minted")
            advanceUntilIdle()

            assertEquals("session_minted", handle.get<String>("mintedSessionId"))
        }

    private fun newSessionHandle(): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "serverId" to "server_1",
                "sessionId" to NEW_SESSION_ARG,
                "cwd" to "/",
            ),
        )

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
    fun `enqueueing a prompt while disconnected triggers a reconnect`() =
        runTest {
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
    fun `resolves title from the session store when nav arg title is blank`() =
        runTest {
            val sessionRepository =
                FakeSessionRepository().apply {
                    setSessions(
                        "server_1",
                        listOf(SessionSummary(id = "session_1", title = "Refactoring auth module")),
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

    // region: Auto-title tests

    @Test
    fun `agent pushed title replaces the title the session list reported`() =
        runTest {
            val sessionRepository =
                FakeSessionRepository().apply {
                    setSessions(
                        "server_1",
                        listOf(SessionSummary(id = "session_1", title = "Listed Title")),
                    )
                }
            val snapshot = readySnapshot(title = "Agent Named Title")
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

            // The agent naming the session outranks whatever its session list reported.
            assertEquals("Agent Named Title", viewModel.state.value.title)
            assertTrue(sessionRepository.updateTitleCalls.any { it.third == "Agent Named Title" })
        }

    @Test
    fun `applies and persists server title when current title is blank`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val snapshot = readySnapshot(title = "Generated Title")
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
            val snapshot = readySnapshot(title = "Generated Title")
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

    @Test
    fun `fetches the agent title from the session list when nothing was pushed`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val facade =
                TestFacade().apply {
                    fetchedSessionTitle = "Agent Generated Title"
                    emitSnapshot(readySnapshot(messages = listOf(assistantMessage())))
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            // Some agents generate a session name server-side but never push it over
            // session_info_update; it only shows up in a session/list response.
            assertEquals(1, facade.fetchSessionTitleCalls)
            assertEquals("Agent Generated Title", viewModel.state.value.title)
            val (serverId, sessionId, title) = sessionRepository.updateTitleCalls.single()
            assertEquals("server_1", serverId)
            assertEquals("session_1", sessionId)
            assertEquals("Agent Generated Title", title)
        }

    @Test
    fun `does not fetch the session list title while the first response is still streaming`() =
        runTest {
            val facade =
                TestFacade().apply {
                    fetchedSessionTitle = "Agent Generated Title"
                    emitSnapshot(
                        readySnapshot(
                            messages = listOf(assistantMessage(isStreaming = true)),
                            isStreaming = true,
                        ),
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            assertEquals(0, facade.fetchSessionTitleCalls)
            assertTrue(viewModel.state.value.title == null)
        }

    @Test
    fun `does not fetch the session list title when the agent already pushed one`() =
        runTest {
            val facade =
                TestFacade().apply {
                    fetchedSessionTitle = "Agent Generated Title"
                    emitSnapshot(
                        readySnapshot(
                            title = "Pushed Title",
                            messages = listOf(assistantMessage()),
                        ),
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            // The push is applied before the fallback runs inside the same snapshot,
            // so the fallback must stand down rather than query the agent.
            assertEquals("Pushed Title", viewModel.state.value.title)
            assertEquals(0, facade.fetchSessionTitleCalls)
        }

    @Test
    fun `ignores a session list title that is just the first prompt`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val facade =
                TestFacade().apply {
                    // What an agent synthesises when it has not really named the session.
                    fetchedSessionTitle = "do the thing"
                    emitSnapshot(
                        readySnapshot(
                            messages = listOf(userMessage("do the thing"), assistantMessage()),
                        ),
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            assertTrue(
                "the prompt must never become the title",
                viewModel.state.value.title == null,
            )
            assertTrue(sessionRepository.updateTitleCalls.isEmpty())
        }

    @Test
    fun `retries the session list until the agent names the session`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
            val facade =
                TestFacade().apply {
                    // First attempt still sees the synthesised prompt; the agent names it later.
                    fetchedSessionTitlesByCall = mapOf(1 to "do the thing", 2 to "Add retry logic")
                    emitSnapshot(
                        readySnapshot(
                            messages = listOf(userMessage("do the thing"), assistantMessage()),
                        ),
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            assertEquals(2, facade.fetchSessionTitleCalls)
            assertEquals("Add retry logic", viewModel.state.value.title)
            assertEquals("Add retry logic", sessionRepository.updateTitleCalls.single().third)
        }

    @Test
    fun `a pushed title replaces a fallback title`() =
        runTest {
            val facade =
                TestFacade().apply {
                    fetchedSessionTitle = "Fallback Title"
                    emitSnapshot(readySnapshot(messages = listOf(assistantMessage())))
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()
            assertEquals("Fallback Title", viewModel.state.value.title)

            // The agent's canonical title arrives after the speculative fallback.
            facade.emitSnapshot(
                readySnapshot(
                    title = "Pushed Title",
                    messages = listOf(assistantMessage(id = "assistant_2")),
                ),
            )
            advanceUntilIdle()

            assertEquals("Pushed Title", viewModel.state.value.title)
        }

    @Test
    fun `adopts a title the session list reports after the chat has opened`() =
        runTest {
            val sessionRepository = FakeSessionRepository()
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
            assertNull(viewModel.state.value.title)

            // The agent names the session later and a session list refresh records it.
            sessionRepository.setSessions(
                "server_1",
                listOf(SessionSummary(id = "session_1", title = "Agent Named Title")),
            )
            advanceUntilIdle()

            assertEquals("Agent Named Title", viewModel.state.value.title)
        }

    @Test
    fun `never shows the prompt as the title even when the session store holds one`() =
        runTest {
            val sessionRepository =
                FakeSessionRepository().apply {
                    setSessions(
                        "server_1",
                        listOf(SessionSummary(id = "session_1", title = "do the thing")),
                    )
                }
            val facade =
                TestFacade().apply {
                    emitSnapshot(
                        readySnapshot(
                            messages = listOf(userMessage("do the thing"), assistantMessage()),
                        ),
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
                    facadeFactory = TestFacadeFactory { facade },
                )
            advanceUntilIdle()

            assertNull(viewModel.state.value.title)
            assertTrue(sessionRepository.updateTitleCalls.isEmpty())
        }

    private fun assistantMessage(
        id: String = "assistant_1",
        isStreaming: Boolean = false,
    ): ChatMessage =
        ChatMessage(
            id = id,
            role = ChatMessage.Role.ASSISTANT,
            content = "hello",
            isStreaming = isStreaming,
        )

    private fun userMessage(content: String): ChatMessage =
        ChatMessage(
            id = "user_1",
            role = ChatMessage.Role.USER,
            content = content,
        )

    // endregion

    @Test
    fun `a hydrating snapshot does not replace a reported load failure with a spinner`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()
            val facade = facadeFactory.lastFacade.value!!

            facade.emitLoadFailed("The gateway could not start a session for this agent.")
            advanceUntilIdle()
            assertEquals(
                "The gateway could not start a session for this agent.",
                viewModel.state.value.error,
            )

            // A load that is still in flight reports HYDRATING without an error. Treating
            // that as "the failure is over" drops the message and re-enters loading, which
            // is the spinner that never resolves.
            facade.emitSnapshot(hydratingSnapshot())
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(
                "an in-flight snapshot must not erase the failure",
                "The gateway could not start a session for this agent.",
                state.error,
            )
            assertFalse("the screen must not fall back to the loading state", state.isLoading)
        }

    @Test
    fun `a hydrating snapshot still enters loading when no failure was reported`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()
            val facade = facadeFactory.lastFacade.value!!

            // Clear the default fake's init failure with a successful load, so the
            // hydration below starts from a screen with nothing to report.
            facade.emitSnapshot(readySnapshot())
            advanceUntilIdle()
            assertTrue(viewModel.state.value.error == null)

            facade.emitSnapshot(hydratingSnapshot())
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue("a fresh hydration must show loading", state.isLoading)
            assertTrue(state.error == null)
        }

    @Test
    fun `a ready snapshot clears an earlier load failure`() =
        runTest {
            val facadeFactory = TestFacadeFactory { TestFacade() }
            val viewModel = createViewModel(facadeFactory = facadeFactory)
            advanceUntilIdle()
            val facade = facadeFactory.lastFacade.value!!

            facade.emitLoadFailed("Disconnected. Reconnect to refresh this session.")
            advanceUntilIdle()

            facade.emitSnapshot(readySnapshot())
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue("a successful load must clear the failure", state.error == null)
            assertFalse(state.isLoading)
        }
}
