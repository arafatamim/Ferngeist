package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val viewerRetentionMs = TimeUnit.DAYS.toMillis(1)

/**
 * A viewer opens its URI asynchronously, possibly after our cache hold ends. Give it an immutable
 * copy outside the download LRU. Shortcut: retain shared copies for a day; Android may reclaim
 * cache storage sooner, and a permanent copy belongs in the user's chosen document destination.
 */
internal fun shareArtifact(
    artifact: CachedArtifact,
    root: File,
): File {
    if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create the attachment sharing directory")
    val cutoff = System.currentTimeMillis() - viewerRetentionMs
    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val shared = File.createTempFile("view-", ".${artifact.file.extension}", root)
    var complete = false
    try {
        exportArtifact(artifact) { shared.outputStream().buffered() }
        complete = true
        return shared
    } finally {
        if (!complete) shared.delete()
    }
}
