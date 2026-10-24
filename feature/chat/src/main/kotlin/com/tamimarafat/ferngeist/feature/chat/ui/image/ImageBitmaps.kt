package com.tamimarafat.ferngeist.feature.chat.ui.image

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a base64-encoded [ChatImageData] into a sampled [ImageBitmap] on
 * [decodeDispatcher], using the same sampling strategy as the thumbnail in
 * [ImageAttachmentItem]. Returns `null` when the payload cannot be decoded.
 */
@Composable
internal fun rememberDecodedImageBitmap(
    base64: String,
    decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, base64) {
        value =
            withContext(decodeDispatcher) {
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val boundsOpts =
                        BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext null

                    val sampleSize =
                        ImageAttachmentHelper.computeSampleSize(
                            outWidth = boundsOpts.outWidth,
                            outHeight = boundsOpts.outHeight,
                            maxDimension = ImageAttachmentHelper.MAX_IMAGE_DIMENSION,
                        )

                    val bitmap =
                        BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sampleSize },
                        ) ?: return@withContext null

                    bitmap.asImageBitmap()
                }.getOrNull()
            }
    }.value
