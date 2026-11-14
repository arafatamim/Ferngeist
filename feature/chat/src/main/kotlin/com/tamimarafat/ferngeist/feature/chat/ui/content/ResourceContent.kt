package com.tamimarafat.ferngeist.feature.chat.ui.content

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.core.git.syntax.SyntaxLanguageResolver
import com.tamimarafat.ferngeist.core.git.syntax.rememberHighlightedCode
import com.tamimarafat.ferngeist.core.git.syntax.styledCode
import com.tamimarafat.ferngeist.core.model.content.chatFile
import com.tamimarafat.ferngeist.core.model.content.chatResourceLink
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.ResourceLinkCards
import com.tamimarafat.ferngeist.feature.chat.ui.message.FileAttachmentCard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ResourceLinkContentBlock(block: ContentBlock.ResourceLink) {
    val file = remember(block) { block.chatResourceLink() }
    if (file != null) {
        ResourceLinkCards(listOf(file), animate = false)
    } else {
        val uriHandler = LocalUriHandler.current
        val context = LocalContext.current
        AssistChip(
            onClick = {
                runCatching { uriHandler.openUri(block.uri) }.onFailure {
                    Toast.makeText(context, R.string.artifact_no_viewer, Toast.LENGTH_SHORT).show()
                }
            },
            label = { Text(block.name.ifBlank { block.uri }) },
        )
    }
}

@Composable
internal fun ResourceContentBlock(resource: EmbeddedResourceResource) {
    when (resource) {
        is EmbeddedResourceResource.TextResourceContents -> EmbeddedTextAttachment(resource)
        is EmbeddedResourceResource.BlobResourceContents ->
            ContentBlock.Resource(resource).chatFile()?.let { FileAttachmentCard(it.name, it.sizeBytes) }
    }
}

@Composable
private fun EmbeddedTextAttachment(
    resource: EmbeddedResourceResource.TextResourceContents,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var open by remember(resource) { mutableStateOf(false) }
    val size by produceState<Long?>(null, resource.text) {
        value =
            withContext(decodeDispatcher) {
                resource.text
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
            }
    }
    FileAttachmentCard(
        name = resource.uri.substringAfterLast('/').ifBlank { "file" },
        sizeBytes = size,
        onClick = { open = true },
    )
    if (open) EmbeddedTextReader(resource, onDismiss = { open = false })
}

// A supplied file may be a single enormous line: even its reader lays out only bounded chunks.
private const val READER_CHUNK_CHARS = 4096

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbeddedTextReader(
    resource: EmbeddedResourceResource.TextResourceContents,
    onDismiss: () -> Unit,
) {
    val label = remember(resource) { SyntaxLanguageResolver.fromPath(resource.uri) ?: mimeLabel(resource.mimeType) }
    val chunks = remember(resource.text) { resource.text.readerChunks() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(resource.uri, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(chunks.size) { index ->
                        val text = resource.text.substring(chunks[index])
                        val highlighted = rememberHighlightedCode(text, label, streaming = false)
                        val colors = localSyntaxColors.current
                        val body =
                            remember(text, highlighted, label, colors) { styledCode(text, highlighted, label, colors) }
                        Text(body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * [this] cut into ranges of at most [READER_CHUNK_CHARS], each ending after a line break when the
 * window holds one (so highlighting restarts on a line) and never inside a surrogate pair.
 */
internal fun String.readerChunks(): List<IntRange> {
    val text = this
    return buildList {
        var start = 0
        while (start < text.length) {
            val limit = minOf(start + READER_CHUNK_CHARS, text.length)
            // Bounded to this window: lastIndexOf would rescan consumed text, quadratic on one long line.
            val lineEnd = (limit - 1 downTo start).firstOrNull { text[it] == '\n' }
            val end =
                when {
                    limit == text.length -> limit
                    lineEnd != null -> lineEnd + 1
                    text[limit - 1].isHighSurrogate() -> limit - 1
                    else -> limit
                }
            add(start until end)
            start = end
        }
    }
}

/** Maps a resource MIME type to a SyntaxMP label when the URI has no usable extension. */
private fun mimeLabel(mimeType: String?): String? = mimeLabels[mimeType?.substringBefore(';')?.trim()?.lowercase()]

private val mimeLabels =
    mapOf(
        "application/json" to "json",
        "text/json" to "json",
        "application/yaml" to "yaml",
        "text/yaml" to "yaml",
        "application/x-yaml" to "yaml",
        "application/toml" to "toml",
        "text/toml" to "toml",
        "text/markdown" to "markdown",
        "text/html" to "html",
        "text/css" to "css",
        "application/xml" to "xml",
        "text/xml" to "xml",
        "application/sql" to "sql",
        "text/x-sql" to "sql",
        "application/javascript" to "javascript",
        "text/javascript" to "javascript",
        "application/typescript" to "typescript",
        "text/typescript" to "typescript",
        "text/x-java-source" to "java",
        "text/x-kotlin" to "kotlin",
        "text/x-python" to "python",
        "application/x-python" to "python",
        "text/x-shellscript" to "shell",
        "application/x-sh" to "shell",
        "text/x-rust" to "rust",
        "text/x-go" to "go",
        "text/x-ruby" to "ruby",
        "text/x-php" to "php",
        "text/x-swift" to "swift",
        "text/x-csharp" to "csharp",
        "text/x-c" to "c",
        "text/x-c++src" to "cpp",
    )
