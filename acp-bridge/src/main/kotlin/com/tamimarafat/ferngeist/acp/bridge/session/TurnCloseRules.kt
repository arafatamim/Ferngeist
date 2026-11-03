package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * True when turn close must rewrite [message]: it is still streaming, its flat `content` view
 * has not been derived yet, or its reply or reasoning chunks still stand as one segment each
 * and need folding into one segment per run (see [mergedTextRuns]).
 */
internal fun ChatMessage.needsTurnCloseRewrite(): Boolean =
    isStreaming ||
        (
            role == ChatMessage.Role.ASSISTANT &&
                (needsDerivedContent() || segments.hasAdjacentTextRuns())
        )

/**
 * Settles a bubble for rendering: clears the streaming flag, folds adjacent MESSAGE (and THOUGHT)
 * segments into one markdown document per run, and derives the flat `content` view when it is still
 * empty. The fold does not change that view - it is the join of every MESSAGE segment's text in
 * order - so an already-derived `content` is carried through untouched.
 */
internal fun ChatMessage.closedByTurnClose(): ChatMessage {
    if (role != ChatMessage.Role.ASSISTANT) return copy(isStreaming = false)
    val merged = mergedTextRuns(segments)
    return copy(
        isStreaming = false,
        segments = merged,
        content = if (content.isEmpty()) derivedContent(merged) else content,
    )
}

/**
 * Folds every run of adjacent MESSAGE segments, and every run of adjacent THOUGHT segments, into a
 * single segment whose text is their concatenation, keeping the run's first id so the key set is
 * stable - the absorbed trailing ids simply drop out. (The cached markdown entry is still re-parsed:
 * its text no longer matches the first chunk's.) A run never crosses another kind, so a THOUGHT
 * between two reply runs leaves those runs separate; tool calls and plans keep their place.
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
        if (segment.kind !in textKinds) {
            merged.add(segment)
            continue
        }
        val runText = StringBuilder(segment.text)
        while (index < segments.size && segments[index].kind == segment.kind) {
            runText.append(segments[index].text)
            index++
        }
        merged.add(segment.copy(text = runText.toString()))
    }
    return merged.toPersistentList()
}

private val textKinds = setOf(AssistantSegment.Kind.MESSAGE, AssistantSegment.Kind.THOUGHT)

/** True when two MESSAGE or two THOUGHT segments sit next to each other - the shape a chunked
 * stream leaves behind, and the only shape [mergedTextRuns] has to rewrite. */
internal fun List<AssistantSegment>.hasAdjacentTextRuns(): Boolean =
    (1 until size).any { index -> this[index].kind in textKinds && this[index].kind == this[index - 1].kind }

/** Flat text view of the MESSAGE segments, in the order the agent emitted them. */
internal fun derivedContent(segments: List<AssistantSegment>): String =
    segments
        .filter { it.kind == AssistantSegment.Kind.MESSAGE }
        .joinToString("") { it.text }

/** True when [this] is an assistant bubble whose flat `content` has not been derived yet.
 * Requires a MESSAGE segment specifically: a tool-call- or thought-only bubble derives
 * an empty string, so treating it as pending would make this never settle and re-derive
 * the whole list on every turn close. */
internal fun ChatMessage.needsDerivedContent(): Boolean =
    role == ChatMessage.Role.ASSISTANT &&
        content.isEmpty() &&
        segments.any { it.kind == AssistantSegment.Kind.MESSAGE }
