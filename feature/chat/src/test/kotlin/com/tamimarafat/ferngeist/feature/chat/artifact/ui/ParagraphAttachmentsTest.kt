package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.ui.MessageBubble
import com.tamimarafat.ferngeist.feature.chat.ui.RenderedMarkdown
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkspaceFiles
import com.tamimarafat.ferngeist.feature.chat.ui.localWorkspaceRoot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/** Attachment cards as the transcript renders them: placement, shape, order, size, Download and retry. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParagraphAttachmentsTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val downloads = mutableListOf<Pair<String, String>>()
    private var failOpens = 0
    private val host =
        ArtifactHost(
            open = { path ->
                opened += path
                if (failOpens-- > 0) throw IOException("gateway unreachable")
            },
            download = { path, name -> downloads += path to name },
            stat = { paths ->
                stats += paths
                paths.map { if ("gone" in it) ChatArtifactStat(null, "not_found") else ChatArtifactStat(2048, null) }
            },
        )
    private val stats = mutableListOf<List<String>>()

    @Test
    fun `one stat call labels each card with its size and marks an unreadable file before any tap`() {
        show("See /tmp/a.pdf and /tmp/gone.pdf")
        compose.waitForIdle()

        compose.onNodeWithText("PDF · 2", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Unavailable. Tap to retry.", useUnmergedTree = true).assertExists()
        assertEquals(listOf(listOf("/tmp/a.pdf", "/tmp/gone.pdf")), stats)
        assertEquals("nothing is read until a tap", emptyList<String>(), opened)
    }

    @Test
    fun `cards follow their own paragraph, square and in reference order, without source files`() {
        show("First /tmp/b.pdf, src/A.kt and /tmp/a.pdf.\n\nSecond [clip](/tmp/c.mp4)", width = 400)

        val first = compose.onNodeWithText("First", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second =
            compose
                .onNodeWithText(
                    "Second",
                    substring = true,
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        val b = card("b.pdf")
        val a = card("a.pdf")
        val c = card("c.mp4")

        assertTrue("b.pdf sits below its paragraph", b.top >= first.bottom && b.bottom <= second.top)
        assertTrue("a.pdf follows b.pdf on the same row", a.left > b.left && a.top == b.top)
        assertTrue("c.mp4 sits below the second paragraph", c.top >= second.bottom)
        listOf(a, b, c).forEach { assertEquals("square", it.width.value, it.height.value, 0.5f) }
        compose.onNodeWithContentDescription("A.kt").assertDoesNotExist()
    }

    @Test
    fun `a card shrinks to stay square in a narrow column`() {
        show("See /tmp/a.pdf", width = 96)

        val a = card("a.pdf")

        assertTrue("fits the column: ${a.width}", a.width.value <= 96f)
        assertEquals(a.width.value, a.height.value, 0.5f)
    }

    @Test
    fun `long press and the accessibility action both download the card's file`() {
        show("See [clip](/tmp/rec/clip.mp4)")

        compose.onNodeWithContentDescription("clip.mp4").performTouchInput { longClick() }
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithContentDescription("clip.mp4").performCustomAccessibilityActionWithLabel("Download")

        assertEquals(List(2) { "/tmp/rec/clip.mp4" to "clip.mp4" }, downloads)
    }

    @Test
    fun `a failed open shows unavailable and a retry reads the same file`() {
        failOpens = 1
        show("See /tmp/a.pdf")

        compose.onNodeWithContentDescription("a.pdf").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unavailable. Tap to retry.", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("a.pdf").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Unavailable. Tap to retry.", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf("/tmp/a.pdf", "/tmp/a.pdf"), opened)
    }

    private fun card(name: String) = compose.onNodeWithContentDescription(name).getUnclippedBoundsInRoot()

    @Test
    fun `a resource link to one ambiguous candidate leaves the other file actionable`() {
        val message =
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                segments =
                    persistentListOf(
                        AssistantSegment(id = "prose", text = "See report.pdf"),
                        AssistantSegment(
                            kind = AssistantSegment.Kind.RESOURCE_LINK,
                            resourceLink = ChatResourceLink("/home/u/app/a/report.pdf", "Linked report"),
                        ),
                    ),
            )
        showAssistant(workspace = mapOf("report.pdf" to listOf("a/report.pdf", "b/report.pdf"))) { message }

        compose.onNodeWithContentDescription("b/report.pdf").performClick()
        compose.waitForIdle()

        assertEquals(listOf("b/report.pdf"), opened)
        compose.onNodeWithContentDescription("a/report.pdf").assertExists()
        compose.onNodeWithContentDescription("Linked report").assertExists()
    }

    @Test
    fun `a later resource link keeps each earlier paragraph card without duplicating mentions`() {
        val message =
            mutableStateOf(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    isStreaming = true,
                    segments =
                        persistentListOf(
                            AssistantSegment(
                                id = "prose",
                                text = "First /tmp/report.pdf and /tmp/report.pdf.\n\nSecond /tmp/report.pdf.\n\nDone.",
                            ),
                        ),
                ),
            )
        showAssistant { message.value }
        compose.onAllNodesWithContentDescription("report.pdf").assertCountEquals(2)

        compose.runOnIdle {
            message.value =
                message.value.copy(
                    segments =
                        message.value.segments.adding(
                            AssistantSegment(
                                kind = AssistantSegment.Kind.RESOURCE_LINK,
                                resourceLink = ChatResourceLink("/tmp/report.pdf", "Linked report"),
                            ),
                        ),
                )
        }

        compose.onNodeWithContentDescription("Linked report").assertExists()
        compose.onAllNodesWithContentDescription("report.pdf").assertCountEquals(2)
    }

    private fun showAssistant(
        workspace: Map<String, List<String>>? = null,
        message: () -> ChatMessage,
    ) {
        compose.setContent {
            val snapshot = message()
            val documents =
                snapshot.segments
                    .filter { it.kind == AssistantSegment.Kind.MESSAGE }
                    .associate { segment ->
                        val blocks =
                            MarkdownEngine(MarkdownDialect.GfmCompat)
                                .append(segment.text)
                                .snapshot.document.blocks
                        segment.id to MarkdownRenderedDocument(blocks)
                    }
            CompositionLocalProvider(
                localArtifactHost provides host,
                localWorkspaceRoot provides "/home/u/app",
                localWorkspaceFiles provides workspace,
            ) {
                MessageBubble(snapshot, documents.toPersistentMap(), false, null, {}, {}, Modifier.width(400.dp))
            }
        }
    }

    private fun show(
        markdown: String,
        width: Int = 320,
    ) {
        val blocks =
            MarkdownEngine(MarkdownDialect.GfmCompat)
                .append(markdown)
                .snapshot.document.blocks
        compose.setContent {
            CompositionLocalProvider(localArtifactHost provides host, localWorkspaceRoot provides "/home/u/app") {
                RenderedMarkdown(markdown, MarkdownRenderedDocument(blocks), Modifier.width(width.dp))
            }
        }
    }
}
