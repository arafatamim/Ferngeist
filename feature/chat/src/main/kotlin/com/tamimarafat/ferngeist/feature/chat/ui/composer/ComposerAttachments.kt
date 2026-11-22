@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.composer

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.git.ui.fileIconFor
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.R

/** A pending image filling its carousel item, or a placeholder when it fails to decode. */
@Composable
internal fun PendingImage(image: ChatImageData) {
    val bitmap =
        remember(image.base64) {
            runCatching {
                val bytes = Base64.decode(image.base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.chat_image_desc),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A pending non-image attachment filling its carousel item: its type badge in a soft cookie
 * (matching the transcript's attachment cards) over its name and detail.
 */
@Composable
internal fun PendingFileTile(
    name: String,
    detail: String,
    badge: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
                badge()
            }
        }
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The workspace's per-extension file icon, the same one the transcript's attachment cards show. */
@Composable
internal fun FileTypeIcon(name: String) {
    Image(painterResource(fileIconFor(name)), contentDescription = null, modifier = Modifier.size(28.dp))
}

/**
 * The remove (x) overlay for a carousel item. Inset inside the corner, on an inverse scrim so it
 * reads over any image.
 */
@Composable
internal fun BoxScope.AttachmentRemoveButton(
    contentDescription: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp),
    ) {
        IconButton(onClick = onRemove, modifier = Modifier.fillMaxSize().handCursor()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
