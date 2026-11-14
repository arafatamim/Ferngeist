package com.tamimarafat.ferngeist.core.model.content

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.tamimarafat.ferngeist.core.model.ChatAudioData
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatPromptResourceLink
import com.tamimarafat.ferngeist.core.model.ChatResourceLink
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Locally composed prompts use the same ordered representation as their ACP echoes.
 *
 * Text files become embedded text resources, not base64 blobs, so an agent can read them as
 * prompt context. Binary files stay blobs. Audio is inline audio content, and resource links are
 * named references the agent resolves itself.
 */
fun userContentBlocks(
    text: String,
    images: List<ChatImageData>,
    files: List<ChatFileData>,
    audios: List<ChatAudioData> = emptyList(),
    resourceLinks: List<ChatPromptResourceLink> = emptyList(),
): List<ContentBlock> =
    buildList {
        if (text.isNotEmpty()) add(ContentBlock.Text(text))
        images.forEach { add(ContentBlock.Image(data = it.base64, mimeType = it.mimeType)) }
        audios.forEach { add(ContentBlock.Audio(data = it.base64, mimeType = it.mimeType)) }
        files.forEach { file ->
            add(
                if (isTextMimeType(file.mimeType) || isTextFileName(file.name)) {
                    ContentBlock.Resource(
                        EmbeddedResourceResource.TextResourceContents(
                            text = file.textContent(),
                            uri = fileUri(file.name),
                            mimeType = file.mimeType,
                        ),
                    )
                } else {
                    ContentBlock.Resource(
                        EmbeddedResourceResource.BlobResourceContents(
                            blob = file.base64,
                            uri = fileUri(file.name),
                            mimeType = file.mimeType,
                        ),
                    )
                },
            )
        }
        resourceLinks.forEach {
            add(
                ContentBlock.ResourceLink(
                    name = it.name,
                    uri = it.uri,
                    mimeType = it.mimeType,
                    size = it.size,
                    description = it.description,
                ),
            )
        }
    }

fun ContentBlock.chatImage(): ChatImageData? =
    (this as? ContentBlock.Image)?.let { ChatImageData(it.data, it.mimeType.ifBlank { "image/*" }) }

fun ContentBlock.chatAudio(): ChatAudioData? =
    (this as? ContentBlock.Audio)?.let {
        val mimeType = it.mimeType.ifBlank { "audio/*" }
        ChatAudioData(
            name = "audio.${mimeType.substringAfterLast('/', "bin")}",
            base64 = it.data,
            mimeType = mimeType,
            sizeBytes = decodedSize(it.data),
        )
    }

fun ContentBlock.chatFile(): ChatFileData? {
    val resource = (this as? ContentBlock.Resource)?.resource ?: return null
    return when (resource) {
        is EmbeddedResourceResource.BlobResourceContents ->
            ChatFileData(
                name = resource.uri.substringAfterLast('/').ifBlank { "file" },
                base64 = resource.blob,
                mimeType = resource.mimeType?.takeIf(String::isNotBlank) ?: "application/octet-stream",
                sizeBytes = decodedSize(resource.blob),
            )

        is EmbeddedResourceResource.TextResourceContents -> {
            val bytes = resource.text.toByteArray(StandardCharsets.UTF_8)
            ChatFileData(
                name = resource.uri.substringAfterLast('/').ifBlank { "file" },
                base64 = Base64.getEncoder().encodeToString(bytes),
                mimeType = resource.mimeType?.takeIf(String::isNotBlank) ?: "text/plain",
                sizeBytes = bytes.size.toLong(),
            )
        }
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

/** A resource link as the user sent it, without assuming it points at a usable host file. */
fun ContentBlock.chatPromptResourceLink(): ChatPromptResourceLink? =
    (this as? ContentBlock.ResourceLink)?.let {
        ChatPromptResourceLink(
            name = it.name,
            uri = it.uri,
            mimeType = it.mimeType,
            description = it.description,
            size = it.size,
        )
    }

/** Text MIME types whose bytes are sent as ACP embedded text rather than a base64 blob. */
fun isTextMimeType(mimeType: String?): Boolean {
    val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return type.startsWith("text/") ||
        type == "application/json" ||
        type == "application/xml" ||
        type == "application/javascript" ||
        type == "application/x-javascript" ||
        type == "application/x-httpd-php" ||
        type == "application/x-sh" ||
        type.endsWith("+json") ||
        type.endsWith("+xml") ||
        type == "application/yaml" ||
        type == "application/x-yaml"
}

/** Common text/source extensions used when a provider labels a text file `application/octet-stream`. */
fun isTextFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in TEXT_FILE_EXTENSIONS
}

private fun ChatFileData.textContent(): String =
    runCatching { String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8) }
        .getOrDefault("")

private fun fileUri(name: String): String = "file:///$name"

private fun decodedSize(base64: String): Long =
    (
        base64.length.toLong() * BASE64_BYTES_PER_GROUP / BASE64_CHARS_PER_GROUP -
            base64.takeLast(2).count { char -> char == '=' }
    ).coerceAtLeast(0)

private val windowsDrive = Regex("^/[A-Za-z]:/")
private val windowsPath = Regex("^[A-Za-z]:/")

private val TEXT_FILE_EXTENSIONS =
    setOf(
        "txt",
        "md",
        "markdown",
        "json",
        "jsonl",
        "xml",
        "yaml",
        "yml",
        "csv",
        "tsv",
        "log",
        "kt",
        "kts",
        "java",
        "py",
        "rb",
        "go",
        "rs",
        "c",
        "h",
        "cpp",
        "hpp",
        "cs",
        "swift",
        "js",
        "jsx",
        "ts",
        "tsx",
        "html",
        "css",
        "scss",
        "sql",
        "sh",
        "bash",
        "zsh",
        "fish",
        "toml",
        "ini",
        "cfg",
        "conf",
        "properties",
        "gradle",
        "kts",
        "gitignore",
        "env",
    )

private const val BASE64_BYTES_PER_GROUP = 3
private const val BASE64_CHARS_PER_GROUP = 4
