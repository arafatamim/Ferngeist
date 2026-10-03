package com.tamimarafat.ferngeist.feature.chat

import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the streaming fade rests on: appending to a parsed document must not renumber the
 * blocks that did not change.
 *
 * The renderer keys each block on its [com.adamglin.compose.markdown.core.model.BlockId], so an id
 * that survives an append keeps the Compose node mounted — its reveal and fade state with it. If a
 * settled paragraph's id changed on the next chunk, the node would remount on every chunk.
 */
class MarkdownEngineIdentityTest {
    @Test
    fun `settled paragraph keeps its id when a later paragraph is appended`() {
        val engine = MarkdownEngine(MarkdownDialect.GfmCompat)
        engine.append("First paragraph.\n")

        val firstId =
            engine
                .snapshot()
                .document
                .blocks
                .single()
                .id

        val delta = engine.append("\n\nSecond paragraph.")

        assertTrue(
            "the first paragraph must still be present after the append",
            delta.snapshot.document.blocks
                .any { it.id == firstId },
        )
        assertEquals(
            "the appended paragraph should be the only new block",
            1,
            delta.insertedBlockIds.size,
        )
    }

    @Test
    fun `appending to a paragraph does not report it as inserted`() {
        val engine = MarkdownEngine(MarkdownDialect.GfmCompat)
        engine.append("Growing sentence")

        val id =
            engine
                .snapshot()
                .document
                .blocks
                .single()
                .id
        val delta = engine.append(" that keeps going")

        assertTrue(
            "a paragraph that only grew is updated, not inserted",
            id !in delta.insertedBlockIds,
        )
    }

    @Test
    fun `reset restores a document with no blocks`() {
        val engine = MarkdownEngine(MarkdownDialect.GfmCompat)
        engine.append("Something")

        engine.reset()

        assertTrue(
            engine
                .snapshot()
                .document
                .blocks
                .isEmpty(),
        )
    }
}
