package com.tamimarafat.ferngeist.feature.chat.activity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.TurnKey
import com.tamimarafat.ferngeist.core.model.TurnTiming
import com.tamimarafat.ferngeist.feature.chat.promptKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneTurnClockTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    private fun clock() = PhoneTurnClock(dataStore, Json)

    private fun userMessage(
        text: String,
        id: String = text,
    ) = ChatMessage(id = id, role = ChatMessage.Role.USER, contentBlocks = listOf(ContentBlock.Text(text)))

    private fun firstPrompt(text: String): Set<TurnKey> = listOf(userMessage(text)).promptKeys().values.toSet()

    private fun test(body: suspend TestScope.() -> Unit) =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    File(folder.root, "turns.preferences_pb")
                }
            body()
        }

    @Test
    fun sameSessionIdOnDifferentAgents_keepsSeparateActivity() =
        test {
            val clock = clock()
            clock.record("a", "shared", 100, firstPrompt("a"))
            clock.record("b", "shared", 200, firstPrompt("b"))

            assertEquals(100L, clock.lastTurn("a", "shared"))
            assertEquals(200L, clock.lastTurn("b", "shared"))
            assertNull(clock.lastTurn("c", "shared"))
        }

    @Test
    fun completedPhoneTurnAfterLeaving_doesNotLookLikeExternalActivity() =
        test {
            val clock = clock()
            clock.record("server", "chat", 100, firstPrompt("prompt"))
            clock.observe(
                "server",
                "chat",
                listOf(
                    userMessage("prompt"),
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, turn = TurnTiming(100, 200)),
                ),
                now = 200,
            )

            val lastTurn = clock.lastTurn("server", "chat")
            assertEquals(false, ActivityConfirmation(190).needsConfirmation(lastTurn, 210))
            assertEquals(true, ActivityConfirmation(30_201).needsConfirmation(lastTurn, 30_210))
        }

    @Test
    fun ownTurnEndingAfterAReplay_stillCountsAsThePhones() =
        test {
            val clock = clock()
            clock.record("server", "chat", 100, firstPrompt("fix it"))
            // A reattached session replays the prompt under a new id, then the turn ends live.
            clock.observe(
                "server",
                "chat",
                listOf(
                    userMessage("fix it", id = "replayed"),
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, turn = TurnTiming(100, 90_000)),
                ),
                now = 90_000,
            )

            assertEquals(90_000L, clock.lastTurn("server", "chat"))
        }

    @Test
    fun lateOlderEcho_cannotMoveThePhoneClockBackwards() =
        test {
            val clock = clock()
            clock.record("server", "out-of-order", 200, firstPrompt("new"))
            clock.record("server", "out-of-order", 100, firstPrompt("old"))

            assertEquals(200L, clock.lastTurn("server", "out-of-order"))
        }

    @Test
    fun restartedProcess_stillKnowsItsOwnRecentTurn() =
        test {
            clock().record("server", "chat", 1_000, firstPrompt("prompt"))

            val restarted = clock()
            restarted.restore()

            assertEquals(1_000L, restarted.lastTurn("server", "chat"))
        }

    @Test
    fun turnPastTheDecisionWindow_isPrunedFromDisk() =
        test {
            val clock = clock()
            clock.record("server", "stale", 1_000, firstPrompt("old"))
            clock.record("server", "fresh", 1_000 + ActivityConfirmation.RELEVANCE_MS + 1, firstPrompt("new"))

            val restarted = clock()
            restarted.restore()

            assertNull(restarted.lastTurn("server", "stale"))
            assertNull(clock.lastTurn("server", "stale"))
            assertEquals(
                1,
                dataStore.data
                    .first()
                    .asMap()
                    .size,
            )
        }
}
