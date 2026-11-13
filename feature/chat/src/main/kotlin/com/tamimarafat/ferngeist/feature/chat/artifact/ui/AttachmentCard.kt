package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.git.ui.fileIconFor
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.extensionOf

// Custom clickable press feedback, inside Expressive's 0.95–0.98 band.
private const val PRESSED_SCALE = 0.96f

/**
 * A square attachment for the file at [path]: its type badge, then its name with the extension set
 * apart, so a long name never hides what kind of file it is, and its [size] when known. Tap opens
 * it; long press, or the accessibility action, offers Download. [label] ellipsizes on screen but
 * stays whole for accessibility.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AttachmentCard(
    path: String,
    label: String,
    size: String?,
    loading: Boolean,
    failed: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val download = stringResource(R.string.artifact_download)
    val unavailable = stringResource(R.string.artifact_unavailable_retry)
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (failed) colors.errorContainer else colors.surfaceContainerHigh,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    // The card sits in the message's selection container, where its text would be selectable
    // and the menu popup's text, laid out in another window, crashes the selection sort.
    DisableSelection {
        Box(modifier) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = container,
                contentColor = if (failed) colors.onErrorContainer else colors.onSurface,
                modifier =
                    Modifier.fillMaxSize().cardGestures(
                        label = label,
                        state = if (failed) unavailable else size,
                        download = download,
                        onOpen = onOpen,
                        onLongPress = { menu = true },
                        onDownload = onDownload,
                    ),
            ) {
                CardFace(path, label, size, loading, if (failed) unavailable else null)
            }
            DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DropdownMenuItem(
                        text = { Text(download) },
                        shape = MenuDefaults.itemShape(0, 1).shape,
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        modifier = Modifier.handCursor(),
                        onClick = {
                            menu = false
                            onDownload()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Tap, long press and a spring-scaled press, clipped to the card's shape, with one accessible node
 * named [label], described by [state] when set, offering [download] as an action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.cardGestures(
    label: String,
    state: String?,
    download: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDownload: () -> Unit,
): Modifier {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) PRESSED_SCALE else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clip(MaterialTheme.shapes.large)
        .combinedClickable(
            interactionSource = presses,
            indication = ripple(),
            onClick = onOpen,
            onLongClick = onLongPress,
            onLongClickLabel = download,
        ).semantics(mergeDescendants = true) {
            contentDescription = label
            state?.let { stateDescription = it }
            customActions =
                listOf(
                    CustomAccessibilityAction(download) {
                        onDownload()
                        true
                    },
                )
        }
}

/**
 * The badge, then the name above its extension and [size], or above [problem] when the file could
 * not be read.
 */
@Composable
private fun CardFace(
    path: String,
    label: String,
    size: String?,
    loading: Boolean,
    problem: String?,
) {
    val extension = extensionOf(label)
    Column(modifier = Modifier.padding(12.dp)) {
        FileBadge(path, loading, failed = problem != null)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            (if (extension.isEmpty()) label else label.dropLast(extension.length + 1)).breakableName(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            problem ?: listOf(extension.uppercase(), size.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (problem != null) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [this] with a break opportunity after each separator, so a long file name wraps between its words. */
private fun String.breakableName(): String = replace(separatorRun) { it.value + "\u200B" }

private val separatorRun = Regex("[-_.]+")

/** The file's type icon in a soft cookie; while it fetches, the badge becomes the morphing loading shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileBadge(
    path: String,
    loading: Boolean,
    failed: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Crossfade(targetState = loading, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) { fetching ->
        if (fetching) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(44.dp),
                containerColor = colors.secondaryContainer,
                indicatorColor = colors.onSecondaryContainer,
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(MaterialShapes.Cookie4Sided.toShape())
                        .background(if (failed) colors.error else colors.secondaryContainer),
            ) {
                if (failed) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.onError)
                } else {
                    Image(
                        painterResource(fileIconFor(path)),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
