@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.feature.chat.ui.UserMessageBubble

/** Shared stage for the user-bubble previews: app theme, right-aligned in the transcript. */
@Composable
internal fun UserBubblePreview(
    message: ChatMessage,
    maxLines: Int = Int.MAX_VALUE,
    onRetryMessage: ((String) -> Unit)? = {},
) {
    MaterialExpressiveTheme {
        Column(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            UserMessageBubble(
                message = message,
                onRetryMessage = onRetryMessage,
                onImageClick = {},
                maxLines = maxLines,
            )
        }
    }
}

internal fun userMessage(
    text: String? = null,
    blocks: List<ContentBlock> = emptyList(),
    status: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
): ChatMessage =
    ChatMessage(
        role = ChatMessage.Role.USER,
        contentBlocks = listOfNotNull(text?.let { ContentBlock.Text(it) }) + blocks,
        status = status,
    )

internal const val PREVIEW_PROMPT = "Ship the transcript scroll fix tonight."

internal const val PREVIEW_LONG_PROMPT =
    "The transcript jumps when a long reply streams in while I'm reading history. " +
        "Keep the scroll anchored to the message I'm on, then re-follow once I'm at the bottom again."

internal const val PREVIEW_PNG_MIME = "image/png"

// A 32px four-colour PNG: enough for the attachment thumbnail to decode in layoutlib.
internal const val PREVIEW_IMAGE_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAOklEQVR42mOoWPWMJPRrXwVJiGHUglELRi0YtWDUgiFhQc+JaSShEz2XSEKjFoxaMGrBqAWjFgwJCwBj6Uh5CSSJawAAAABJRU5ErkJggg=="

internal const val PREVIEW_NOT_AN_IMAGE_BASE64 = "bm90IGFuIGltYWdl"
