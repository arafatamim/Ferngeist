package com.tamimarafat.ferngeist.feature.chat.markdown.image

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.tamimarafat.ferngeist.feature.chat.R

private val maxImageHeight = 320.dp

/**
 * A transcript image at its own size, scaled down to the transcript's width and [maxImageHeight].
 * Shows [alt] in [fallbackStyle] once the load fails; the failed request is kept, so a
 * recomposition does not retry it.
 */
@Composable
internal fun MarkdownImage(
    destination: String,
    alt: String,
    loader: TranscriptImageLoader,
    fallbackStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val request =
        remember(context, loader, destination) {
            ImageRequest
                .Builder(context)
                .data(TranscriptImage(destination))
                .fetcherFactory(loader)
                .memoryCacheKey(loader.cacheKey(destination))
                // The loader reads its own bytes; nothing of a workspace file lands on disk.
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    var failed by remember(request) { mutableStateOf(false) }
    val description = alt.ifBlank { stringResource(R.string.chat_image_desc) }
    if (failed) {
        BasicText(
            text =
                alt.ifBlank {
                    stringResource(R.string.chat_image_unavailable)
                },
            style = fallbackStyle,
            modifier = modifier,
        )
        return
    }
    AsyncImage(
        model = request,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = modifier.heightIn(max = maxImageHeight),
    )
}
