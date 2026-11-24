package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
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
import com.tamimarafat.ferngeist.core.common.ui.loading.ContainedLoadingIndicator
import com.tamimarafat.ferngeist.core.common.ui.motion.rememberPressShape
import com.tamimarafat.ferngeist.core.git.ui.fileIconFor
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.artifact.reference.extensionOf

// Opaque enough that the name stays legible over any image.
private const val SCRIM_ALPHA = 0.88f

// A design call: big enough for an icon and two lines of name, small enough for a row of three.
internal val attachmentCardSize = 128.dp

// An image card is enlarged to twice the width, its shape the image's own within these bounds.
internal val attachmentPreviewWidth = attachmentCardSize * 2
internal const val MIN_PREVIEW_ASPECT = 0.75f
internal const val MAX_PREVIEW_ASPECT = 2f

/**
 * Set by a bubble that owns its background: its attachment cards then sit on the theme's paper
 * surface instead of the neutral raised container, which is a sibling of the bubble's own tone and
 * reads as the same colour. A tinted card would either vanish into the container or fight it; paper
 * keeps the card a card in every delivery state, including the error container of a FAILED prompt.
 */
internal val localAttachmentSurface = staticCompositionLocalOf<Color?> { null }

/**
 * An attachment's card surface for the blocks that draw their own container: the bubble's paper
 * when a bubble owns it, else the neutral pair an unbubbled block has always used.
 */
@Composable
internal fun AttachmentSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bubble = localAttachmentSurface.current
    Surface(
        shape = shape,
        color = bubble ?: MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (bubble != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = modifier,
        content = content,
    )
}

/**
 * A card's footprint: its image's aspect, or a square when it has no image. A bubble's content is
 * the row, so its photo fills that row; in the transcript column a photo is capped to the artifact
 * card's preview width, where the text beside it would otherwise be squeezed to nothing.
 */
@Composable
internal fun attachmentCardModifier(aspect: Float?): Modifier {
    if (aspect == null) return Modifier.widthIn(max = attachmentCardSize).fillMaxWidth().aspectRatio(1f)
    val clamped = aspect.coerceIn(MIN_PREVIEW_ASPECT, MAX_PREVIEW_ASPECT)
    return if (localAttachmentSurface.current != null) {
        Modifier.fillMaxWidth().aspectRatio(clamped)
    } else {
        Modifier.widthIn(max = attachmentPreviewWidth).fillMaxWidth().aspectRatio(clamped)
    }
}

/**
 * An attachment card for the file at [path]: its type badge, then its name with the extension set
 * apart, so a long name never hides what kind of file it is, and its [size] when known. Tap opens
 * it; long press, or the accessibility action, offers Download when [onDownload] is set. [label]
 * ellipsizes on screen but stays whole for accessibility. A [preview] replaces the badge face with
 * the image itself, and [badge] replaces the type icon inside its cookie (a clip's play control).
 * Inside a bubble the card sits on paper, where its own raised neutral would melt into the bubble.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AttachmentCard(
    path: String,
    label: String,
    size: String?,
    loading: Boolean,
    failed: Boolean,
    onOpen: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    failureMessage: String? = null,
    preview: ImageBitmap? = null,
    badge: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val download = stringResource(R.string.artifact_download)
    val unavailable = failureMessage ?: stringResource(R.string.artifact_unavailable_retry)
    val colours = resolvedAttachmentColours(failed)
    val container by animateColorAsState(colours.first, MaterialTheme.motionScheme.defaultEffectsSpec())
    AttachmentCardBody(
        modifier = modifier,
        baseShape = MaterialTheme.shapes.large,
        container = container,
        content = colours.second,
        path = path,
        label = label,
        size = size,
        problem = if (failed) unavailable else null,
        loading = loading,
        preview = preview,
        downloadLabel = download,
        onOpen = onOpen,
        onClickLabel = onClickLabel,
        onDownload = onDownload,
        badge = badge,
    )
}

/** The card's surface, its gestures and its download menu; [problem] replaces its state line. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AttachmentCardBody(
    modifier: Modifier,
    baseShape: CornerBasedShape,
    container: Color,
    content: Color,
    path: String,
    label: String,
    size: String?,
    problem: String?,
    loading: Boolean,
    preview: ImageBitmap?,
    downloadLabel: String,
    onOpen: (() -> Unit)?,
    onClickLabel: String?,
    onDownload: (() -> Unit)?,
    badge: (@Composable () -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    // The card sits in the message's selection container, where its text would be selectable
    // and the menu popup's text, laid out in another window, crashes the selection sort.
    val interactionSource = remember { MutableInteractionSource() }
    // M3 press feedback: the corners spring in while held, as on the chat cards.
    val shape = rememberPressShape(baseShape, interactionSource)
    DisableSelection {
        Box(modifier) {
            Surface(
                shape = shape,
                color = container,
                contentColor = content,
                modifier =
                    Modifier.fillMaxSize().cardGestures(
                        shape = shape,
                        interactionSource = interactionSource,
                        label = label,
                        state = problem ?: size,
                        download = downloadLabel.takeIf { onDownload != null },
                        onOpen = onOpen,
                        onClickLabel = onClickLabel,
                        onLongPress = if (onDownload != null) ({ menu = true }) else null,
                        onDownload = onDownload,
                    ),
            ) {
                CardContent(path, label, size, loading, problem, preview, badge)
            }
            if (onDownload != null) {
                DownloadMenu(
                    expanded = menu,
                    label = downloadLabel,
                    onDismiss = { menu = false },
                    onDownload = {
                        menu = false
                        onDownload()
                    },
                )
            }
        }
    }
}

/**
 * The card's container and content for this [failed] state. On a bubble the card is paper, and the
 * failure shows in its own badge and state line rather than in a second container: one bubble keeps
 * one container, or a broken attachment punches a hole in it.
 */
