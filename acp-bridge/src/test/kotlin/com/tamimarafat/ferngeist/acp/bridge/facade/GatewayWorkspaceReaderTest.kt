package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewayRepo
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.GatewayFileContents
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A workspace file read keeps what the transcript's images need: the blob, and the session it is scoped to. */
class GatewayWorkspaceReaderTest {
    private val connection = GatewayWorkspaceConnection("runtime", "https", "gw.example", "credential")

    @Test
    fun `a file read carries the gateway's blob and scopes the read to the live session`() =
        runTest {
            val reads = mutableListOf<Pair<String, String?>>()
            val gateway =
                object : GatewayRepository by FakeGatewayRepo() {
                    override suspend fun fetchFile(
                        connection: GatewayWorkspaceConnection,
                        path: String,
                        acpSessionId: String?,
                    ): GatewayFileContents {
                        reads += path to acpSessionId
                        return GatewayFileContents(blob = "AQID", truncated = false)
                    }
                }
            val reader = GatewayWorkspaceReader(gateway, { connection }, { "session-1" }, cwd = "/project")

            assertEquals(ChatFileContents(text = null, truncated = false, blob = "AQID"), reader.fetchFile("a.png"))
            assertEquals(listOf("a.png" to "session-1"), reads)
        }

    @Test
    fun `a chat without a gateway workspace reads nothing`() =
        runTest {
            val reader = GatewayWorkspaceReader(FakeGatewayRepo(), { null }, { "session-1" }, cwd = "/project")

            assertNull(reader.fetchFile("a.png"))
        }
}
