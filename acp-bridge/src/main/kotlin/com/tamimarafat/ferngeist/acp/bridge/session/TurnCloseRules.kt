package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.text
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * True when turn close must clear streaming state or fold adjacent reply/reasoning chunks into
 * one segment per run (see [mergedTextRuns]).
 */
internal fun ChatMessage.needsTurnCloseRewrite(): Boolean =
    isStreaming ||
        (
            role == ChatMessage.Role.ASSISTANT &&
                segments.hasAdjacentTextRuns()
        )

/**
 * Settles a bubble for rendering: clears the streaming flag, folds adjacent MESSAGE (and THOUGHT)
 * segments into one markdown document per run. The derived plain-text view remains unchanged
 * because the fold concatenates only adjacent blocks of the same text kind.
 */
internal fun ChatMessage.closedByTurnClose(): ChatMessage {
    if (role != ChatMessage.Role.ASSISTANT) return copy(isStreaming = false)
    val merged = mergedTextRuns(segments)
    return copy(
        isStreaming = false,
        segments = merged,
    )
}

/**
 * Folds every run of adjacent MESSAGE segments, and every run of adjacent THOUGHT segments, into a
 * single segment whose text is their concatenation, keeping the run's first id so the key set is
 * stable - the absorbed trailing ids simply drop out. (The cached markdown entry is still re-parsed:
 * its text no longer matches the first chunk's.) A run never crosses another kind, so a THOUGHT
 * between two reply runs leaves those runs separate; tool calls and plans keep their place.
 * Different block annotations or metadata are also boundaries, so folding cannot discard them.
 *
 * Linear in the run length: one StringBuilder per run, rather than one `text +` per segment
 * (which would be O(n^2) inside the run - the very cost the per-chunk shape avoids).
 */
internal fun mergedTextRuns(segments: PersistentList<AssistantSegment>): PersistentList<AssistantSegment> {
    if (!segments.hasAdjacentTextRuns()) return segments
    val merged = ArrayList<AssistantSegment>(segments.size)
    var index = 0
    while (index < segments.size) {
        val segment = segments[index]
        index++
        val block = segment.contentBlock
        if (segment.kind !in textKinds || block !is ContentBlock.Text) {
            merged.add(segment)
            continue
        }
        val runText = StringBuilder(segment.text)
        while (index < segments.size && segment.canFoldWith(segments[index])) {
            runText.append(segments[index].text)
            index++
        }
        merged.add(segment.copy(contentBlock = block.copy(text = runText.toString())))
    }
    return merged.toPersistentList()
}

private val textKinds = setOf(AssistantSegment.Kind.MESSAGE, AssistantSegment.Kind.THOUGHT)

/** True when adjacent text segments share their kind and metadata, so folding preserves their meaning. */
internal fun List<AssistantSegment>.hasAdjacentTextRuns(): Boolean =
    (1 until size).any { index -> this[index - 1].canFoldWith(this[index]) }

private fun AssistantSegment.canFoldWith(other: AssistantSegment): Boolean {
    val block = contentBlock as? ContentBlock.Text
    val next = other.contentBlock as? ContentBlock.Text
    return kind in textKinds &&
        kind == other.kind &&
        block != null &&
        next != null &&
        block.copy(text = "") == next.copy(text = "")
}
