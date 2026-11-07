package com.tamimarafat.ferngeist.core.model

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource

/**
 * The ordered ACP blocks for a locally composed prompt. Text comes first because the composer
 * sends it as the prompt body; attachments follow in the order the user picked them.
 */
fun userContentBlocks(
    text: String,
    images: List<ChatImageData>,
    files: List<ChatFileData>,
): List<ContentBlock> =
    buildList {
        if (text.isNotEmpty()) add(ContentBlock.Text(text))
        images.forEach { image ->
            add(ContentBlock.Image(data = image.base64, mimeType = image.mimeType))
        }
        files.forEach { file ->
            add(
                ContentBlock.Resource(
                    resource =
                        EmbeddedResourceResource.BlobResourceContents(
                            blob = file.base64,
                            uri = file.name,
                            mimeType = file.mimeType,
                        ),
                ),
            )
        }
    }

/** The image payloads in [this] block list, for the user bubble's image grid. */
fun List<ContentBlock>.chatImages(): List<ChatImageData> =
    filterIsInstance<ContentBlock.Image>().map {
        ChatImageData(base64 = it.data, mimeType = it.mimeType.ifBlank { "image/*" })
    }

/** The embedded blobs in [this] block list, for the user bubble's file cards. */
fun List<ContentBlock>.chatFiles(): List<ChatFileData> =
    mapNotNull { block ->
        val resource =
            (block as? ContentBlock.Resource)?.resource as? EmbeddedResourceResource.BlobResourceContents
                ?: return@mapNotNull null
        ChatFileData(
            name = resource.uri.substringAfterLast('/').ifBlank { "file" },
            base64 = resource.blob,
            mimeType = resource.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            sizeBytes = base64DecodedSize(resource.blob),
        )
    }

private fun base64DecodedSize(value: String): Long {
    val padding = value.takeLast(2).count { it == '=' }
    return (value.length.toLong() * BASE64_BYTES_PER_GROUP / BASE64_CHARS_PER_GROUP - padding).coerceAtLeast(0)
}

private const val BASE64_BYTES_PER_GROUP = 3
private const val BASE64_CHARS_PER_GROUP = 4
