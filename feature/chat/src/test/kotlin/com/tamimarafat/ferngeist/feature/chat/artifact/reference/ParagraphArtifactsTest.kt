package com.tamimarafat.ferngeist.feature.chat.artifact.reference

import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import com.adamglin.compose.markdown.core.model.BlockNode
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.ArtifactPresentation.CARD
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.ArtifactPresentation.INLINE_MEDIA
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.ArtifactPresentation.SOURCE_CHIP
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which files a paragraph references, and whether each becomes a card, inline media or a source chip. */
class ParagraphArtifactsTest {
    private val cwd = "/home/u/app"
    private val probe = "/tmp/ferngeist-elicitation-probe/live-full-history-test.mp4"

    @Test
    fun `the reported outside-workspace video link is one card`() {
        val found = artifacts("[Recorded test]($probe)")

        assertEquals(listOf(ParagraphArtifact(probe, listOf(probe), false, ReferenceSource.LINK)), found)
        assertEquals(CARD, found.single().presentation)
        assertEquals(ArtifactKind.VIDEO, found.single().kind)
    }

    @Test
    fun `the same video as a plain path is one card`() {
        assertEquals(listOf(probe to CARD), summary("Recorded test $probe"))
    }

    @Test
    fun `a workspace video plays inline whether linked or written`() {
        assertEquals(listOf("out/clip.mp4" to INLINE_MEDIA), summary("[clip]($cwd/out/clip.mp4)"))
        assertEquals(listOf("out/clip.mp4" to INLINE_MEDIA), summary("See $cwd/out/clip.mp4."))
    }

    @Test
    fun `workspace source keeps its chip and line suffix stays out of the path`() {
        assertEquals(listOf("src/A.kt" to SOURCE_CHIP), summary("[the route](src/A.kt:42)"))
    }

    @Test
    fun `html is a card inside the workspace and out`() {
        assertEquals(
            listOf("report/index.html" to CARD, "/tmp/r.html" to CARD),
            summary("Open [it](report/index.html) or /tmp/r.html"),
        )
    }

    @Test
    fun `workspace svg and audio render inline`() {
        val listed = mapOf("logo.svg" to listOf("art/logo.svg"), "beep.mp3" to listOf("sfx/beep.mp3"))

        assertEquals(
            listOf("art/logo.svg" to INLINE_MEDIA, "sfx/beep.mp3" to INLINE_MEDIA),
            summary("Uses logo.svg and beep.mp3", workspace = listed),
        )
        assertEquals(ArtifactKind.IMAGE, artifacts("logo.svg", workspace = listed).single().kind)
    }

    @Test
    fun `an extensionless file counts only when linked`() {
        assertEquals(listOf("/opt/tool/run" to CARD), summary("[the tool](/opt/tool/run)"))
        assertEquals(emptyList<Pair<String, ArtifactPresentation>>(), summary("Run /opt/tool/run now"))
        assertEquals(listOf("data/blob.qqq" to CARD), summary("[blob](data/blob.qqq)"))
    }

    @Test
    fun `a file linked and then written is one reference in first order`() {
        val found = artifacts("See /tmp/b.pdf, then [a](/tmp/a.pdf) and /tmp/a.pdf again and [b](/tmp/b.pdf)")

        assertEquals(listOf(listOf("/tmp/b.pdf"), listOf("/tmp/a.pdf")), found.map { it.candidates })
    }

    @Test
    fun `references inside emphasis and strong count, link labels do not`() {
        assertEquals(
            listOf("/tmp/a.pdf" to CARD, "/tmp/c.pdf" to CARD),
            summary("**see _/tmp/a.pdf_** and [/tmp/b.pdf](/tmp/c.pdf)"),
        )
    }

    @Test
    fun `encoded, quoted and backticked paths keep their spaces`() {
        val spaced = "/tmp/my video.mp4"

        assertEquals(listOf(spaced), artifacts("[v](/tmp/my%20video.mp4)").single().candidates)
        assertEquals(listOf(spaced), artifacts("Saved \"$spaced\" here").single().candidates)
        assertEquals(listOf(spaced), artifacts("Saved `$spaced` here").single().candidates)
    }

    @Test
    fun `web addresses and domain-like prose stay web or text`() {
        assertEquals(
            emptyList<Pair<String, ArtifactPresentation>>(),
            summary(
                "[pdf](https://x.com/a.pdf), https://x.com/b.mp4, `https://x.com/c.mp4`, example.com, Node.js, e.g. ASP.NET",
            ),
        )
    }

    @Test
    fun `an ambiguous bare name keeps every candidate`() {
        val listed = mapOf("a.png" to listOf("x/a.png", "y/a.png"))

        assertEquals(listOf("x/a.png", "y/a.png"), artifacts("Look at a.png", workspace = listed).single().candidates)
    }

    @Test
    fun `the same name in two folders is two references`() {
        assertEquals(
            listOf(listOf("/tmp/a/x.pdf"), listOf("/tmp/b/x.pdf")),
            artifacts("/tmp/a/x.pdf vs /tmp/b/x.pdf").map { it.candidates },
        )
    }

    @Test
    fun `a bare file name resolves through the turn's files, then the cwd`() {
        assertEquals(
            listOf("/srv/out/report.pdf" to CARD),
            summary("Wrote report.pdf", turn = setOf("/srv/out/report.pdf")),
        )
        assertEquals(listOf("report.pdf" to CARD), summary("Wrote report.pdf"))
    }

    @Test
    fun `without a cwd only absolute paths resolve, as outside host files`() {
        val found = artifacts("[a](out/a.mp4) and $probe", cwd = "")

        assertEquals(listOf(probe to CARD), found.map { it.candidates.single() to it.presentation })
    }

    @Test
    fun `parent relative references resolve outside the workspace as cards`() {
        assertEquals(listOf("/home/u/secrets.pdf" to CARD), summary("[up](../secrets.pdf)"))
        assertEquals(listOf("/home/u/secrets.pdf" to CARD), summary("See ../secrets.pdf"))
        assertEquals(listOf("C:\\work\\out.pdf" to CARD), summary("[up](../out.pdf)", cwd = "C:\\work\\app"))
    }

    @Test
    fun `a malformed parent relative link remains text instead of crashing the transcript`() {
        assertEquals(emptyList<ParagraphArtifact>(), artifacts("[bad](../bad%00.pdf)"))
    }

    private fun summary(
        markdown: String,
        cwd: String = this.cwd,
        turn: Set<String> = emptySet(),
        workspace: Map<String, List<String>>? = null,
    ): List<Pair<String, ArtifactPresentation>> =
        artifacts(markdown, cwd, turn, workspace).map { it.candidates.single() to it.presentation }

    private fun artifacts(
        markdown: String,
        cwd: String = this.cwd,
        turn: Set<String> = emptySet(),
        workspace: Map<String, List<String>>? = null,
    ): List<ParagraphArtifact> {
        val paragraph =
            MarkdownEngine(MarkdownDialect.GfmCompat)
                .append(markdown)
                .snapshot.document.blocks
                .single() as BlockNode.Paragraph
        return paragraphArtifacts(paragraph.children, cwd, turn, workspace)
    }
}
