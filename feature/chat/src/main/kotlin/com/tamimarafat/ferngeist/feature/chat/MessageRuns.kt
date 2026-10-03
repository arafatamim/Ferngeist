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

private fun List<AssistantSegment>.runsOf(kind: AssistantSegment.Kind): List<SegmentBlock.Run> =
    displayBlocks().filterIsInstance<SegmentBlock.Run>().filter { it.kind == kind }

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

    /** A segment that renders on its own: a tool call or a plan. */
    class Single internal constructor(
        val segment: AssistantSegment,
    ) : SegmentBlock {
        override val key: String get() = segment.id
    }
}

/**
 * The blocks an assistant bubble renders, in segment order.
 *
 * Adjacent MESSAGE and adjacent THOUGHT segments become one [SegmentBlock.Run] each; TOOL_CALL and
 * PLAN segments render on their own as [SegmentBlock.Single]. Order is preserved so a turn that
 * interleaves reasoning, prose and tool calls still reads the way the agent produced it.
 */
internal fun List<AssistantSegment>.displayBlocks(): List<SegmentBlock> {
    if (isEmpty()) return emptyList()
    val blocks = ArrayList<SegmentBlock>()
    var index = 0
    while (index < size) {
        val kind = this[index].kind
        if (kind != AssistantSegment.Kind.MESSAGE && kind != AssistantSegment.Kind.THOUGHT) {
            blocks.add(SegmentBlock.Single(this[index]))
            index++
            continue
        }
        var end = index + 1
        while (end < size && this[end].kind == kind) {
            end++
        }
        val run = this.subList(index, end)
        blocks.add(
            SegmentBlock.Run(
                key = this[index].id,
                kind = kind,
                text = run.joinToString("") { it.text },
                segments = run,
            ),
        )
        index = end
    }
    return blocks
}
