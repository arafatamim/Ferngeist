package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Saving a cached file to the reader's chosen destination: every byte, or a visible failure. */
class ArtifactExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bytes = ByteArray(100_000) { (it * 31 % 256).toByte() }

    @Test
    fun `the destination receives every byte`() {
        val out = ByteArrayOutputStream()

        exportArtifact(cached(bytes)) { out }

        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun `a destination that will not open fails`() {
        assertTrue(runCatching { exportArtifact(cached(bytes)) { null } }.exceptionOrNull() is IOException)
    }

    @Test
    fun `a write failure such as a full disk surfaces`() {
        val full =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("No space left on device")
            }

        assertTrue(runCatching { exportArtifact(cached(bytes)) { full } }.exceptionOrNull() is IOException)
    }

    @Test
    fun `a cached copy shorter than verified is not a success`() {
        val artifact = cached(bytes).copy(size = bytes.size + 1L)

        assertTrue(
            runCatching { exportArtifact(artifact) { ByteArrayOutputStream() } }.exceptionOrNull() is IOException,
        )
    }

    private fun cached(content: ByteArray): CachedArtifact {
        val file = temp.newFile().apply { writeBytes(content) }
        return CachedArtifact(file, "application/octet-stream", content.size.toLong())
    }
}
