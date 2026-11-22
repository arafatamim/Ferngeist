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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.content.chatImage
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.AttachmentCard
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.EmbeddedBlobCard
import com.tamimarafat.ferngeist.feature.chat.artifact.ui.attachmentCardModifier
import com.tamimarafat.ferngeist.feature.chat.ui.audio.AudioContentBlock
import com.tamimarafat.ferngeist.feature.chat.ui.image.rememberDecodedImageBitmap

private const val MAX_TEXT_CHARS = 5000

@Composable
internal fun AssistantContent(
    segment: AssistantSegment,
    onImageClick: ((ChatImageData) -> Unit)?,
) {
    val blob = segment.cachedBlob
    if (blob != null) {
        EmbeddedBlobCard(blob)
    } else {
        segment.contentBlock?.let { ContentBlockRenderer(it, onImageClick) }
    }
}

/** One content block rendered the same way in a prompt, a reply, or a tool's output. */
@Composable
internal fun ContentBlockRenderer(
    block: ContentBlock,
    onImageClick: ((ChatImageData) -> Unit)? = null,
) {
    when (block) {
        is ContentBlock.Text -> TextContentBlock(block.text)
        is ContentBlock.Image -> block.chatImage()?.let { image -> ImageAttachmentCard(image, onImageClick) }
        is ContentBlock.Audio -> AudioContentBlock(block)
        is ContentBlock.ResourceLink -> ResourceLinkContentBlock(block)
        is ContentBlock.Resource -> ResourceContentBlock(block.resource)
    }
}

/**
 * An image as the transcript's attachment card: its own face while it decodes, a square badge card
 * when it cannot be shown, and the full image behind a tap. A base64 prompt image carries no name,
 * so the card names it by kind and its MIME type is the state.
 */
@Composable
private fun ImageAttachmentCard(
    image: ChatImageData,
    onImageClick: ((ChatImageData) -> Unit)?,
) {
    val bitmap = rememberDecodedImageBitmap(image.base64)
    AttachmentCard(
        path = "",
        label = stringResource(R.string.chat_image_desc),
        size = null,
        loading = false,
        failed = bitmap == null,
        onOpen = onImageClick?.let { { it(image) } },
        modifier = attachmentCardModifier(bitmap?.let { it.width.toFloat() / it.height }),
        failureMessage = image.mimeType,
        preview = bitmap,
    )
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
