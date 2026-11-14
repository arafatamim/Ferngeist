package com.tamimarafat.ferngeist.acp.bridge.connection

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
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

// Codex replays an attachment as `[@name](file:///name)` plus a base64 `<context>` block inside the
// user's text, not as a resource block. Left as text, a 500 KB zip is one unbreakable 700 KB line.
private val embeddedContext =
    Regex(
        """(?:\[@[^\]]*]\([^)]*\)\s*)?""" +
            """<context ref="([^"]*)" mimeType="([^"]*)" encoding="base64">([^<]*)</context>""",
    )

/** Restores inlined context attachments at their original position, without exposing base64 as prose. */
private fun restoreEmbeddedContexts(content: ContentBlock.Text): List<ContentBlock> {
    val matches = embeddedContext.findAll(content.text).toList()
    if (matches.isEmpty()) return listOf(content)
    val blocks =
        buildList {
            var position = 0
            matches.forEach { match ->
                val before = content.text.substring(position, match.range.first)
                if (before.isNotEmpty()) add(content.copy(text = before))
                match.contextResource(content)?.let(::add)
                position = match.range.last + 1
            }
            val after = content.text.substring(position)
            if (after.isNotEmpty()) add(content.copy(text = after))
        }
    // Preserve the old prompt-matching normalization while leaving the attachment between its prose spans.
    val first = blocks.indexOfFirst { it is ContentBlock.Text }
    val last = blocks.indexOfLast { it is ContentBlock.Text }
    return blocks.mapIndexedNotNull { index, block ->
        if (block !is ContentBlock.Text) return@mapIndexedNotNull block
        val start = if (index == first) block.text.trimStart() else block.text
        val text = if (index == last) start.trimEnd() else start
        text.takeIf(String::isNotEmpty)?.let { block.copy(text = it) }
    }
}

private fun MatchResult.contextResource(source: ContentBlock.Text): ContentBlock.Resource? {
    val (ref, mimeType, body) = destructured
    val blob = body.filterNot(Char::isWhitespace)
    return blob.takeIf(String::isNotEmpty)?.let {
        ContentBlock.Resource(
            EmbeddedResourceResource.BlobResourceContents(
                blob = it,
                uri = ref,
                mimeType = mimeType.ifBlank { "application/octet-stream" },
            ),
            annotations = source.annotations,
            _meta = source._meta,
        )
    }
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

/** Decodes data-URI image metadata once, before either user or reply content enters the runtime. */
internal fun normalizeContent(content: ContentBlock): ContentBlock =
    if (content is ContentBlock.Image) {
        extractImage(content)?.let { content.copy(data = it.base64, mimeType = it.mimeType) } ?: content
    } else {
        content
    }

/** Recovers attachment payloads agents encoded inside a text chunk without displaying base64 as prose. */
internal fun userContent(content: ContentBlock): List<ContentBlock> =
    if (content is ContentBlock.Text) {
        restoreEmbeddedContexts(content)
    } else {
        listOf(normalizeContent(content))
    }
