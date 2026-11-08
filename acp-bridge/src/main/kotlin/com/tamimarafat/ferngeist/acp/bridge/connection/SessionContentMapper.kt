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

/** A user message's prose with the attachments an agent inlined into it as text pulled back out. */
internal data class SplitUserText(
    val text: String,
    val files: List<ChatFileData>,
)

// Codex replays an attachment as `[@name](file:///name)` plus a base64 `<context>` block inside the
// user's text, not as a resource block. Left as text, a 500 KB zip is one unbreakable 700 KB line.
private val embeddedContext =
    Regex(
        """(?:\[@[^\]]*]\([^)]*\)\s*)?""" +
            """<context ref="([^"]*)" mimeType="([^"]*)" encoding="base64">([^<]*)</context>""",
    )

/** Turns the base64 `<context>` blocks in [text] back into file attachments, keeping the rest as text. */
internal fun splitEmbeddedContexts(text: String): SplitUserText {
    val blocks = embeddedContext.findAll(text).toList()
    if (blocks.isEmpty()) return SplitUserText(text, emptyList())
    val files =
        blocks.mapNotNull { block ->
            val (ref, mimeType, body) = block.destructured
            val blob = body.filterNot(Char::isWhitespace)
            blob.takeIf { it.isNotEmpty() }?.let {
                ChatFileData(
                    name = ref.substringAfterLast('/').ifBlank { "file" },
                    base64 = it,
                    mimeType = mimeType.ifBlank { "application/octet-stream" },
                    sizeBytes = it.length.toLong() * 3L / 4L,
                )
            }
        }
    return SplitUserText(embeddedContext.replace(text, "").trim(), files)
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