@Composable
private fun resolvedAttachmentColours(failed: Boolean): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    val bubble = localAttachmentSurface.current
    return when {
        failed && bubble == null -> Pair(colors.errorContainer, colors.onErrorContainer)
        bubble != null -> Pair(bubble, colors.onSurface)
        else -> Pair(colors.surfaceContainerHigh, colors.onSurface)
    }
}

/** The long-press menu offering the download [label]; only composed when there is a download to offer. */
@Composable
private fun DownloadMenu(
    expanded: Boolean,
    label: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            DropdownMenuItem(
                text = { Text(label) },
                shape = MenuDefaults.itemShape(0, 1).shape,
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                modifier = Modifier.handCursor(),
                onClick = onDownload,
            )
        }
    }
}

/**
 * Tap, long press and the shared press morph, clipped to [shape], with one accessible node
 * named [label], described by [state] when set, offering [download] as an action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.cardGestures(
    shape: Shape,
    interactionSource: MutableInteractionSource,
    label: String,
    state: String?,
    download: String?,
    onOpen: (() -> Unit)?,
    onClickLabel: String?,
    onLongPress: (() -> Unit)?,
    onDownload: (() -> Unit)?,
): Modifier =
    clip(shape)
        .combinedClickable(
            enabled = onOpen != null,
            interactionSource = interactionSource,
            indication = ripple(),
            onClickLabel = onClickLabel,
            onClick = onOpen ?: {},
            onLongClick = onLongPress,
            onLongClickLabel = download,
        ).semantics(mergeDescendants = true) {
            contentDescription = label
            state?.let { stateDescription = it }
            if (download != null && onDownload != null) {
                customActions =
                    listOf(
                        CustomAccessibilityAction(download) {
                            onDownload()
                            true
                        },
                    )
            }
        }

/** The image face while a [preview] is in hand and the file is readable, otherwise the badge face. */
@Composable
private fun CardContent(
    path: String,
    label: String,
    size: String?,
    loading: Boolean,
    problem: String?,
    preview: ImageBitmap?,
    badge: (@Composable () -> Unit)?,
) {
    if (preview != null && problem == null) {
        PreviewFace(preview, label, size, loading)
    } else {
        CardFace(path, label, size, loading, problem, badge)
    }
}

/** The badge above the file's [CardLabels]. */
@Composable
private fun CardFace(
    path: String,
    label: String,
    size: String?,
    loading: Boolean,
    problem: String?,
    badge: (@Composable () -> Unit)?,
) {
    Column(modifier = Modifier.padding(12.dp)) {
        FileBadge(path, loading, failed = problem != null, badge = badge)
        Spacer(modifier = Modifier.weight(1f))
        CardLabels(label, size, problem)
    }
}

/** [preview] filling the card under a scrim with the name and [size]; a fetch for a tap shows the loading shape on top. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewFace(
    preview: ImageBitmap,
    label: String,
    size: String?,
    loading: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val scrim = localAttachmentSurface.current ?: colors.surfaceContainerHigh
    Box {
        Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(scrim.copy(alpha = SCRIM_ALPHA))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CardLabels(label, size, problem = null)
        }
        if (loading) {
            ContainedLoadingIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                containerColor = colors.secondaryContainer,
                indicatorColor = colors.onSecondaryContainer,
            )
        }
    }
}

/** The name above its extension and [size], or above [problem] when the file could not be read. */
@Composable
private fun CardLabels(
    label: String,
    size: String?,
    problem: String?,
) {
    val extension = extensionOf(label)
    // Inside a bubble the card is paper, so a problem needs the theme's error tone to read as one.
    val secondary =
        when {
            problem == null -> MaterialTheme.colorScheme.onSurfaceVariant
            localAttachmentSurface.current != null -> MaterialTheme.colorScheme.error
            else -> LocalContentColor.current
        }
    Text(
        (if (extension.isEmpty()) label else label.dropLast(extension.length + 1)).breakableName(),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        problem ?: listOf(extension.uppercase(), size.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = secondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [this] with a break opportunity after each separator, so a long file name wraps between its words. */
private fun String.breakableName(): String = replace(separatorRun) { it.value + "\u200B" }

private val separatorRun = Regex("[-_.]+")

/**
 * The file's type icon in a soft cookie, or [badge] in its place (a clip's play control); while it
 * fetches, the badge becomes the morphing loading shape, and a failure takes over both.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileBadge(
    path: String,
    loading: Boolean,
    failed: Boolean,
    badge: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val cookie = colors.secondaryContainer
    Crossfade(targetState = loading, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) { fetching ->
        if (fetching) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(44.dp),
                containerColor = cookie,
                indicatorColor = colors.onSecondaryContainer,
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(MaterialShapes.Cookie4Sided.toShape())
                        .background(if (failed) colors.error else cookie),
            ) {
                when {
                    failed -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.onError)
                    badge != null ->
                        CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) { badge() }
                    else ->
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
