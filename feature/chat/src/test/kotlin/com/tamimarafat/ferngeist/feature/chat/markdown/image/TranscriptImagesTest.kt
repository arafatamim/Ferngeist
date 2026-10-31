package com.tamimarafat.ferngeist.feature.chat.markdown.image

import com.tamimarafat.ferngeist.core.model.ChatFileContents
import com.tamimarafat.ferngeist.feature.chat.FakeChatSessionFacade
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Where a transcript image's bytes come from, and which sources fall back to alt text. */
class TranscriptImagesTest {
    private val cwd = "/home/dev/project"

    @Test
    fun `web and data URLs keep their own origin`() {
        assertEquals(ImageOrigin.Remote("https://example.com/a.png"), imageOrigin("https://example.com/a.png", cwd))
        assertEquals(ImageOrigin.Remote("HTTP://example.com/a.png"), imageOrigin("HTTP://example.com/a.png", cwd))
        assertEquals(ImageOrigin.Data("image/png;base64,AAAA"), imageOrigin("data:image/png;base64,AAAA", cwd))
    }

    @Test
    fun `workspace paths resolve against the project directory and only inside it`() {
        assertEquals(ImageOrigin.Workspace("docs/a.png"), imageOrigin("docs/a.png", cwd))
        assertEquals(ImageOrigin.Workspace("docs/a.png"), imageOrigin("./docs/a.png", cwd))
        assertEquals(ImageOrigin.Workspace("docs/a.png"), imageOrigin("/home/dev/project/docs/a.png", cwd))
        assertEquals(ImageOrigin.Workspace("docs/a.png"), imageOrigin("file:///home/dev/project/docs/a.png", cwd))
        assertEquals(ImageOrigin.Unsupported, imageOrigin("/etc/passwd", cwd))
        assertEquals(ImageOrigin.Unsupported, imageOrigin("file:///home/dev/other/a.png", cwd))
        assertEquals(ImageOrigin.Unsupported, imageOrigin("ftp://example.com/a.png", cwd))
    }

    @Test
    fun `data URLs decode base64 and percent-encoded payloads`() =
        runTest {
            val loader = loader()

            assertArrayEquals(byteArrayOf(1, 2, 3), loader.load("data:image/png;base64,AQID"))
            assertEquals("<svg a+b/>", loader.load("data:image/svg+xml,%3Csvg a+b/%3E").decodeToString())
        }

    @Test
    fun `invalid and oversized data URLs fail instead of decoding`() =
        runTest {
            val loader = loader()
            val oversized = "data:image/png;base64," + "A".repeat(MAX_IMAGE_BYTES / 3 * 4 + 8)

            assertFails { loader.load("data:image/png;base64,!!not base64!!") }
            assertFails { loader.load("data:image/png;base64") }
            assertFails { loader.load(oversized) }
        }

    @Test
    fun `workspace images decode the gateway's blob or take an SVG's text`() =
        runTest {
            val png = byteArrayOf(-119, 80, 78, 71)
            val loader =
                loader(
                    "a.png" to
                        ChatFileContents(
                            text = null,
                            truncated = false,
                            blob = Base64.getEncoder().encodeToString(png),
                        ),
                    "b.svg" to ChatFileContents(text = "<svg/>", truncated = false),
                )

            assertArrayEquals(png, loader.load("a.png"))
            assertEquals("<svg/>", loader.load("/home/dev/project/b.svg").decodeToString())
        }

    @Test
    fun `a truncated, missing or outside workspace image fails`() =
        runTest {
            val loader = loader("big.png" to ChatFileContents(text = null, truncated = true, blob = "AAAA"))

            assertFails { loader.load("big.png") }
            assertFails { loader.load("absent.png") }
            assertFails { loader.load("/etc/passwd") }
        }

    @Test
    fun `remote images go to the remote fetcher, not the workspace`() =
        runTest {
            val fetched = mutableListOf<String>()
            val loader =
                TranscriptImageLoader(workspace = FakeChatSessionFacade(), cwd = cwd) { url ->
                    fetched += url
                    byteArrayOf(7)
                }

            assertArrayEquals(byteArrayOf(7), loader.load("https://example.com/a.png"))
            assertEquals(listOf("https://example.com/a.png"), fetched)
        }

    @Test
    fun `two chats never share a cache key for the same path`() {
        val first = loader()
        val second = loader()

        assertTrue(first.cacheKey("a.png") != second.cacheKey("a.png"))
        assertEquals(first.cacheKey("a.png"), first.cacheKey("a.png"))
    }

    /** A workspace holding [files]; any other path reads as no workspace. */
    private fun loader(vararg files: Pair<String, ChatFileContents>): TranscriptImageLoader {
        val workspace =
            object : FakeChatSessionFacade() {
                override suspend fun fetchFile(path: String): ChatFileContents? = files.toMap()[path]
            }
        return TranscriptImageLoader(workspace = workspace, cwd = cwd) { error("no network in tests") }
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        val failed = runCatching { block() }.isFailure
        assertTrue("expected the load to fail", failed)
    }
}
