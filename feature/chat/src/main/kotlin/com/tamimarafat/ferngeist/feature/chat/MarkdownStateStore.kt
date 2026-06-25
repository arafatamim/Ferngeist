package com.tamimarafat.ferngeist.feature.chat

import com.mikepenz.markdown.model.parseMarkdownFlow
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mikepenz.markdown.model.State as MarkdownRenderState
import java.util.concurrent.Executors

/**
 * Maintains a cached set of parsed markdown states for assistant messages.
 *
 * Parsing is batched and throttled to avoid UI jank during streaming updates.
 */
internal class MarkdownStateStore(
    private val scope: CoroutineScope,
    private val currentMessages: () -> List<ChatMessage>,
    private val onMarkdownStatesChanged: (Map<String, MarkdownRenderState>) -> Unit,
    private val trace: (String) -> Unit,
) {
    companion object {
        // 80ms (~12 parses/sec) keeps the chat surface responsive during
        // streaming. The previous 28ms interval drove ~35 parses/sec, which
        // caused CPU contention with UI work and Room reads.
        private const val MARKDOWN_FLUSH_INTERVAL_MS = 80L
        private const val MARKDOWN_BATCH_SIZE = 24
        private const val MARKDOWN_STATE_EMIT_INTERVAL_MS = 120L
    }

    // Dedicated dispatcher isolates markdown parsing from shared Default pool
    // (Room queries, image decoding, etc.) to prevent CPU contention.
    private val markdownDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var initialHydrated: Boolean = false
    private val markdownStateCache = linkedMapOf<String, MarkdownEntry>()
    private val pendingMarkdownQueue = linkedMapOf<String, String>()
    private val markdownParsingKeys = mutableSetOf<String>()
    private var markdownParserJob: Job? = null

    /**
     * Updates markdown cache for a new snapshot and returns the projection to render.
     *
     * During initial hydration, missing entries are pre-parsed synchronously so the
     * first render can show rich markdown without waiting for the scheduler.
     */
    suspend fun onSnapshot(
        messages: List<ChatMessage>,
        loadState: ChatLoadState,
    ): MarkdownStateProjection {
        val requiredEntries = collectRequiredEntries(messages)
        if (!initialHydrated && loadState != ChatLoadState.FAILED) {
            preparseMissingEntries(requiredEntries)
            if (loadState == ChatLoadState.READY) {
                initialHydrated = true
            }
        }

        val markdownStates = buildMarkdownEntries(messages)
        val pendingInitialHydration =
            loadState != ChatLoadState.FAILED &&
                !initialHydrated &&
                messages.isNotEmpty()
        return MarkdownStateProjection(
            markdownStates = markdownStates,
            pendingInitialHydration = pendingInitialHydration,
        )
    }

    /** Clears cached states and cancels any in-flight parsing work. */
    fun reset() {
        initialHydrated = false
        markdownParserJob?.cancel()
        markdownParserJob = null
        markdownStateCache.clear()
        pendingMarkdownQueue.clear()
        markdownParsingKeys.clear()
        (markdownDispatcher as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.close()
    }

    /**
     * Extracts assistant message content that requires markdown parsing.
     */
    private fun collectRequiredEntries(messages: List<ChatMessage>): LinkedHashMap<String, String> {
        val requiredEntries = linkedMapOf<String, String>()
        messages.forEach { message ->
            if (message.role != ChatMessage.Role.ASSISTANT) return@forEach
            if (message.segments.isNotEmpty()) {
                message.segments.forEach { segment ->
                    if (segment.kind == AssistantSegment.Kind.MESSAGE && segment.text.isNotBlank()) {
                        requiredEntries[segment.id] = segment.text
                    }
                }
            } else if (message.content.isNotBlank()) {
                requiredEntries[message.id] = message.content
            }
        }
        return requiredEntries
    }

    private suspend fun preparseMissingEntries(requiredEntries: Map<String, String>) {
        // Parse missing entries in parallel. `parse()` switches to
        // Dispatchers.Default internally, so the `async` here fans out across
        // the Default dispatcher pool instead of blocking the snapshot
        // coroutine on a single sequential loop.
        val (keys, results) =
            coroutineScope {
                val pending =
                    requiredEntries.filter { (key, text) ->
                        markdownStateCache[key]?.text != text
                    }
                val deferreds =
                    pending.map { (key, text) ->
                        async {
                            try {
                                key to MarkdownEntry(text = text, state = parse(text))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                trace("markdownPreparse:error key=$key message=${error.message}")
                                key to null
                            }
                        }
                    }
                deferreds.awaitAll().unzip()
            }
        // Sequential map writes preserve LinkedHashMap insertion order for
        // downstream render projections.
        keys.forEach { pendingMarkdownQueue.remove(it) }
        keys.forEach { markdownParsingKeys.remove(it) }
        results.forEachIndexed { index, entry ->
            entry?.let { markdownStateCache[keys[index]] = it }
        }
    }

    /**
     * Synchronizes the cache with current messages and schedules parsing for deltas.
     */
    private fun buildMarkdownEntries(messages: List<ChatMessage>): Map<String, MarkdownRenderState> {
        val requiredEntries = collectRequiredEntries(messages)
        val requiredKeys = requiredEntries.keys

        markdownStateCache.keys
            .toList()
            .filterNot(requiredKeys::contains)
            .forEach { markdownStateCache.remove(it) }
        pendingMarkdownQueue.keys
            .toList()
            .filterNot(requiredKeys::contains)
            .forEach { pendingMarkdownQueue.remove(it) }
        markdownParsingKeys.retainAll(requiredKeys)

        requiredEntries.forEach { (key, text) ->
            val cached = markdownStateCache[key]
            if (cached?.text == text) return@forEach
            pendingMarkdownQueue[key] = text
        }

        if (pendingMarkdownQueue.isNotEmpty()) {
            scheduleMarkdownParsing()
        }

        return requiredKeys
            .mapNotNull { key ->
                markdownStateCache[key]?.state?.let { key to it }
            }.toMap()
    }

    /**
     * Launches a background parser that drains the pending queue in batches.
     */
    private fun scheduleMarkdownParsing() {
        if (markdownParserJob?.isActive == true) return

        markdownParserJob =
            scope.launch {
                var lastEmitAtMs = 0L
                try {
                    while (pendingMarkdownQueue.isNotEmpty()) {
                        val batch = mutableListOf<Pair<String, String>>()
                        val iterator = pendingMarkdownQueue.entries.iterator()
                        while (iterator.hasNext() && batch.size < MARKDOWN_BATCH_SIZE) {
                            val entry = iterator.next()
                            iterator.remove()
                            batch += entry.key to entry.value
                            markdownParsingKeys += entry.key
                        }

                        val parsedBatch = mutableListOf<Pair<String, MarkdownEntry>>()
                        batch.forEach { (key, text) ->
                            try {
                                val parsedState = parse(text)
                                parsedBatch += key to MarkdownEntry(text = text, state = parsedState)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                trace("markdownParse:error key=$key message=${error.message}")
                            } finally {
                                markdownParsingKeys.remove(key)
                            }
                        }

                        parsedBatch.forEach { (key, entry) ->
                            val queuedOverride = pendingMarkdownQueue[key]
                            if (queuedOverride == null || queuedOverride == entry.text) {
                                markdownStateCache[key] = entry
                            }
                        }

                        val now = System.currentTimeMillis()
                        val shouldEmitState =
                            pendingMarkdownQueue.isEmpty() ||
                                (now - lastEmitAtMs) >= MARKDOWN_STATE_EMIT_INTERVAL_MS
                        if (shouldEmitState) {
                            lastEmitAtMs = now
                            publishMarkdownStatesForCurrentMessages()
                        }

                        if (pendingMarkdownQueue.isNotEmpty()) {
                            delay(MARKDOWN_FLUSH_INTERVAL_MS)
                        }
                    }
                } finally {
                    markdownParserJob = null
                }
            }
    }

    /**
     * Parses markdown text and returns the first non-loading state.
     * Uses dedicated dispatcher to avoid competing with UI/DB work on Default pool.
     */
    private suspend fun parse(text: String): MarkdownRenderState =
        withContext(markdownDispatcher) {
            parseMarkdownFlow(text)
                .first { it !is MarkdownRenderState.Loading }
        }

    /** Emits markdown states for the currently rendered messages. */
    private fun publishMarkdownStatesForCurrentMessages() {
        onMarkdownStatesChanged(buildMarkdownEntries(currentMessages()))
    }

    private data class MarkdownEntry(
        val text: String,
        val state: MarkdownRenderState,
    )
}

/** Snapshot of markdown state used to render the current message list. */
internal data class MarkdownStateProjection(
    val markdownStates: Map<String, MarkdownRenderState>,
    val pendingInitialHydration: Boolean,
)
