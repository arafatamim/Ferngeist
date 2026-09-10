package com.tamimarafat.ferngeist.data.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tamimarafat.ferngeist.data.database.FerngeistDatabase
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
    }
}
