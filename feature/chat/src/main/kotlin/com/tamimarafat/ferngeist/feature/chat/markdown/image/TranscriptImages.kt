package com.tamimarafat.ferngeist.feature.chat.markdown.image

import androidx.compose.runtime.compositionLocalOf
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.tamimarafat.ferngeist.core.model.ChatWorkspaceReader
import com.tamimarafat.ferngeist.feature.chat.relativeTo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.buffer
import okio.source
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** Most encoded bytes one transcript image may take; a larger one shows its alt text instead. */
internal const val MAX_IMAGE_BYTES = 8 shl 20

private const val REMOTE_TIMEOUT_MS = 15_000

/** Base64 text long enough to decode past [MAX_IMAGE_BYTES]: four characters per three bytes. */
private const val MAX_BASE64_CHARS = (MAX_IMAGE_BYTES + 2) / 3 * 4

/** A Markdown image's destination, as Coil's model for [TranscriptImageLoader]. */
internal data class TranscriptImage(
    val destination: String,
)

/** Where a Markdown image destination's bytes come from. */
internal sealed interface ImageOrigin {
    data class Remote(
        val url: String,
    ) : ImageOrigin

    /** A `data:` URL's part after the scheme: `[<mime>][;base64],<payload>`. */
    data class Data(
        val body: String,
    ) : ImageOrigin

    /** A file of the chat's workspace, relative to its project directory. */
    data class Workspace(
        val path: String,
    ) : ImageOrigin

    data object Unsupported : ImageOrigin
}

/**
 * [destination] as an [ImageOrigin]: web and data URLs as they are, anything else a workspace
 * path. Absolute and `file:` paths must sit inside [cwd]; the gateway still guards the root
 * against `..` and symlinks.
 */
internal fun imageOrigin(
    destination: String,
    cwd: String,
): ImageOrigin {
    val scheme = destination.substringBefore(':', "").lowercase()
    return when {
        scheme == "http" || scheme == "https" -> ImageOrigin.Remote(destination)
        scheme == "data" -> ImageOrigin.Data(destination.substringAfter(':'))
        scheme == "file" -> workspaceOrigin(runCatching { URI(destination).path }.getOrNull(), cwd)
        // One letter is a Windows drive, not a scheme.
        scheme.length > 1 -> ImageOrigin.Unsupported
        else -> workspaceOrigin(destination, cwd)
    }
}

private fun workspaceOrigin(
    path: String?,
    cwd: String,
): ImageOrigin =
    path
        ?.relativeTo(cwd)
        ?.takeIf { it.isNotBlank() }
        ?.let(ImageOrigin::Workspace) ?: ImageOrigin.Unsupported

/**
 * Reads one chat's transcript images, as the Coil fetcher for [TranscriptImage]. One per chat:
 * workspace reads go through that chat's session, and [cacheKey] keeps its images apart from
 * another chat's same path. Every failure throws, which Coil turns into the alt-text fallback.
 */
internal class TranscriptImageLoader(
    private val workspace: ChatWorkspaceReader,
    private val cwd: String,
    private val fetchRemote: suspend (String) -> ByteArray = { fetchRemoteImage(it) },
) : Fetcher.Factory<TranscriptImage> {
    private val id = UUID.randomUUID().toString()

    fun cacheKey(destination: String): String = "transcript-image:$id:$destination"

    suspend fun load(destination: String): ByteArray =
        when (val origin = imageOrigin(destination, cwd)) {
            is ImageOrigin.Remote -> fetchRemote(origin.url)
            is ImageOrigin.Data -> decodeDataUrl(origin.body)
            is ImageOrigin.Workspace -> readWorkspace(origin.path)
            ImageOrigin.Unsupported -> throw IllegalArgumentException("Unsupported image source $destination")
        }

    private suspend fun readWorkspace(path: String): ByteArray {
        val file = checkNotNull(workspace.fetchFile(path)) { "No workspace to read $path from" }
        // Partial bytes would decode to a cut-off image, or not at all.
        check(!file.truncated) { "$path is over the image size limit" }
        val blob = file.blob
        return if (blob !=
            null
        ) {
            decodeBase64(blob)
        } else {
            checkNotNull(file.text) { "$path is empty" }.encodeToByteArray()
        }
    }

    override fun create(
        data: TranscriptImage,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher =
        Fetcher {
            val bytes = load(data.destination)
            SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
}

internal fun decodeDataUrl(body: String): ByteArray {
    require(',' in body) { "Malformed data URL" }
    val payload = body.substringAfter(',')
    if (body.substringBefore(',').endsWith(";base64")) return decodeBase64(payload)
    // '+' is a literal in a data URL, not URLDecoder's form-encoded space.
    val bytes = URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").encodeToByteArray()
    require(bytes.size <= MAX_IMAGE_BYTES) { "Image is over the size limit" }
    return bytes
}

/** Checked against the limit before decoding, so an oversized payload is never allocated. */
private fun decodeBase64(encoded: String): ByteArray {
    require(encoded.length <= MAX_BASE64_CHARS) { "Image is over the size limit" }
    val bytes = Base64.getMimeDecoder().decode(encoded)
    require(bytes.size <= MAX_IMAGE_BYTES) { "Image is over the size limit" }
    return bytes
}

/** No gateway credentials: an external host gets a plain request, under the app's cleartext policy. */
private suspend fun fetchRemoteImage(
    url: String,
    io: CoroutineDispatcher = Dispatchers.IO,
): ByteArray =
    runInterruptible(io) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = REMOTE_TIMEOUT_MS
        connection.readTimeout = REMOTE_TIMEOUT_MS
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} for $url" }
            check(connection.contentLengthLong <= MAX_IMAGE_BYTES) { "$url is over the image size limit" }
            connection.inputStream.source().buffer().use { body ->
                check(!body.request(MAX_IMAGE_BYTES + 1L)) { "$url is over the image size limit" }
                body.readByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

/** The chat's image loader; null outside a chat, where images show their alt text as before. */
internal val localTranscriptImages = compositionLocalOf<TranscriptImageLoader?> { null }
