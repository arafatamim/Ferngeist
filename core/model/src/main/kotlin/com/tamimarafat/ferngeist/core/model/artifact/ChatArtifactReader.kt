package com.tamimarafat.ferngeist.core.model.artifact

import java.io.OutputStream

/**
 * What a complete read verified: the host's MIME type, the exact number of bytes written, and the
 * host's validator for those bytes, if it sent one.
 */
data class ChatArtifactMetadata(
    val mimeType: String,
    val size: Long,
    val etag: String?,
)

/**
 * What the host reports about a file without reading it: its [size], or, when it cannot be read,
 * the gateway's [error] code (`not_found`, `forbidden`, `too_large`, ...).
 */
data class ChatArtifactStat(
    val size: Long?,
    val error: String?,
)

/**
 * Reads host files a chat references, bound to the gateway and cwd it was captured with, so a
 * reconnect or session switch cannot retarget a relative path or a read in flight.
 */
interface ChatArtifactReader {
    /** The bound gateway and cwd, for keying cached bytes; never carries a credential. */
    val identity: String

    /**
     * Streams all of [path] (absolute, or relative to the bound cwd) into [destination], or writes
     * nothing and returns null when the host's file still matches [ifNoneMatch], the etag of an
     * earlier read. Throws on any failure, a body shorter than promised included, so a partial copy
     * is never a result.
     */
    suspend fun write(
        path: String,
        destination: OutputStream,
        ifNoneMatch: String?,
    ): ChatArtifactMetadata?

    /** Describes [paths] (each as [write] takes it) in one round trip, one entry per path in order. */
    suspend fun stat(paths: List<String>): List<ChatArtifactStat>
}
