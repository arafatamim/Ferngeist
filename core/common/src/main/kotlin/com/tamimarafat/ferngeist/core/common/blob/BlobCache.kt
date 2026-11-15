package com.tamimarafat.ferngeist.core.common.blob

import com.tamimarafat.ferngeist.core.model.blob.EmbeddedBlobStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** An evictable store for embedded resources, shared by transcript ingestion and attachment actions. */
class BlobCache(
    private val root: File,
) : EmbeddedBlobStore {
    /** Writes a complete blob and returns its opaque key, or null when it exceeds the budget. */
    override fun put(base64: String): String? =
        synchronized(lock) {
            if (base64.length.toLong() > MAX_ENCODED_LENGTH) return@synchronized null
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create blob cache")
            val key = UUID.randomUUID().toString()
            val file = File(root, key)
            var complete = false
            try {
                if (!decode(base64, file)) return@synchronized null
                file.setLastModified(System.currentTimeMillis())
                prune(file)
                complete = true
                key
            } finally {
                if (!complete) file.delete()
            }
        }

    /** Holds a file during [block], preventing eviction until the operation finishes. */
    fun <T> use(
        key: String,
        block: (File) -> T,
    ): T? =
        synchronized(lock) {
            prune()
            if (!keyPattern.matches(key)) return@synchronized null
            val file = File(root, key)
            if (!file.isFile) return@synchronized null
            file.setLastModified(System.currentTimeMillis())
            block(file)
        }

    fun contains(key: String): Boolean =
        synchronized(lock) {
            prune()
            keyPattern.matches(key) && File(root, key).isFile
        }

    /** Removes expired or over-budget entries even when no transcript is opened. */
    override fun clean() = synchronized(lock) { prune() }

    private fun decode(
        base64: String,
        file: File,
    ): Boolean =
        Base64.getDecoder().wrap(AsciiInput(base64)).use { input ->
            file.outputStream().buffered().use { output -> copyBlob(input, output) }
        }

    private fun copyBlob(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return true
            size += count
            if (size > BLOB_CACHE_BUDGET_BYTES) return false
            output.write(buffer, 0, count)
        }
    }

    private fun prune(keep: File? = null) {
        val files =
            root
                .listFiles()
                ?.filter { it.isFile && it != keep }
                .orEmpty()
                .sortedBy(File::lastModified)
        files.filter { it.lastModified() < System.currentTimeMillis() - maxAgeMs }.forEach { file ->
            if (!file.delete() && file.exists()) throw IOException("Cannot remove expired blob")
        }
        var total = files.filter(File::exists).sumOf(File::length) + (keep?.length() ?: 0)
        files.filter(File::exists).forEach { file ->
            if (total > BLOB_CACHE_BUDGET_BYTES) {
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
        if (total > BLOB_CACHE_BUDGET_BYTES) throw IOException("Cannot free space in blob cache")
    }

    private companion object {
        const val BINARY_GROUP_SIZE = 3
        const val BASE64_GROUP_SIZE = 4
        const val MAX_ENCODED_LENGTH =
            ((BLOB_CACHE_BUDGET_BYTES + BINARY_GROUP_SIZE - 1) / BINARY_GROUP_SIZE) * BASE64_GROUP_SIZE
        val maxAgeMs = TimeUnit.DAYS.toMillis(14)
        val lock = Any()
        val keyPattern = Regex("[0-9a-f-]{36}")
    }
}

private class AsciiInput(
    private val text: String,
) : InputStream() {
    private var position = 0

    override fun read(): Int {
        if (position == text.length) return -1
        val char = text[position++].code
        if (char > MAX_ASCII_CODE) throw IOException("Invalid base64")
        return char
    }
}

private const val MAX_ASCII_CODE = 127
const val BLOB_CACHE_BUDGET_BYTES = 100L * 1024 * 1024
const val BLOB_CACHE_DIRECTORY = "reply-blobs"
