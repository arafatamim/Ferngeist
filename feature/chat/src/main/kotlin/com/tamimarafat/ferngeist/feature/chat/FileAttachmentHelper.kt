package com.tamimarafat.ferngeist.feature.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.tamimarafat.ferngeist.core.model.ChatFileData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads arbitrary (non-image) file attachments from a content URI into
 * [ChatFileData], enforcing a per-file size cap. Unlike [ImageAttachmentHelper],
 * the bytes are sent verbatim (Base64 NO_WRAP) with their original MIME type —
 * no decoding or downscaling.
 */
private const val BYTES_PER_KB = 1024L

object FileAttachmentHelper {
    /** Maximum number of files a user can attach per message. */
    const val MAX_FILES = 5

    /** Maximum raw file size (bytes) before Base64 encoding. */
    const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024

    /** Outcome of reading a single file URI. */
    sealed interface Result {
        data class Success(
            val file: ChatFileData,
        ) : Result

        data class TooLarge(
            val name: String,
            val sizeBytes: Long,
        ) : Result

        data object Unreadable : Result
    }

    /**
     * Reads [uri] from [contentResolver], enforcing [MAX_FILE_SIZE_BYTES], and
     * wraps the Base64-encoded bytes in [ChatFileData]. Runs on [Dispatchers.IO].
     */
    suspend fun uriToChatFileData(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result =
        withContext(Dispatchers.IO) {
            val (name, declaredSize) = queryNameAndSize(contentResolver, uri)
            if (declaredSize != null && declaredSize > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.TooLarge(name, declaredSize)
            }
            val bytes =
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.Unreadable
            if (bytes.size > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.TooLarge(name, bytes.size.toLong())
            }
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Result.Success(
                ChatFileData(
                    name = name,
                    base64 = base64,
                    mimeType = mimeType,
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        }

    /** Queries [OpenableColumns] for a display name (fallback "file") and size (nullable). */
    private fun queryNameAndSize(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Pair<String, Long?> {
        var name = "file"
        var size: Long? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    name = cursor.getString(nameIdx)
                }
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    fun formatSize(bytes: Long): String {
        if (bytes < BYTES_PER_KB) return "$bytes B"
        val kb = bytes / BYTES_PER_KB.toDouble()
        if (kb < BYTES_PER_KB) return "%.0f KB".format(kb)
        val mb = kb / BYTES_PER_KB.toDouble()
        return "%.1f MB".format(mb)
    }
}
