package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData

internal fun extractText(content: ContentBlock): String =
    when (content) {
        is ContentBlock.Text -> content.text
        else -> ""
    }

internal fun extractImage(content: ContentBlock): ChatImageData? {
    if (content !is ContentBlock.Image) return null
    val base64 = content.data.ifBlank { dataUriBase64(content.uri) }
    if (base64.isBlank()) return null
    val mimeType = content.mimeType.ifBlank { dataUriMimeType(content.uri) }.ifBlank { "image/*" }
    return ChatImageData(base64 = base64, mimeType = mimeType)
}

internal fun extractFiles(content: ContentBlock): ChatFileData? {
    if (content !is ContentBlock.Resource) return null
    val resource = content.resource as? EmbeddedResourceResource.BlobResourceContents ?: return null
    val blob = resource.blob
    if (blob.isBlank()) return null
    val name = resource.uri.substringAfterLast('/').ifBlank { "file" }
    val mimeType = resource.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    val sizeBytes = blob.length.toLong() * 3L / 4L
    return ChatFileData(name = name, base64 = blob, mimeType = mimeType, sizeBytes = sizeBytes)
}

/** Extracts the base64 payload from a `data:<mime>;base64,<payload>` URI, else "". */
private fun dataUriBase64(uri: String?): String {
    val value = uri.orEmpty()
    val marker = ";base64,"
    val idx = value.indexOf(marker)
    return if (value.startsWith("data:") && idx >= 0) value.substring(idx + marker.length) else ""
}

/** Extracts the media type from a `data:<mime>;base64,...` URI, else "". */
private fun dataUriMimeType(uri: String?): String {
    val value = uri.orEmpty()
    if (!value.startsWith("data:")) return ""
    val idx = value.indexOf(";base64,")
    val start = "data:".length
    return if (idx > start) value.substring(start, idx) else ""
}
