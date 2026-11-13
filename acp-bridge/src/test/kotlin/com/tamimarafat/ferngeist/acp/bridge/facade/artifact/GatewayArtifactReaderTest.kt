package com.tamimarafat.ferngeist.acp.bridge.facade.artifact

import com.tamimarafat.ferngeist.acp.bridge.facade.GatewayWorkspaceReader
import com.tamimarafat.ferngeist.acp.bridge.hub.FakeGatewayRepo
import com.tamimarafat.ferngeist.core.model.GatewayWorkspaceConnection
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactMetadata
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** A captured artifact reader stays on the gateway and cwd it was taken from. */
class GatewayArtifactReaderTest {
    private val gatewayA = GatewayWorkspaceConnection("rt-a", "https", "a.example", "cred-a")
    private val gatewayB = GatewayWorkspaceConnection("rt-b", "https", "b.example", "cred-b")
    private val reads = mutableListOf<String>()
    private val gateway =
        object : GatewayRepository by FakeGatewayRepo() {
            override suspend fun downloadFile(
                connection: ProjectGitTarget,
                path: String,
                destination: OutputStream,
                ifNoneMatch: String?,
            ): ChatArtifactMetadata {
                reads += "${connection.host}$path"
                return ChatArtifactMetadata("text/html", 0, null)
            }
        }

    @Test
    fun `a reader keeps its gateway and cwd after the chat moves on`() =
        runTest {
            var connection = gatewayA
            val reader =
                requireNotNull(GatewayWorkspaceReader(gateway, { connection }, { null }, "/work/a").artifactReader())
            connection = gatewayB

            reader.write("out/clip.mp4", ByteArrayOutputStream(), null)
            reader.write("report/assets/app.js", ByteArrayOutputStream(), null)
            reader.write("/tmp/elsewhere.pdf", ByteArrayOutputStream(), null)

            assertEquals(
                listOf(
                    "a.example/work/a/out/clip.mp4",
                    "a.example/work/a/report/assets/app.js",
                    "a.example/tmp/elsewhere.pdf",
                ),
                reads,
            )
        }

    @Test
    fun `without a cwd a relative path is refused before any request, an absolute one still reads`() =
        runTest {
            val reader = requireNotNull(GatewayWorkspaceReader(gateway, { gatewayA }, { null }, "").artifactReader())

            val relative = runCatching { reader.write("out/clip.mp4", ByteArrayOutputStream(), null) }
            reader.write("/tmp/clip.mp4", ByteArrayOutputStream(), null)

            assertTrue(relative.isFailure)
            assertEquals(listOf("a.example/tmp/clip.mp4"), reads)
        }

    @Test
    fun `readers for different gateways or cwds never share an identity`() {
        val a = GatewayWorkspaceReader(gateway, { gatewayA }, { null }, "/work/a").artifactReader()!!
        val otherCwd = GatewayWorkspaceReader(gateway, { gatewayA }, { null }, "/work/b").artifactReader()!!
        val otherGateway = GatewayWorkspaceReader(gateway, { gatewayB }, { null }, "/work/a").artifactReader()!!

        assertNotEquals(a.identity, otherCwd.identity)
        assertNotEquals(a.identity, otherGateway.identity)
        assertTrue("the identity keys caches and must not carry the credential", "cred-a" !in a.identity)
    }
}
