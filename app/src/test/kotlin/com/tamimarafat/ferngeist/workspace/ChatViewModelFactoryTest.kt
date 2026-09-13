package com.tamimarafat.ferngeist.workspace

import android.net.Uri
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatSessionFacadeFactory
import com.tamimarafat.ferngeist.core.model.NEW_SESSION_ARG
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.ChatScrollStateStore
import com.tamimarafat.ferngeist.feature.chat.PendingPromptStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] reads every id out of its `SavedStateHandle`, so the factory's whole job
 * is to seed one — there is no `hiltViewModel()` inside a pane to do it. The main
 * dispatcher is a [StandardTestDispatcher] so the view model's `init` coroutines queue
 * instead of running against the relaxed mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelFactoryTest {
    private val chatConnectionHub = mockk<ChatConnectionHub>(relaxed = true)
    private val sessionFacadeFactory = mockk<ChatSessionFacadeFactory>(relaxed = true)

    private val factory =
        ChatViewModelFactory(
            sessionFacadeFactory = sessionFacadeFactory,
            sessionRepository = mockk<SessionRepository>(relaxed = true),
            chatScrollStateStore = mockk<ChatScrollStateStore>(relaxed = true),
            pendingPromptStore = mockk<PendingPromptStore>(relaxed = true),
            recentSelectionStore = mockk<RecentSelectionStore>(relaxed = true),
            chatConnectionHub = chatConnectionHub,
            gatewayRepository = mockk<GatewayRepository>(relaxed = true),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `create seeds the pane selection into the view model`() {
        val viewModel =
            factory.create(
                selection = WorkspaceSelection("srv-1", "sess-1", "/work/proj", "create-on-arrival"),
                mintedSessionId = "real-42",
            )

        assertEquals("/work/proj", viewModel.cwd)
        // Announcing screen presence is the view model's first act, so it proves both ids
        // arrived from the handle the factory built rather than the enclosing nav entry's.
        verify { chatConnectionHub.chatScreenOpened("srv-1", "sess-1", "/work/proj") }
    }

    @Test
    fun `create rejects blank ids instead of building a broken chat`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(WorkspaceSelection("", "sess-1", "/work", "t"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(WorkspaceSelection("srv-1", " ", "/work", "t"))
        }
    }

    /**
     * The minted id is a create-on-arrival chat's real identity: the nav arg stays the
     * [NEW_SESSION_ARG] sentinel for the pane's lifetime, and the pane's `WorkspaceState`
     * carries what the transport minted. `trackedSessionId` is private, so the identity is
     * observable through the id the view model hands the facade — the one its hub presence and
     * gateway binding are keyed by. Without the forward the facade is built for the sentinel.
     */
    @Test
    fun `create hands the facade the minted id, not the new-session sentinel`() {
        factory.create(
            selection = WorkspaceSelection("srv-1", NEW_SESSION_ARG, "/work/proj", "create-on-arrival"),
            mintedSessionId = "real-42",
        )

        verify {
            sessionFacadeFactory.create(
                scope = any(),
                serverId = "srv-1",
                sessionId = "real-42",
                cwd = "/work/proj",
            )
        }
    }

    /**
     * The pane hands the factory a `Uri.encode`d title (`WorkspaceScreen` encodes it, exactly as
     * the nav route would carry it) and [com.tamimarafat.ferngeist.feature.chat.ChatViewModel]
     * decodes it out of the handle. JVM unit tests stub `android.net.Uri`
     * (`unitTests.isReturnDefaultValues`), so decode is stubbed here with the only answer it may
     * give: [TITLE] for the exact string `Uri.encode(TITLE)` produces — it leaves alphanumerics
     * and `_-!.~'()*` intact and escapes everything else, so the literal below is what the pane
     * really passes. A different string reaching decode fails the stub, and a title that never
     * reaches decode leaves `ChatState.title` null.
     */
    @Test
    fun `create forwards the pane's encoded title for the view model to decode`() {
        mockkStatic(Uri::class)
        every { Uri.decode(ENCODED_TITLE) } returns TITLE

        val viewModel =
            factory.create(
                selection = WorkspaceSelection("srv-1", "sess-1", "/work/proj", ENCODED_TITLE),
            )

        // ChatState.title is what the app bar renders; the view model decodes in its initializer
        // and short-circuits the repository lookup for a non-blank title, so no dispatcher
        // advance is needed to see it.
        assertEquals(TITLE, viewModel.state.value.title)
    }

    private companion object {
        /** Carries a literal `%`, so a second decode cannot produce it again. */
        const val TITLE = "100% done: a/b"

        /** `Uri.encode(TITLE)`: the space, `%`, `:` and `/` are the escapes. */
        const val ENCODED_TITLE = "100%25%20done%3A%20a%2Fb"
    }
}
