package com.tamimarafat.ferngeist.gateway.files

import com.tamimarafat.ferngeist.core.model.ChatFileContents
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import java.util.Base64

/** The source viewer's and transcript images' bound, the 8 MiB the v2 JSON readers cut at. */
internal const val FILE_PREVIEW_BYTES = 8L * 1024 * 1024

// git's own text/binary sniff window, which the gateway also uses for unknown extensions.
private const val SNIFF_BYTES = 8000

/** Asks `/v1/files` for at most [FILE_PREVIEW_BYTES]. */
internal fun HttpRequestBuilder.previewRange() {
    header(HttpHeaders.Range, "bytes=0-${FILE_PREVIEW_BYTES - 1}")
}

/** The gateway answers a range starting at zero with 416 when the file is empty. */
internal fun HttpResponse.isEmptyFileRange(): Boolean =
    status == HttpStatusCode.RequestedRangeNotSatisfiable && headers[HttpHeaders.ContentRange] == "bytes */0"

/**
 * A bounded `/v1/files` response as the source viewer reads it: truncated when `Content-Range`
 * names a larger file. A body shorter than its `Content-Length` is a failed read, never a preview.
 */
internal suspend fun HttpResponse.toPreview(): ChatFileContents {
    if (isEmptyFileRange()) return ChatFileContents(text = "", truncated = false)
    val bytes = bodyAsBytes()
    val expected = contentLength()
    check(expected == null || expected == bytes.size.toLong()) { "File body ended after ${bytes.size} bytes" }
    val total = headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
    val truncated = total != null && total > bytes.size
    return if (isText(contentType(), bytes)) {
        ChatFileContents(text = bytes.decodeToString(), truncated = truncated)
    } else {
        ChatFileContents(text = null, truncated = truncated, blob = Base64.getEncoder().encodeToString(bytes))
    }
}

/**
 * The v2 readers' text/binary split: raster images are binary, everything else is text unless a
 * NUL byte shows up. The extension's MIME alone misleads (`.ts` maps to `video/mp2t`).
 */
private fun isText(
    type: ContentType?,
    bytes: ByteArray,
): Boolean {
    if (type?.contentType == "image" && type.contentSubtype != "svg+xml") return false
    return (0 until minOf(bytes.size, SNIFF_BYTES)).none { bytes[it] == 0.toByte() }
}
