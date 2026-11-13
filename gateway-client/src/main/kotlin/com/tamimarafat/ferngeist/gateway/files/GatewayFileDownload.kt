package com.tamimarafat.ferngeist.gateway.files

import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactMetadata
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.etag
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.OutputStream

/**
 * A whole-file read runs up to the gateway's own ten-minute deadline; only an idle socket ends it
 * early. With [ifNoneMatch], an unchanged file answers `304` instead of its bytes.
 */
internal fun HttpRequestBuilder.wholeFile(ifNoneMatch: String?) {
    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
    ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
}

/**
 * Copies a complete `/v1/files` body into [destination], or returns null on `304 Not Modified`.
 * Redirects are rejected by the HTTP pipeline; fewer bytes than `Content-Length` promised fail
 * the read.
 */
internal suspend fun HttpResponse.writeWholeFile(destination: OutputStream): ChatArtifactMetadata? {
    if (status == HttpStatusCode.NotModified) return null
    val expected = checkNotNull(contentLength()) { "File response carries no length" }
    val copied = bodyAsChannel().copyTo(destination)
    check(copied == expected) { "File body ended after $copied of $expected bytes" }
    val type = contentType()?.withoutParameters() ?: ContentType.Application.OctetStream
    return ChatArtifactMetadata(mimeType = type.toString(), size = copied, etag = etag())
}
