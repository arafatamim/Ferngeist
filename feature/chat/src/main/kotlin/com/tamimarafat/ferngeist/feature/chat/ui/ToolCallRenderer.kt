package com.tamimarafat.ferngeist.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ToolCallContent
import com.tamimarafat.ferngeist.core.common.ui.localSyntaxColors
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.syntax.SyntaxLanguageResolver
import com.tamimarafat.ferngeist.feature.chat.syntax.rememberHighlightedCode
import com.tamimarafat.ferngeist.feature.chat.syntax.styledCode

private const val MAX_TEXT_CHARS = 5000

@Composable
internal fun ContentBlockRenderer(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text -> TextContentBlock(block.text)
        is ContentBlock.Image -> ImageContentBlock(block.data, block.mimeType)
        is ContentBlock.Audio -> AudioContentBlock(block.mimeType)
        is ContentBlock.ResourceLink -> ResourceLinkContentBlock(block)
        is ContentBlock.Resource -> ResourceContentBlock(block.resource)
    }
}

@Composable
private fun TextContentBlock(text: String) {
    val displayText =
        if (text.length > MAX_TEXT_CHARS) {
            text.substring(0, MAX_TEXT_CHARS) + "… (truncated)"
        } else {
            text
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Composable
private fun ImageContentBlock(
    data: String,
    mimeType: String,
) {
    val bitmap =
        remember(data) {
            runCatching {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    if (bitmap != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_tool_output_image, mimeType),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .padding(8.dp),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Text(
            text = stringResource(R.string.chat_failed_decode_image, mimeType),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AudioContentBlock(mimeType: String) {
    Text(
        text = stringResource(R.string.chat_audio_content, mimeType),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ResourceLinkContentBlock(block: ContentBlock.ResourceLink) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Text(
            text = block.name,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = block.uri,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        block.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ResourceContentBlock(resource: EmbeddedResourceResource) {
    when (resource) {
        is EmbeddedResourceResource.TextResourceContents -> {
            val label =
                remember(resource.uri, resource.mimeType) {
                    SyntaxLanguageResolver.fromPath(resource.uri) ?: mimeLabel(resource.mimeType)
                }
            val highlighted = rememberHighlightedCode(resource.text, label, streaming = false)
            val colors = localSyntaxColors.current
            val body =
                remember(resource.text, highlighted, label, colors) {
                    styledCode(resource.text, highlighted, label, colors)
                }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = resource.uri,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is EmbeddedResourceResource.BlobResourceContents -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                Text(
                    text = resource.uri,
                    style = MaterialTheme.typography.bodySmall,
                )
                resource.mimeType?.let { mimeType ->
                    Text(
                        text = stringResource(R.string.chat_binary_resource, mimeType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerminalRenderer(terminal: ToolCallContent.Terminal) {
    Text(
        text = stringResource(R.string.chat_terminal_output, terminal.terminalId),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
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
