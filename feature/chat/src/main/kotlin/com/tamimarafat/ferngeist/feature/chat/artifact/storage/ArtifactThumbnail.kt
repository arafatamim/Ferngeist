package com.tamimarafat.ferngeist.feature.chat.artifact.storage

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * [artifact] decoded no larger than [maxPx] on its longer side, so a card never holds a
 * full-resolution bitmap. Throws when the platform cannot decode it (SVG, or AVIF below API 31).
 */
internal fun decodeThumbnail(
    artifact: CachedArtifact,
    maxPx: Int,
): ImageBitmap =
    ImageDecoder
        .decodeBitmap(ImageDecoder.createSource(artifact.file)) { decoder, info, _ ->
            val scale = maxPx.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }.asImageBitmap()
