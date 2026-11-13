package com.tamimarafat.ferngeist.feature.chat.markdown

import androidx.compose.ui.text.LinkAnnotation
import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import com.adamglin.compose.markdown.core.model.BlockNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which Markdown links reach the URI handler: web links do, host paths never do, chips or not. */
class MarkdownLinkRoutingTest {
    @Test
    fun `only web links become browser links`() {
        val paragraph =
            MarkdownEngine(MarkdownDialect.GfmCompat)
                .append(
                    "[site](https://example.com), [mail](mailto:a@b.c), [clip](/tmp/rec/clip.mp4), " +
                        "[win](C:\\rec\\clip.mp4), [rel](out/clip.mp4) and [uri](file:///tmp/a.pdf)",
                ).snapshot.document.blocks
                .single() as BlockNode.Paragraph

        val text = paragraph.children.toAnnotatedString()

        assertEquals(
            listOf("https://example.com", "mailto:a@b.c"),
            text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }
}
