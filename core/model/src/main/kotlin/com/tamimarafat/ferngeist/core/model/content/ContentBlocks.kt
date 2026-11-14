package com.tamimarafat.ferngeist.core.model.content

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import java.net.URI

/** Locally composed prompts use the same ordered representation as their ACP echoes. */
fun userContentBlocks(
    text: String,
    images: List<ChatImageData>,
    files: List<ChatFileData>,
): List<ContentBlock> =
    buildList {
        if (text.isNotEmpty()) add(ContentBlock.Text(text))
        images.forEach { add(ContentBlock.Image(data = it.base64, mimeType = it.mimeType)) }
        files.forEach {
            add(
                ContentBlock.Resource(
                    EmbeddedResourceResource.BlobResourceContents(it.base64, "file:///${it.name}", it.mimeType),
                ),
            )
        }
    }

fun ContentBlock.chatImage(): ChatImageData? =
    (this as? ContentBlock.Image)?.let { ChatImageData(it.data, it.mimeType.ifBlank { "image/*" }) }

fun ContentBlock.chatFile(): ChatFileData? {
    val resource = (this as? ContentBlock.Resource)?.resource as? EmbeddedResourceResource.BlobResourceContents
    return resource?.let {
        ChatFileData(
            name = it.uri.substringAfterLast('/').ifBlank { "file" },
            base64 = it.blob,
            mimeType = it.mimeType?.takeIf(String::isNotBlank) ?: "application/octet-stream",
            sizeBytes =
                (
                    it.blob.length.toLong() * BASE64_BYTES_PER_GROUP / BASE64_CHARS_PER_GROUP -
                        it.blob.takeLast(2).count { char -> char == '=' }
                ).coerceAtLeast(0),
        )
    }
}

/** Agents may send unescaped host paths, so URI parsing falls back to the file scheme's body. */
fun ContentBlock.chatResourceLink(): ChatResourceLink? {
    if (this !is ContentBlock.ResourceLink || !uri.startsWith("file://")) return null
    val raw = runCatching { URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
    val path = if (windowsDrive.containsMatchIn(raw)) raw.drop(1) else raw
    if (!path.startsWith('/') && !windowsPath.containsMatchIn(path)) return null
    return ChatResourceLink(path, name.ifBlank { path.substringAfterLast('/') }, mimeType, size)
}

private val windowsDrive = Regex("^/[A-Za-z]:/")
private val windowsPath = Regex("^[A-Za-z]:/")

private const val BASE64_BYTES_PER_GROUP = 3
private const val BASE64_CHARS_PER_GROUP = 4
