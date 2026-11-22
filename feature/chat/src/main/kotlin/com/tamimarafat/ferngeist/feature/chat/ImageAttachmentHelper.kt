package com.tamimarafat.ferngeist.feature.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import com.tamimarafat.ferngeist.core.model.ChatImageData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Pure helpers for converting image bytes into [ChatImageData], shared between
 * the photo-picker path in the composer and the unit-test harness.
 */
object ImageAttachmentHelper {
    /** Maximum number of images a user can attach per message. */
    const val MAX_IMAGES = 5

    /** Downscale images so the largest side does not exceed this pixel count. */
    const val MAX_IMAGE_DIMENSION = 1024

    /** JPEG compression quality (1-100) applied after downscale. */
    const val JPEG_QUALITY = 85

    /**
     * Decodes raw bytes, downscales if necessary, re-encodes to JPEG at
     * [JPEG_QUALITY], and wraps the result in [ChatImageData].
     *
     * @param bytes  raw image bytes from any source (ContentResolver, network, etc.).
     * @param mimeType  the MIME type of the original bytes (e.g. "image/png").
     * @return [ChatImageData] with a Base64-NO_WRAP JPEG payload, or `null` if
     *         the input cannot be decoded as a bitmap.
     */
    fun encodeImageBytes(
        bytes: ByteArray,
        _mimeType: String,
    ): ChatImageData? {
        // ImageDecoder, unlike BitmapFactory, applies the EXIF orientation: camera photos are stored
        // sideways with a rotation tag, which the JPEG re-encode below would otherwise drop.
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    // Software: the JPEG re-encode below reads the pixels.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(
                        computeSampleSize(info.size.width, info.size.height, MAX_IMAGE_DIMENSION),
                    )
                }
            } catch (_: IOException) {
                null
            } ?: return null

        val jpegBytes =
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                if (!bitmap.isRecycled) bitmap.recycle()
                stream.toByteArray()
            }

        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return ChatImageData(base64 = base64, mimeType = "image/jpeg")
    }

    // -- ContentResolver helper (kept separate for testability) --

    /**
     * Reads the bytes and MIME type of [uri] from [contentResolver], then
     * delegates to [encodeImageBytes] on [ioDispatcher].
     */
    suspend fun uriToChatImageData(
        contentResolver: ContentResolver,
        uri: Uri,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ChatImageData? =
        withContext(ioDispatcher) {
            val bytes: ByteArray =
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext null
            val mimeType = contentResolver.getType(uri) ?: "image/*"
            encodeImageBytes(bytes, mimeType)
        }

    // -- internal --

    /** Returns the largest power-of-two sample size that keeps the image within [maxDimension]. */
    internal fun computeSampleSize(
        outWidth: Int,
        outHeight: Int,
        maxDimension: Int,
    ): Int {
        var sampleSize = 1
        while (outWidth / sampleSize > maxDimension || outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
