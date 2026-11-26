@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupMenuState
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.feature.chat.R

/** Collapses the composer: a separate pill above the text field. */
@Composable
internal fun CloseComposerButton(
    onClose: () -> Unit,
    fullscreen: Boolean,
    floating: Boolean,
    modifier: Modifier = Modifier,
) {
    // Over scrolled content a shade past the text card beneath it, so it reads as the higher layer.
    val containerColor by animateColorAsState(
        if (floating) MaterialTheme.colorScheme.surfaceDim else MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "CloseComposerButtonColor",
    )
    FilledTonalIconButton(
        onClick = onClose,
        shapes = IconButtonDefaults.shapes(),
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier =
            modifier
                .size(IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
                .handCursor(),
    ) {
        Icon(
            imageVector = if (fullscreen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
            contentDescription =
                stringResource(
                    if (fullscreen) R.string.chat_composer_exit_fullscreen_desc else R.string.chat_composer_close_desc,
                ),
        )
    }
}

/**
 * The expanded composer's secondary actions, as an M3 connected button group: photos and the camera
 * for an agent that takes images, and files for anything it accepts.
 */
@Composable
internal fun ComposerActionGroup(
    canSendImages: Boolean,
    showFiles: Boolean,
    pickers: AttachmentPickers,
    dropTargetActive: Boolean,
) {
    val photosLabel = stringResource(R.string.chat_attach_photos_desc)
    val cameraLabel = stringResource(R.string.chat_attach_camera_desc)
    val filesLabel = stringResource(R.string.chat_attach_desc)
    val filesIcon = if (dropTargetActive) Icons.Default.MoveToInbox else Icons.Default.AttachFile
    val actions =
        buildList {
            if (canSendImages) {
                add(AttachmentAction(Icons.Outlined.Image, photosLabel, pickers.pickPhotos))
                pickers.takePhoto?.let { add(AttachmentAction(Icons.Outlined.PhotoCamera, cameraLabel, it)) }
            }
            if (showFiles) add(AttachmentAction(filesIcon, filesLabel, pickers.pickFiles))
        }
    val single = IconButtonDefaults.shapes()
    val leading =
        IconButtonDefaults.shapes(
            shape = ButtonGroupDefaults.connectedLeadingButtonShape,
            pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
        )
    val middle =
        IconButtonDefaults.shapes(
            shape = ShapeDefaults.Small,
            pressedShape = ButtonGroupDefaults.connectedMiddleButtonPressShape,
        )
    val trailing =
        IconButtonDefaults.shapes(
            shape = ButtonGroupDefaults.connectedTrailingButtonShape,
            pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
        )
    ButtonGroup(
        overflowIndicator = {
            ButtonGroupDefaults.OverflowIndicator(
                menuState = it,
                modifier = Modifier.widthIn(min = LocalMinimumInteractiveComponentSize.current),
                shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            )
        },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            val shapes =
                when {
                    actions.size == 1 -> single
                    index == 0 -> leading
                    index == actions.lastIndex -> trailing
                    else -> middle
                }
            groupItem(action, shapes)
        }
    }
}

private data class AttachmentAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

private fun ButtonGroupScope.groupItem(
    action: AttachmentAction,
    shapes: IconButtonShapes,
) = customItem(
    buttonGroupContent = {
        val interactionSource = remember { MutableInteractionSource() }
        FilledTonalIconButton(
            onClick = action.onClick,
            shapes = shapes,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .widthIn(min = LocalMinimumInteractiveComponentSize.current)
                    .handCursor()
                    .animateWidth(interactionSource),
        ) {
            Icon(imageVector = action.icon, contentDescription = action.label)
        }
    },
    menuContent = { state: ButtonGroupMenuState ->
        DropdownMenuItem(
            leadingIcon = { Icon(imageVector = action.icon, contentDescription = null) },
            text = { Text(action.label) },
            onClick = {
                action.onClick()
                state.dismiss()
            },
        )
    },
)
