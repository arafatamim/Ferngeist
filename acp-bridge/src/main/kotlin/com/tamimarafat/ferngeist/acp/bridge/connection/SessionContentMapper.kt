package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource

internal fun extractText(content: ContentBlock): String =
    when (content) {
        is ContentBlock.Text -> content.text
        else -> ""
    }

/**
 * The block as a user message stores it: a data-URI image payload is folded into `data`, and a
 * block with nothing displayable is dropped instead of becoming an empty attachment.
 */
internal fun ContentBlock.toUserContentBlock(): ContentBlock? =
    when (this) {
        is ContentBlock.Text -> this
        is ContentBlock.Image -> {
            val payload = data.ifBlank { dataUriBase64(uri) }
            if (payload.isBlank()) {
                null
            } else {
                copy(
                    data = payload,
                    mimeType = mimeType.ifBlank { dataUriMimeType(uri) }.ifBlank { "image/*" },
                    uri = null,
                )
            }
        }
        is ContentBlock.Audio -> takeIf { data.isNotBlank() }
        is ContentBlock.ResourceLink -> this
        is ContentBlock.Resource ->
            when (val resource = resource) {
                is EmbeddedResourceResource.BlobResourceContents -> takeIf { resource.blob.isNotBlank() }
                is EmbeddedResourceResource.TextResourceContents -> this
            }
    }

/** Extracts the base64 payload from a `data:<mime>;base64,<payload>` URI, else "". */
private fun dataUriBase64(uri: String?): String {
    val value = uri.orEmpty()
    val idx = value.indexOf(BASE64_MARKER)
    return if (value.startsWith(DATA_URI_PREFIX) && idx >= 0) value.substring(idx + BASE64_MARKER.length) else ""
}

/** Extracts the media type from a `data:<mime>;base64,...` URI, else "". */
private fun dataUriMimeType(uri: String?): String {
    val value = uri.orEmpty()
    if (!value.startsWith(DATA_URI_PREFIX)) return ""
    val idx = value.indexOf(BASE64_MARKER)
    return if (idx > DATA_URI_PREFIX.length) value.substring(DATA_URI_PREFIX.length, idx) else ""
}

private const val DATA_URI_PREFIX = "data:"
private const val BASE64_MARKER = ";base64,"
