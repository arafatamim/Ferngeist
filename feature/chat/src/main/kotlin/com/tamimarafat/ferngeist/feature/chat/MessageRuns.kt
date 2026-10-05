package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.AssistantSegment

/*
 * Runs: adjacent same-kind segments, joined for display and for parsing.
 *
 * Chunks arrive as one segment each (`SessionMessageReducer.appendText`) so the data layer never
 * copies an accumulating bubble — the O(n²) that made long transcripts slow to load. Two consumers
 * need the opposite shape and cannot afford that cost: `MessageBubble` renders one markdown block
 * per segment, and `MarkdownStateStore` parses one markdown document per segment. A per-chunk
 * segment therefore becomes one word per line while streaming, and a fence or bold span cut at a
 * chunk boundary parses as an unterminated fragment.
 *
 * Undoing it in the data layer would reintroduce the O(n²), so it is undone here — once, by both
 * consumers, from this single definition. If they disagreed on what a run is, the renderer would
 * draw text that no cache key exists for, which is exactly the "renders as the first two characters,
 * then pops in whole" failure this exists to prevent.
 */

/** Runs of adjacent MESSAGE segments. Other kinds are dropped: prose and reasoning are separate
 *  documents, and absorbing a thought into the prose around it would render the reasoning twice. */
internal fun List<AssistantSegment>.messageRuns(): List<SegmentBlock.Run> = runsOf(AssistantSegment.Kind.MESSAGE)

/** Runs of adjacent THOUGHT segments, for the single reasoning bubble a streamed thought should be. */
internal fun List<AssistantSegment>.thoughtRuns(): List<SegmentBlock.Run> = runsOf(AssistantSegment.Kind.THOUGHT)

// Groups are flattened: a thought rendered as a rail row is still a run the sheet resolves.
private fun List<AssistantSegment>.runsOf(kind: AssistantSegment.Kind): List<SegmentBlock.Run> =
    displayBlocks()
        .flatMap { if (it is SegmentBlock.Group) it.items else listOf(it) }
        .filterIsInstance<SegmentBlock.Run>()
        .filter { it.kind == kind }

/** One rendered block of an assistant bubble. */
internal sealed interface SegmentBlock {
    /** Stable identity for `key()`: the run's first segment id, or the segment's own id. */
    val key: String

    /**
     * Adjacent same-kind segments joined for display and for parsing. [key] is the FIRST segment's
     * id: stable as the run grows, so the parse cache key and the Compose node identity both
     * survive a new chunk.
     */
    class Run internal constructor(
        override val key: String,
        val kind: AssistantSegment.Kind,
        val text: String,
        val segments: List<AssistantSegment>,
    ) : SegmentBlock

    /**
     * Adjacent tool calls and reasoning, rendered as one foldable activity. [items] are THOUGHT
     * [Run]s and one TOOL_CALL [Single] per call. [key] is the FIRST segment's id, so the group's
     * fold and reveal state survive a newly appended item.
     */
    class Group internal constructor(
        override val key: String,
        val items: List<SegmentBlock>,
    ) : SegmentBlock

    /** A segment that renders on its own: a plan, or a tool call inside a [Group]. */
    class Single internal constructor(
        val segment: AssistantSegment,
    ) : SegmentBlock {
        override val key: String get() = segment.id
    }
}

/**
 * The blocks an assistant bubble renders, in segment order.
 *
 * Adjacent MESSAGE segments become one [SegmentBlock.Run]. A span of adjacent THOUGHT and TOOL_CALL
 * segments becomes one [SegmentBlock.Group] if it holds a call, or a THOUGHT run if it is reasoning
 * alone. PLAN segments render on their own as [SegmentBlock.Single]. Order is preserved so a turn
 * that interleaves reasoning, prose and tool calls still reads the way the agent produced it.
 */
internal fun List<AssistantSegment>.displayBlocks(): List<SegmentBlock> {
    val blocks = ArrayList<SegmentBlock>()
    var index = 0
    while (index < size) {
        val end = spanEnd(index)
        val span = subList(index, end)
        blocks +=
            if (span.any { it.kind == AssistantSegment.Kind.TOOL_CALL }) {
                SegmentBlock.Group(key = span.first().id, items = span.foldRuns())
            } else {
                span.foldRuns().single()
            }
        index = end
    }
    return blocks
}

private val ACTIVITY_KINDS = setOf(AssistantSegment.Kind.THOUGHT, AssistantSegment.Kind.TOOL_CALL)

private fun List<AssistantSegment>.spanEnd(start: Int): Int {
    val kind = this[start].kind
    if (kind == AssistantSegment.Kind.PLAN) return start + 1
    var end = start + 1
    while (end < size && continuesSpan(kind, this[end].kind)) {
        end++
    }
    return end
}

private fun continuesSpan(
    first: AssistantSegment.Kind,
    next: AssistantSegment.Kind,
): Boolean = next == first || first in ACTIVITY_KINDS && next in ACTIVITY_KINDS

/** Adjacent MESSAGE or THOUGHT segments joined into runs; every other segment on its own. */
private fun List<AssistantSegment>.foldRuns(): List<SegmentBlock> {
    val blocks = ArrayList<SegmentBlock>()
    var index = 0
    while (index < size) {
        val kind = this[index].kind
        if (kind != AssistantSegment.Kind.MESSAGE && kind != AssistantSegment.Kind.THOUGHT) {
            blocks += SegmentBlock.Single(this[index])
            index++
            continue
        }
        var end = index + 1
        while (end < size && this[end].kind == kind) {
            end++
        }
        val run = subList(index, end)
        blocks +=
            SegmentBlock.Run(key = run.first().id, kind = kind, text = run.joinToString("") { it.text }, segments = run)
        index = end
    }
    return blocks
}
