package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.chatFiles
import com.tamimarafat.ferngeist.core.model.chatImages
import com.tamimarafat.ferngeist.feature.chat.selection.QuoteSelectionContainer
import com.tamimarafat.ferngeist.feature.chat.ui.ContentBlockRenderer

/**
 * A user message's ACP blocks in their original order. Text stays a plain prompt line, images and
 * files keep the composer's attachment cards, and every other block goes through the same
 * [ContentBlockRenderer] a tool call uses, so replay and live tool output cannot drift apart.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UserContentBlocks(
    blocks: List<ContentBlock>,
    textColor: Color,
    maxLines: Int,
    onImageClick: ((ChatImageData) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var first = true
        var index = 0
        while (index < blocks.size) {
            val end = blocks.runEnd(index)
            val run = blocks.subList(index, end)
            val text = run.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
            if (text.isNotEmpty() || blocks[index] !is ContentBlock.Text) {
                if (!first) Spacer(Modifier.height(8.dp))
                UserContentRun(
                    run = run,
                    textColor = textColor,
                    maxLines = maxLines,
                    onImageClick = onImageClick,
                )
                first = false
            }
            index = end
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserContentRun(
    run: List<ContentBlock>,
    textColor: Color,
    maxLines: Int,
    onImageClick: ((ChatImageData) -> Unit)?,
) {
    when (val block = run.first()) {
        is ContentBlock.Text ->
            UserText(
                text = run.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text },
                textColor = textColor,
                maxLines = maxLines,
            )
        is ContentBlock.Image -> ImageAttachments(run.chatImages(), onImageClick = onImageClick)
        is ContentBlock.Resource ->
            if (block.resource is EmbeddedResourceResource.BlobResourceContents) {
                FileAttachments(run.chatFiles())
            } else {
                ContentBlockRenderer(block)
            }
        else -> ContentBlockRenderer(block)
    }
}

@Composable
private fun UserText(
    text: String,
    textColor: Color,
    maxLines: Int,
) {
    QuoteSelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** End of the run that starts at [start]: adjacent text, images, or embedded blobs stay together. */
private fun List<ContentBlock>.runEnd(start: Int): Int {
    var end = start + 1
    while (end < size && this[start].isSameRun(this[end])) end++
    return end
}

private fun ContentBlock.isSameRun(other: ContentBlock): Boolean =
    when (this) {
        is ContentBlock.Text -> other is ContentBlock.Text
        is ContentBlock.Image -> other is ContentBlock.Image
        is ContentBlock.Resource -> other is ContentBlock.Resource && isBlob(this) && isBlob(other)
        else -> false
    }

private fun isBlob(block: ContentBlock.Resource): Boolean =
    block.resource is EmbeddedResourceResource.BlobResourceContents
