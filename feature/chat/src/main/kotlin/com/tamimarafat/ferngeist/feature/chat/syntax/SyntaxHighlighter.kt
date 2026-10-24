package com.tamimarafat.ferngeist.feature.chat.syntax

import androidx.compose.runtime.Immutable
import com.gallatinapps.syntaxmp.spans.SyntaxTokenSpan
import com.gallatinapps.syntaxmp.tokenizer.SyntaxTokenizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tokens for exactly [text], tagged with the raw label and the language the label resolved to. */
@Immutable
internal data class HighlightedCode(
    val text: String,
    val label: String,
    val language: String,
    val tokens: List<SyntaxTokenSpan>,
)

/**
 * Tokenizes code off the main thread and keeps a bounded cache of the results.
 *
 * A tokenizer instance caches its own analysis, but only for the last snippet it saw; chat
 * scrolls back through many blocks, so the LRU here is what keeps a re-entered block from
 * re-parsing. The cache is keyed by canonical language and text, never by theme: a light/dark
 * switch restyles the cached spans instead of re-tokenizing.
 */
internal class SyntaxHighlighter(
    private val tokenizer: SyntaxTokenizer = SyntaxTokenizer(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    companion object {
        /** Above this a block/file stays plain; the cap is what keeps tokenization bounded. */
        const val MAX_CHARS = 100_000

        /** A fence still streaming is tokenized only up to this size. */
        const val STREAMING_MAX_CHARS = 20_000

        /** Larger results are used once but not retained: 64 cached files would hold too much text. */
        const val CACHE_MAX_CHARS = 20_000

        /** Wait this long for more streamed text before tokenizing the fence. */
        const val STREAM_DEBOUNCE_MS = 75L

        const val CACHE_ENTRIES = 64
    }

    private val cache =
        object : LinkedHashMap<Pair<String, String>, List<SyntaxTokenSpan>>(CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<String, String>, List<SyntaxTokenSpan>>,
            ): Boolean = size > CACHE_ENTRIES
        }

    suspend fun highlight(
        code: String,
        label: String?,
    ): HighlightedCode? {
        val rawLabel = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val language = tokenizer.resolveLanguageId(rawLabel)?.value ?: return null
        if (code.isEmpty() || code.length > MAX_CHARS) return null

        val key = language to code
        val cacheable = code.length <= CACHE_MAX_CHARS
        val tokens =
            (if (cacheable) synchronized(cache) { cache[key] } else null) ?: withContext(dispatcher) {
                tokenizer.tokenize(code, language).also { result ->
                    if (cacheable) {
                        synchronized(cache) { cache[key] = result }
                    }
                }
            }
        return HighlightedCode(text = code, label = rawLabel, language = language, tokens = tokens)
    }
}
