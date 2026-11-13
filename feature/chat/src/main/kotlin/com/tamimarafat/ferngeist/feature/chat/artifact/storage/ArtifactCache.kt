package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import android.content.Context
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactReader
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.extensionOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A complete, verified local copy of a host file, private to the app. */
internal data class CachedArtifact(
    val file: File,
    val mimeType: String,
    val size: Long,
)

// Two of the gateway's largest (512 MiB) files.
private const val DEFAULT_BUDGET_BYTES = 1024L * 1024 * 1024
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

/**
 * Whole host files under [root], keyed by the reader's identity and the path, so two gateways or
 * cwds never share bytes, and revalidated by etag on every use. A read lands in a temporary file
 * and is published only once complete, so a failed or cancelled read leaves nothing. Past [budgetBytes] the least recently used files go,
 * never one a [use] block still holds. Entries live in memory only: whatever a previous process
 * left under [root] is deleted on first use.
 */
@Singleton
class ArtifactCache internal constructor(
    private val root: File,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.cacheDir, "artifacts"))

    private class Entry(
        val artifact: CachedArtifact,
        val etag: String?,
        var holds: Int,
    )

    private val mutex = Mutex()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    // Access order, so iteration runs least recently used first.
    private val entries = LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val retired = mutableSetOf<Entry>()
    private var pruned = false

    /**
     * Runs [block] with [path] as the host has it now: the cached copy once the host confirms its
     * etag still matches, otherwise a fresh read. One read at a time per file; a host that cannot
     * be asked fails the use rather than serve a copy that may be stale.
     */
    internal suspend fun <T> use(
        reader: ChatArtifactReader,
        path: String,
        block: suspend (CachedArtifact) -> T,
    ): T {
        val key = keyOf(reader.identity, path)
        val entry = keyLocks.getOrPut(key) { Mutex() }.withLock { current(key, reader, path) }
        try {
            return block(entry.artifact)
        } finally {
            withContext(NonCancellable) { release(entry) }
        }
    }

    private suspend fun current(
        key: String,
        reader: ChatArtifactReader,
        path: String,
    ): Entry {
        pruneOnce()
        // Held while the host is asked, so the budget cannot evict the copy a 304 confirms.
        val known = hold(key)
        try {
            return fetch(key, reader, path, known)
        } finally {
            if (known != null) withContext(NonCancellable) { release(known) }
        }
    }

    private suspend fun hold(key: String): Entry? =
        mutex.withLock {
            entries[key]?.takeIf { it.artifact.file.exists() }?.also { it.holds++ }
        }

    private suspend fun fetch(
        key: String,
        reader: ChatArtifactReader,
        path: String,
        known: Entry?,
    ): Entry {
        val written =
            withContext(io) {
                val partial = File.createTempFile(key, ".part", root)
                try {
                    val metadata =
                        partial.outputStream().buffered().use { reader.write(path, it, known?.etag) }
                            ?: return@withContext null
                    // The extension stays so a viewer handed the file can still tell its type by name.
                    val target =
                        File(
                            root,
                            listOf(partial.name.removeSuffix(".part"), extensionOf(path))
                                .filter { it.isNotEmpty() }
                                .joinToString("."),
                        )
                    if (!partial.renameTo(target)) throw IOException("Could not publish $path to the cache")
                    Entry(CachedArtifact(target, metadata.mimeType, metadata.size), metadata.etag, holds = 0)
                } finally {
                    // Gone already once published; otherwise the bytes are partial and must not stay.
                    partial.delete()
                }
            }
        return mutex.withLock {
            if (written == null) {
                // Only a request carrying the known etag can come back unchanged.
                checkNotNull(known).also { it.holds++ }
            } else {
                // A held version keeps its own path and metadata until its last user releases it.
                entries.put(key, written)?.let { retired += it }
                written.also { it.holds++ }
            }
        }
    }

    private suspend fun pruneOnce() =
        mutex.withLock {
            if (pruned) return@withLock
            withContext(io) {
                root.deleteRecursively()
                if (!root.mkdirs()) throw IOException("Could not create the artifact cache")
            }
            pruned = true
        }

    private suspend fun release(entry: Entry) =
        mutex.withLock {
            entry.holds--
            retired.filter { it.holds == 0 }.forEach {
                it.artifact.file.delete()
                retired.remove(it)
            }
            var total = (entries.values + retired).sumOf { it.artifact.size }
            val iterator = entries.values.iterator()
            while (total > budgetBytes && iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.holds == 0) {
                    candidate.artifact.file.delete()
                    total -= candidate.artifact.size
                    iterator.remove()
                }
            }
        }
}

private fun keyOf(
    identity: String,
    path: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$identity\n$path".toByteArray())
        .toHexString()
