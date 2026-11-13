package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import java.io.IOException
import java.io.OutputStream

/**
 * Copies all of [artifact] into the stream [open] gives, the destination the reader picked. A
 * destination that will not open, a failed write, or a short copy throws: never a partial success.
 */
internal fun exportArtifact(
    artifact: CachedArtifact,
    open: () -> OutputStream?,
) {
    val out = open() ?: throw IOException("Could not open the chosen destination")
    val copied = out.use { stream -> artifact.file.inputStream().use { it.copyTo(stream) } }
    if (copied != artifact.size) throw IOException("Saved $copied of ${artifact.size} bytes")
}
