package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactMetadata
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactReader
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Cached host files: isolated per reader, current with the host, complete or absent, kept while in use. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val path = "/tmp/clip.mp4"

    @Test
    fun `two readers of the same path keep their own bytes`() =
        runTest {
            val cache = cache()
            val a = FakeReader("gw-a") { byteArrayOf(1, 2, 3) }
            val b = FakeReader("gw-b") { byteArrayOf(9, 9) }

            cache.use(a, path) {}
            cache.use(b, path) {}

            assertArrayEquals(byteArrayOf(1, 2, 3), cache.use(a, path) { it.file.readBytes() })
            assertArrayEquals(byteArrayOf(9, 9), cache.use(b, path) { it.file.readBytes() })
            assertEquals("each copy crossed the wire once", listOf(1, 1), listOf(a.transfers, b.transfers))
        }

    @Test
    fun `a file the agent rewrote since it was cached is read again`() =
        runTest {
            val cache = cache()
            var content = byteArrayOf(1)
            val reader = FakeReader("gw") { content }

            cache.use(reader, path) {}
            content = byteArrayOf(2, 2)

            assertArrayEquals(byteArrayOf(2, 2), cache.use(reader, path) { it.file.readBytes() })
            assertEquals(2, reader.transfers)
        }

    @Test
    fun `revalidating a changed file leaves a held version unchanged`() =
        runTest {
            val cache = cache()
            var content = byteArrayOf(1, 2, 3)
            val reader = FakeReader("gw") { content }

            cache.use(reader, path) { original ->
                content = byteArrayOf(9, 8, 7, 6)
                cache.use(reader, path) { updated ->
                    assertArrayEquals(byteArrayOf(9, 8, 7, 6), updated.file.readBytes())
                    assertArrayEquals(byteArrayOf(1, 2, 3), original.file.readBytes())
                    assertEquals(3L, original.size)
                }
                assertArrayEquals(byteArrayOf(1, 2, 3), original.file.readBytes())
            }
        }

    @Test
    fun `a host that cannot confirm the cached copy fails the use instead of serving it`() =
        runTest {
            val cache = cache()
            var reachable = true
            val reader = FakeReader("gw") { if (reachable) byteArrayOf(1) else throw IOException("gateway down") }

            cache.use(reader, path) {}
            reachable = false

            assertTrue(runCatching { cache.use(reader, path) {} }.exceptionOrNull() is IOException)
        }

    @Test
    fun `a failed read leaves nothing behind and the next use reads again`() =
        runTest {
            val cache = cache()
            var fail = true
            val reader =
                FakeReader("gw") {
                    if (fail) throw IOException("connection reset") else byteArrayOf(7)
                }

            val failure = runCatching { cache.use(reader, path) {} }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(emptyList<String>(), root().list()!!.toList())

            fail = false
            assertArrayEquals(byteArrayOf(7), cache.use(reader, path) { it.file.readBytes() })
        }

    @Test
    fun `cancelling a read removes its partial bytes`() =
        runTest {
            val cache = cache()
            val never = CompletableDeferred<Unit>()
            val reader = FakeReader("gw", gate = never) { byteArrayOf(1) }

            val job = launch { cache.use(reader, path) {} }
            advanceUntilIdle()
            assertEquals("the read is writing its partial file", 1, root().list()!!.size)
            job.cancel()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), root().list()!!.toList())
        }

    @Test
    fun `concurrent uses of one file share a single read`() =
        runTest {
            val cache = cache()
            val gate = CompletableDeferred<Unit>()
            val reader = FakeReader("gw", gate = gate) { byteArrayOf(5, 5) }

            val files = List(3) { async { cache.use(reader, path) { it.file } } }
            advanceUntilIdle()
            gate.complete(Unit)

            assertEquals(1, files.awaitAll().toSet().size)
            assertEquals(1, reader.transfers)
        }

    @Test
    fun `past the budget the least recently used idle file goes, never one in use`() =
        runTest {
            val cache = cache(budget = 10)
            val reader = FakeReader("gw") { ByteArray(8) }
            val playing = CompletableDeferred<Unit>()
            lateinit var held: File

            val player =
                launch {
                    cache.use(reader, "/a.mp4") {
                        held = it.file
                        playing.await()
                    }
                }
            advanceUntilIdle()
            val idle = cache.use(reader, "/b.mp4") { it.file }

            assertTrue("a file in use survives the budget", held.exists())
            assertFalse("the idle file over budget is evicted", idle.exists())

            playing.complete(Unit)
            player.join()
            val latest = cache.use(reader, "/c.mp4") { it.file }

            assertFalse("released, the older file goes first", held.exists())
            assertTrue(latest.exists())
        }

    @Test
    fun `a cache that cannot write fails visibly`() =
        runTest {
            val blocker = temp.newFile("blocker")
            val cache = ArtifactCache(File(blocker, "artifacts"), Long.MAX_VALUE, StandardTestDispatcher(testScheduler))

            val failure = runCatching { cache.use(FakeReader("gw") { byteArrayOf(1) }, path) {} }.exceptionOrNull()

            assertTrue(failure is IOException)
        }

    private fun root() = File(temp.root, "artifacts")

    private fun TestScope.cache(budget: Long = Long.MAX_VALUE) =
        ArtifactCache(root(), budget, StandardTestDispatcher(testScheduler))

    /** A host whose etag is its content's hash, so a changed file answers with new bytes and an unchanged one with 304. */
    private class FakeReader(
        override val identity: String,
        private val gate: CompletableDeferred<Unit>? = null,
        private val bytes: () -> ByteArray,
    ) : ChatArtifactReader {
        var transfers = 0

        override suspend fun write(
            path: String,
            destination: OutputStream,
            ifNoneMatch: String?,
        ): ChatArtifactMetadata? {
            gate?.await()
            val body = bytes()
            val etag = body.contentHashCode().toString()
            if (etag == ifNoneMatch) return null
            transfers++
            destination.write(body)
            return ChatArtifactMetadata("video/mp4", body.size.toLong(), etag)
        }

        override suspend fun stat(paths: List<String>): List<ChatArtifactStat> = error("Unused")
    }
}
