package com.tamimarafat.ferngeist.feature.chat.ui.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.ui.image.rememberDecodedImageBitmap

@Composable
internal fun FileAttachmentCard(
    name: String,
    sizeBytes: Long?,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier =
            Modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sizeBytes?.let(FileAttachmentHelper::formatSize).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
internal fun ImageAttachmentItem(
    image: ChatImageData,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = rememberDecodedImageBitmap(image.base64)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = stringResource(R.string.chat_image_desc),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(4.dp)
                        .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Fit,
            )
        } else {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = stringResource(R.string.chat_image_desc),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_image_label, image.mimeType),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}
