package com.tamimarafat.ferngeist.feature.chat

import androidx.compose.runtime.Immutable
import com.adamglin.compose.markdown.core.api.MarkdownEngine
import com.adamglin.compose.markdown.core.dialect.MarkdownDialect
import com.adamglin.compose.markdown.core.model.BlockNode
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage

/**
 * Maintains parsed markdown per assistant *run*, driven by an append-only incremental engine.
 *
 * A streamed reply arrives as one segment per chunk, so parsing per segment would parse each chunk
 * as its own markdown document: a fence or bold span cut at a chunk boundary becomes an unterminated
 * fragment. Runs and parse keys come from the same definition the renderer draws from, so the text
 * drawn and the cache key cannot disagree.
 *
 * This store parses whatever has *arrived* and nothing more: no pacing, no clock, no animation
 * state. How much of a document is visible at a given frame is the composition's call
 * (`RunReveal`, docs/adr/0001). The incremental engine still matters for that: a settled block
 * keeps its id across appends, so the renderer keyed on it keeps the node mounted.
 */
internal class MarkdownStateStore {
    private companion object {
        /**
         * GfmCompat rather than ChatFast: it keeps reference links (ChatFast disables them and
         * agents do emit them) and adds table alignment. Definitions are single-line only.
         */
        val DIALECT = MarkdownDialect.GfmCompat
    }

    private val runs = linkedMapOf<String, RunEntry>()
    private var initialHydrated: Boolean = false

    /**
     * Parses what changed in [messages] and returns the projection to render.
     *
     * Runs on the caller's thread (the snapshot collector, on main). A streaming chunk is a tail
     * append and costs little. ponytail: opening a long history parses every run here at once;
     * inject a background dispatcher (one thread, so the engines never see two writers) if session
     * open measurably janks.
     */
    fun onSnapshot(
        messages: List<ChatMessage>,
        loadState: ChatLoadState,
    ): MarkdownStateProjection {
        val documents = sync(messages)
        if (loadState == ChatLoadState.READY) initialHydrated = true
        return MarkdownStateProjection(
            documents = documents,
            pendingInitialHydration =
                loadState != ChatLoadState.FAILED && !initialHydrated && messages.isNotEmpty(),
        )
    }

    /** Drops every cached run. Called when a different session loads. */
    fun reset() {
        initialHydrated = false
        runs.clear()
    }

    private fun sync(messages: List<ChatMessage>): Map<String, MarkdownRenderedDocument> {
        val required = collectRequiredEntries(messages)
        runs.keys.retainAll(required.keys)
        required.forEach { (key, text) -> runs.getOrPut(key) { RunEntry(DIALECT) }.update(text) }
        return required.keys.associateWith { runs.getValue(it).document }
    }

    /**
     * Extracts assistant content that requires markdown parsing, one entry per MESSAGE *run*, keyed
     * by the run's first chunk.
     */
    private fun collectRequiredEntries(messages: List<ChatMessage>): LinkedHashMap<String, String> {
        val required = linkedMapOf<String, String>()
        messages.forEach { message ->
            if (message.role != ChatMessage.Role.ASSISTANT) return@forEach
            if (message.segments.isNotEmpty()) {
                message.segments.messageRuns().forEach { run ->
                    if (run.text.isNotBlank()) required[run.key] = run.text
                }
            } else if (message.content.isNotBlank()) {
                required[message.id] = message.content
            }
        }
        return required
    }

    /** One run's engine and the text it has already been fed. */
    private class RunEntry(
        dialect: MarkdownDialect,
    ) {
        private val engine = MarkdownEngine(dialect)
        private var fed: String = ""

        var document: MarkdownRenderedDocument = MarkdownRenderedDocument(emptyList())
            private set

        /**
         * Feeds only the part of [text] the engine has not seen. Feeding the whole text again would
         * append a second copy of the run. A run that stops being a prefix of what was fed (history
         * reload, an edited turn) starts over, so block ids and text cannot disagree.
         */
        fun update(text: String) {
            if (text == fed) return
            if (!text.startsWith(fed)) {
                engine.reset()
                fed = ""
            }
            val delta = engine.append(text.substring(fed.length))
            fed = text
            document = MarkdownRenderedDocument(delta.snapshot.document.blocks)
        }
    }
}

/** What the bubble renders for one run: the blocks parsed from everything that has arrived. */
@Immutable
data class MarkdownRenderedDocument(
    val blocks: List<BlockNode>,
)

/** Snapshot of markdown state used to render the current message list. */
internal data class MarkdownStateProjection(
    val documents: Map<String, MarkdownRenderedDocument>,
    val pendingInitialHydration: Boolean,
)
