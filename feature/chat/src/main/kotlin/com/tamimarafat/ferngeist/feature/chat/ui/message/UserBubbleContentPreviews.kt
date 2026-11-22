package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus

/** Every content shape a prompt can carry, plus the pending-queue clamp. */
@Preview(name = "Multiline text", widthDp = 420)
@Composable
internal fun UserBubbleMultilinePreview() = UserBubblePreview(userMessage(PREVIEW_LONG_PROMPT))

@Preview(name = "Image attachment", widthDp = 420)
@Composable
internal fun UserBubbleImagePreview() =
    UserBubblePreview(
        userMessage(blocks = listOf(ContentBlock.Image(data = PREVIEW_IMAGE_BASE64, mimeType = PREVIEW_PNG_MIME))),
    )

/** Bytes that are not an image: the attachment falls back to an icon and its MIME type. */
@Preview(name = "Image attachment, undecodable", widthDp = 420)
@Composable
internal fun UserBubbleUndecodableImagePreview() =
    UserBubblePreview(
        userMessage(
            blocks = listOf(ContentBlock.Image(data = PREVIEW_NOT_AN_IMAGE_BASE64, mimeType = "image/heic")),
        ),
    )

@Preview(name = "File attachment", widthDp = 420)
@Composable
internal fun UserBubbleFilePreview() =
    UserBubblePreview(
        userMessage(
            blocks =
                listOf(
                    ContentBlock.Resource(
                        EmbeddedResourceResource.BlobResourceContents(
                            blob = "",
                            uri = "file:///tmp/trip-itinerary.pdf",
                            mimeType = "application/pdf",
                        ),
                    ),
                ),
        ),
    )

@Preview(name = "Audio attachment", widthDp = 420)
@Composable
internal fun UserBubbleAudioPreview() =
    UserBubblePreview(
        userMessage(blocks = listOf(ContentBlock.Audio(data = PREVIEW_IMAGE_BASE64, mimeType = "audio/mp4"))),
    )

/** A non-host link renders as a chip, not a file card. */
@Preview(name = "Resource link", widthDp = 420)
@Composable
internal fun UserBubbleResourceLinkPreview() =
    UserBubblePreview(
        userMessage(
            blocks =
                listOf(
                    ContentBlock.ResourceLink(
                        name = "protocol.md",
                        uri = "https://agentclientprotocol.com/protocol",
                        mimeType = "text/markdown",
                        description = null,
                        size = null,
                    ),
                ),
        ),
    )

@Preview(name = "Embedded text resource", widthDp = 420)
@Composable
internal fun UserBubbleEmbeddedTextPreview() =
    UserBubblePreview(
        userMessage(
            blocks =
                listOf(
                    ContentBlock.Resource(
                        EmbeddedResourceResource.TextResourceContents(
                            text = "fun main() {\n    println(\"hello\")\n}",
                            uri = "file:///tmp/Main.kt",
                            mimeType = "text/plain",
                        ),
                    ),
                ),
        ),
    )

/** Whitespace-only text is dropped, leaving the attachment alone in the bubble. */
@Preview(name = "Blank text with attachment", widthDp = 420)
@Composable
internal fun UserBubbleBlankTextPreview() =
    UserBubblePreview(
        userMessage(
            text = "   ",
            blocks = listOf(ContentBlock.Image(data = PREVIEW_IMAGE_BASE64, mimeType = PREVIEW_PNG_MIME)),
        ),
    )

@Preview(name = "Text with image and file", widthDp = 420)
@Composable
internal fun UserBubbleMixedContentPreview() =
    UserBubblePreview(
        userMessage(
            text = "UI against the latest build, plus the spec.",
            blocks =
                listOf(
                    ContentBlock.Image(data = PREVIEW_IMAGE_BASE64, mimeType = PREVIEW_PNG_MIME),
                    ContentBlock.Resource(
                        EmbeddedResourceResource.BlobResourceContents(
                            blob = "",
                            uri = "file:///tmp/spec.pdf",
                            mimeType = "application/pdf",
                        ),
                    ),
                ),
        ),
    )

/** Pinned in the pending queue: clamped to 3 lines with an ellipsis. */
@Preview(name = "Pending queue, clamped", widthDp = 420)
@Composable
internal fun UserBubblePendingClampedPreview() =
    UserBubblePreview(
        message = userMessage(PREVIEW_LONG_PROMPT, status = MessageDeliveryStatus.QUEUED),
        maxLines = 3,
    )
