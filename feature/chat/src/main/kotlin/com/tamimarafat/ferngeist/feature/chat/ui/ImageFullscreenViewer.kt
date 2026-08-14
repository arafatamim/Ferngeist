package com.tamimarafat.ferngeist.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fullscreen image overlay for attached chat images.
 *
 * Opens as a platform dialog with a dark scrim background. The image is decoded
 * from the [ChatImageData] base64 payload on [Dispatchers.Default] (same sampling
 * strategy as the thumbnail in [ImageAttachmentItem]) and displayed centered with
 * [ContentScale.Fit]. An animated scale+fade entrance and a close affordance in
 * the top-right corner make dismissal discoverable; tapping the scrim or pressing
 * back also dismisses.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageFullscreenViewer(
    image: ChatImageData,
    onDismiss: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.base64) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(image.base64, Base64.DEFAULT)
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
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter =
                fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                    scaleIn(initialScale = 0.94f, animationSpec = spring(stiffness = Spring.StiffnessLow)),
            exit =
                fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
                    scaleOut(targetScale = 0.94f, animationSpec = spring(stiffness = Spring.StiffnessMedium)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.94f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                val currentBitmap = bitmap
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap,
                        contentDescription = stringResource(R.string.chat_image_desc),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(24.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
