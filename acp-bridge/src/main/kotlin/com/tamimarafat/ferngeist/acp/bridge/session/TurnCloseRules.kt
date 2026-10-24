package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * True when turn close must rewrite [message]: it is still streaming, its flat `content` view
 * has not been derived yet, or its reply chunks still stand as one segment each and need
 * folding into one MESSAGE segment (see [mergedMessageSegments]).
 */
internal fun ChatMessage.needsTurnCloseRewrite(): Boolean =
    isStreaming ||
        (
            role == ChatMessage.Role.ASSISTANT &&
                (needsDerivedContent() || segments.hasAdjacentMessageSegments())
        )

/**
 * Settles a bubble for rendering: clears the streaming flag, folds adjacent MESSAGE segments
 * into one markdown document per run, and derives the flat `content` view when it is still
 * empty. The fold does not change that view - it is the join of every MESSAGE segment's text in
 * order - so an already-derived `content` is carried through untouched.
 */
internal fun ChatMessage.closedByTurnClose(): ChatMessage {
    if (role != ChatMessage.Role.ASSISTANT) return copy(isStreaming = false)
    val merged = mergedMessageSegments(segments)
    return copy(
        isStreaming = false,
        segments = merged,
        content = if (content.isEmpty()) derivedContent(merged) else content,
    )
}

/**
 * Folds every run of adjacent MESSAGE segments into a single segment whose text is their
 * concatenation, keeping the run's first id so the key set is stable - the absorbed trailing
 * ids simply drop out. (The cached markdown entry is still re-parsed: its text no longer
 * matches the first chunk's.) Non-MESSAGE segments (thoughts, tool calls, plans) keep their
 * place and are never absorbed, so a THOUGHT between two runs leaves those runs separate.
 *
 * Linear in the run length: one StringBuilder per run, rather than one `text +` per segment
 * (which would be O(n^2) inside the run - the very cost the per-chunk shape avoids).
 */
internal fun mergedMessageSegments(segments: PersistentList<AssistantSegment>): PersistentList<AssistantSegment> {
    if (!segments.hasAdjacentMessageSegments()) return segments
    val merged = ArrayList<AssistantSegment>(segments.size)
    var index = 0
    while (index < segments.size) {
        val segment = segments[index]
        if (segment.kind != AssistantSegment.Kind.MESSAGE) {
            merged.add(segment)
            index++
            continue
        }
        val runText = StringBuilder(segment.text)
        index++
        while (index < segments.size && segments[index].kind == AssistantSegment.Kind.MESSAGE) {
            runText.append(segments[index].text)
            index++
        }
        merged.add(segment.copy(text = runText.toString()))
    }
    return merged.toPersistentList()
}

/** True when two MESSAGE segments sit next to each other - the shape a chunked stream leaves
 * behind, and the only shape [mergedMessageSegments] has to rewrite. */
internal fun List<AssistantSegment>.hasAdjacentMessageSegments(): Boolean =
    (1 until size).any { index ->
        this[index].kind == AssistantSegment.Kind.MESSAGE &&
            this[index - 1].kind == AssistantSegment.Kind.MESSAGE
    }

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
