package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import java.net.URI

internal fun extractText(content: ContentBlock): String =
    when (content) {
        is ContentBlock.Text -> content.text
        else -> ""
    }

/**
 * A reply block as Markdown text. A host file link is not text (see [extractLink]); a link to any
 * other scheme stays an ordinary link, and an embedded text resource shows inline as a fenced block.
 */
internal fun extractReplyText(content: ContentBlock): String =
    when (content) {
        is ContentBlock.ResourceLink -> if (hostPath(content.uri) == null) webLink(content) else ""
        is ContentBlock.Resource ->
            (content.resource as? EmbeddedResourceResource.TextResourceContents)?.let(::fenced).orEmpty()
        else -> extractText(content)
    }

/** The host file a `file://` resource link names, with the agent's description of it. */
internal fun extractLink(content: ContentBlock): ChatResourceLink? {
    if (content !is ContentBlock.ResourceLink) return null
    val path = hostPath(content.uri) ?: return null
    return ChatResourceLink(
        path = path,
        name = content.name.ifBlank { path.substringAfterLast('/') },
        mimeType = content.mimeType?.takeIf { it.isNotBlank() },
        size = content.size,
    )
}

private val windowsDrive = Regex("^/[A-Za-z]:/")

/** The absolute host path of a `file://` [uri], or null for another scheme. */
private fun hostPath(uri: String): String? {
    if (!uri.startsWith("file://")) return null
    // Agents also send paths unencoded, spaces and all, which URI rejects: then the rest is the path.
    val path = runCatching { URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
    return when {
        windowsDrive.containsMatchIn(path) -> path.drop(1)
        path.startsWith("/") -> path
        else -> null
    }
}

// Blank lines on both sides: the block arrives between chunks that may not end or start a line.
private fun webLink(link: ContentBlock.ResourceLink): String {
    val label =
        link.name
            .ifBlank { link.uri }
            .replace("[", "\\[")
            .replace("]", "\\]")
    return "\n\n[$label](<${link.uri}>)\n\n"
}

private val backtickRun = Regex("`+")

private fun fenced(resource: EmbeddedResourceResource.TextResourceContents): String {
    // Longer than any backtick run inside, so the content cannot close the fence early.
    val longest = backtickRun.findAll(resource.text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    val language = resource.uri.substringAfterLast('/').substringAfterLast('.', "")
    return "\n\n$fence$language\n${resource.text.removeSuffix("\n")}\n$fence\n\n"
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
