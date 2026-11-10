package com.tamimarafat.ferngeist.feature.chat.markdown.files

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.git.R
import com.tamimarafat.ferngeist.core.git.ui.FileMarkdownContent
import com.tamimarafat.ferngeist.core.git.ui.FileMarkdownRenderer
import com.tamimarafat.ferngeist.core.git.ui.localFileMarkdownRenderer
import com.tamimarafat.ferngeist.feature.chat.IncrementalMarkdown
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.markdown.MarkdownBlocksLazy
import com.tamimarafat.ferngeist.feature.chat.markdown.documentMarkdownTypography
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Shares the transcript's markdown rendering with whole files without reversing module dependencies. */
@Composable
fun ProvideFileMarkdown(content: @Composable () -> Unit) {
    CompositionLocalProvider(localFileMarkdownRenderer provides ChatFileMarkdownRenderer, content = content)
}

private object ChatFileMarkdownRenderer : FileMarkdownRenderer {
    @Composable
    override fun prepare(
        text: String?,
        truncated: Boolean,
        dispatcher: CoroutineDispatcher,
    ): FileMarkdownContent? {
        val document by produceState<MarkdownRenderedDocument?>(null, text) {
            if (text.isNullOrEmpty()) return@produceState
            value = withContext(dispatcher) { IncrementalMarkdown().apply { update(text) }.document }
        }
        return document?.let { parsed -> FileMarkdownContent { state -> MarkdownFileBody(parsed, truncated, state) } }
    }
}

/**
 * The file parsed as markdown, in a lazy list so a long README does not compose every block.
 * The document is parsed while the sheet opens, so switching modes only swaps which list draws.
 */
@Composable
private fun MarkdownFileBody(
    document: MarkdownRenderedDocument,
    truncated: Boolean,
    listState: LazyListState,
) {
    if (document.blocks.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_file_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val footer: (@Composable () -> Unit)? =
            if (truncated) {
                {
                    Text(
                        text = stringResource(R.string.chat_file_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                null
            }
        MarkdownBlocksLazy(
            blocks = document.blocks,
            typography = documentMarkdownTypography(),
            state = listState,
            footer = footer,
        )
    }
}
