package com.tamimarafat.ferngeist.feature.chat.ui.content

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.content.chatImage
import com.tamimarafat.ferngeist.feature.chat.ui.audio.AudioContentBlock
import com.tamimarafat.ferngeist.feature.chat.ui.message.ImageAttachmentItem

private const val MAX_TEXT_CHARS = 5000

/** One content block rendered the same way in a prompt, a reply, or a tool's output. */
@Composable
internal fun ContentBlockRenderer(
    block: ContentBlock,
    onImageClick: ((ChatImageData) -> Unit)? = null,
) {
    when (block) {
        is ContentBlock.Text -> TextContentBlock(block.text)
        is ContentBlock.Image ->
            block.chatImage()?.let { image ->
                ImageAttachmentItem(image, onClick = onImageClick?.let { { it(image) } })
            }
        is ContentBlock.Audio -> AudioContentBlock(block)
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
