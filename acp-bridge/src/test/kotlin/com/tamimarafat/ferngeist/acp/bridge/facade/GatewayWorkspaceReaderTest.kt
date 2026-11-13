package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewayRepo
import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A workspace file read keeps what the transcript's images need: the blob, read at the host path under the chat's cwd. */
class GatewayWorkspaceReaderTest {
    private val connection = GatewayWorkspaceConnection("runtime", "https", "gw.example", "credential")

    @Test
    fun `a file read carries the gateway's blob and reads the relative path under the cwd`() =
        runTest {
            val reads = mutableListOf<String>()
            val blob = ChatFileContents(text = null, truncated = false, blob = "AQID")
            val gateway =
                object : GatewayRepository by FakeGatewayRepo() {
                    override suspend fun fetchFile(
                        scheme: String,
                        host: String,
                        gatewayCredential: String,
                        path: String,
                    ): ChatFileContents {
                        reads += path
                        return blob
                    }
                }
            val reader = GatewayWorkspaceReader(gateway, { connection }, { "session-1" }, cwd = "/project")

            assertEquals(blob, reader.fetchFile("docs/a.png"))
            assertEquals(listOf("/project/docs/a.png"), reads)
        }

    @Test
    fun `a chat without a gateway workspace reads nothing`() =
        runTest {
            val reader = GatewayWorkspaceReader(FakeGatewayRepo(), { null }, { "session-1" }, cwd = "/project")

            assertNull(reader.fetchFile("a.png"))
        }
}
