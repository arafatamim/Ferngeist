package com.tamimarafat.ferngeist.data.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the one-gateway-session-per-chat invariant.
 *
 * A gateway runtime leases exactly one session, so two rows sharing a
 * `gatewaySessionId` both resume it on a cold start and fight over the lease
 * (the chat screen then never populates). These tests pin the write that keeps
 * that from happening and heals rows written before it existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRepositoryImplGatewaySessionTest {
    private lateinit var database: FerngeistDatabase
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    FerngeistDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        repository = SessionRepositoryImpl(database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun setGatewaySessionId_releasesStaleClaimFromAnotherRow() =
        runTest {
            seedSession(sessionId = "chat-a", gatewaySessionId = "gw-1")
            seedSession(sessionId = "chat-b")

            repository.setGatewaySessionId(serverId = SERVER_ID, sessionId = "chat-b", gatewaySessionId = "gw-1")

            assertEquals("gw-1", repository.getSession(SERVER_ID, "chat-b")?.gatewaySessionId)
            assertNull(
                "the previous claimant must release the session, or both chats resume it",
                repository.getSession(SERVER_ID, "chat-a")?.gatewaySessionId,
            )
        }

    @Test
    fun setGatewaySessionId_leavesUnrelatedClaimsAlone() =
        runTest {
            seedSession(sessionId = "chat-a", gatewaySessionId = "gw-1")
            seedSession(sessionId = "chat-b")

            repository.setGatewaySessionId(serverId = SERVER_ID, sessionId = "chat-b", gatewaySessionId = "gw-2")

            assertEquals("gw-1", repository.getSession(SERVER_ID, "chat-a")?.gatewaySessionId)
            assertEquals("gw-2", repository.getSession(SERVER_ID, "chat-b")?.gatewaySessionId)
        }

    @Test
    fun setGatewaySessionId_null_clearsOnlyThatRow() =
        runTest {
            seedSession(sessionId = "chat-a", gatewaySessionId = "gw-1")
            seedSession(sessionId = "chat-b", gatewaySessionId = "gw-2")

            repository.setGatewaySessionId(serverId = SERVER_ID, sessionId = "chat-a", gatewaySessionId = null)

            assertNull(repository.getSession(SERVER_ID, "chat-a")?.gatewaySessionId)
            assertEquals("gw-2", repository.getSession(SERVER_ID, "chat-b")?.gatewaySessionId)
        }

    @Test
    fun concurrentClaimsForOneGatewaySession_leaveExactlyOneOwner() =
        runTest {
            seedSession(sessionId = "chat-a")
            seedSession(sessionId = "chat-b")

            repeat(CLAIM_ROUNDS) {
                // Two chats on one agent attach to the same gateway session at
                // once. The claim is update-then-clear, so an interleaved pair
                // would have each call clear the other's row and leave the
                // gateway session owned by nobody.
                val claims =
                    listOf("chat-a", "chat-b").map { chat ->
                        async { repository.setGatewaySessionId(SERVER_ID, chat, "gw-1") }
                    }
                claims.awaitAll()

                val owners =
                    listOf("chat-a", "chat-b")
                        .filter { repository.getSession(SERVER_ID, it)?.gatewaySessionId == "gw-1" }
                assertEquals("round $it must leave exactly one owner, got $owners", 1, owners.size)
                repository.setGatewaySessionId(SERVER_ID, "chat-a", null)
                repository.setGatewaySessionId(SERVER_ID, "chat-b", null)
            }
        }

    private suspend fun seedSession(
        sessionId: String,
        gatewaySessionId: String? = null,
    ) {
        database
            .sessionDao()
            .upsertSession(
                sessionId = sessionId,
                serverId = SERVER_ID,
                title = null,
                cwd = null,
                updatedAt = null,
                gatewaySessionId = gatewaySessionId,
            )
    }

    private companion object {
        const val SERVER_ID = "server-1"
        const val CLAIM_ROUNDS = 25
    }
}
