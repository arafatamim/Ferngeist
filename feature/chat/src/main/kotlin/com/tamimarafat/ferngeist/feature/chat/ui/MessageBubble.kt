package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.key
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.R
import kotlin.random.Random
import com.mikepenz.markdown.model.State as MarkdownRenderState
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

@Composable
fun MessageBubble(
    message: ChatMessage,
    markdownStates: ImmutableMap<String, MarkdownRenderState>,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onStreamLayoutSettled: () -> Unit = {},
    onRetryMessage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showStreamingIndicator) Modifier.onSizeChanged { onStreamLayoutSettled() }
                else Modifier,
            ),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (isUser) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = contentColor,
                ),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 8.dp,
                ),
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                UserMessageContent(
                    message = message,
                    textColor = contentColor,
                    onRetry = if (onRetryMessage != null && message.status == MessageDeliveryStatus.FAILED) {
                        { onRetryMessage(message.clientId ?: message.id) }
                    } else null,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            AssistantMessageContent(
                message = message,
                markdownStates = markdownStates,
                showStreamingIndicator = showStreamingIndicator,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageContent(
    message: ChatMessage,
    textColor: Color,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Text content
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }

        // Images
        if (message.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            ImageAttachments(message.images)
        }

        // Status badge for non-SENT delivery states
        if (message.role == ChatMessage.Role.USER &&
            message.status != MessageDeliveryStatus.SENT
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            DeliveryStatusBadge(
                status = message.status,
                onRetry = onRetry,
            )
        }
    }
}


/**
 * Small themed badge indicating the delivery status of a user message.
 *
 * - [MessageDeliveryStatus.QUEUED]: clock/schedule icon tinted with [MaterialTheme.colorScheme.outline].
 * - [MessageDeliveryStatus.SENDING]: an expressive [LoadingIndicator] in [MaterialTheme.colorScheme.primary].
 * - [MessageDeliveryStatus.FAILED]: error icon with [MaterialTheme.colorScheme.errorContainer] background,
 *   tappable to trigger [onRetry].
 * - [MessageDeliveryStatus.SENT]: not rendered (the caller skips this composable entirely).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeliveryStatusBadge(
    status: MessageDeliveryStatus,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(springSpec) + scaleIn(
                initialScale = 0.6f,
                animationSpec = springSpec,
            )) togetherWith (fadeOut(springSpec) + scaleOut(
                targetScale = 0.6f,
                animationSpec = springSpec,
            ))
        },
        label = "DeliveryStatusBadge",
        modifier = modifier,
    ) { currentStatus ->
        when (currentStatus) {
            MessageDeliveryStatus.QUEUED -> {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = stringResource(R.string.chat_status_queued),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            }
            MessageDeliveryStatus.SENDING -> {
                LoadingIndicator(
                    modifier = Modifier.size(14.dp),
                )
            }
            MessageDeliveryStatus.FAILED -> {
                Row(
                    modifier = Modifier
                        .then(
                            if (onRetry != null) {
                                Modifier.clickable { onRetry() }
                            } else Modifier,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = stringResource(R.string.chat_status_failed),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .size(14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_status_retry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            MessageDeliveryStatus.SENT -> { /* never rendered */ }
        }
    }
}
@Composable
private fun AssistantMessageContent(
    message: ChatMessage,
    markdownStates: ImmutableMap<String, MarkdownRenderState>,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Render segments in order
        message.segments.forEach { segment ->
            key(segment.id) {
                when (segment.kind) {
                    AssistantSegment.Kind.MESSAGE -> {
                        if (segment.text.isNotBlank()) {
                            MarkdownText(
                                state = markdownStates[segment.id],
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    AssistantSegment.Kind.THOUGHT -> {
                        ThoughtBubble(
                            isStreaming = message.isStreaming && message.segments.lastOrNull()?.id == segment.id,
                            onClick = { onThoughtClick(segment.id) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    AssistantSegment.Kind.TOOL_CALL -> {
                        segment.toolCall?.let { toolCall ->
                            ToolCallCard(
                                toolCall = toolCall,
                                onClick = { onToolCallClick(segment.id) },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    AssistantSegment.Kind.PLAN -> {
                        PlanBubble(segment.planEntries.orEmpty())
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // Fallback to simple text if no segments
        if (message.segments.isEmpty() && message.content.isNotBlank()) {
            MarkdownText(
                state = markdownStates[message.id],
            )
        }

        // Show a visible placeholder while waiting for the first assistant chunk.
        if (showStreamingIndicator && message.segments.isEmpty() && message.content.isBlank()) {
            StreamingIndicator(
                streamKey = message.id,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MarkdownText(
    state: MarkdownRenderState?,
    modifier: Modifier = Modifier,
) {
    val compactTypography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge,
        h2 = MaterialTheme.typography.titleMedium,
        h3 = MaterialTheme.typography.titleSmall,
        h4 = MaterialTheme.typography.bodyLarge,
        h5 = MaterialTheme.typography.bodyMedium,
        h6 = MaterialTheme.typography.bodySmall,
        text = MaterialTheme.typography.bodyMedium,
        paragraph = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        bullet = MaterialTheme.typography.bodyMedium,
        ordered = MaterialTheme.typography.bodyMedium,
    )
    if (state != null) {
        SelectionContainer {
            Markdown(
                state = state,
                typography = compactTypography,
                animations = markdownAnimations(
                    animateTextSize = { this },
                ),
                dimens = markdownDimens(),
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ThoughtBubble(
    isStreaming: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textBrush = rememberShimmerTextBrush(
        isActive = isStreaming,
        baseColor = baseColor,
        labelPrefix = "reasoning",
    )

    val reasoningDesc = stringResource(R.string.chat_reasoning_desc)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .semantics {
                contentDescription = reasoningDesc
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isStreaming) stringResource(R.string.chat_reasoning) else stringResource(R.string.chat_show_reasoning),
            style = MaterialTheme.typography.bodySmall.copy(
                brush = textBrush,
            ),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.chat_reasoning_desc),
            tint = baseColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PlanBubble(
    entries: List<PlanEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            entries.forEachIndexed { index, entry ->
                val isCompleted = entry.status == PlanEntryStatus.COMPLETED
                val isInProgress = entry.status == PlanEntryStatus.IN_PROGRESS
                val isPending = entry.status == PlanEntryStatus.PENDING

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = if (isCompleted) stringResource(R.string.chat_completed_desc) else stringResource(R.string.chat_plan_desc),
                        tint = if (isInProgress) MaterialTheme.colorScheme.primary
                        else if (isPending) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPending) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                        else if (isInProgress) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                        fontWeight = if (isInProgress) FontWeight.Medium else null,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < entries.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCallCard(
    toolCall: ToolCallDisplay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        ),
        shape = CardDefaults.shape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .semantics {
                        contentDescription = toolCall.title + " " + (toolCall.status?.name?.lowercase() ?: "unknown")
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status badge
                toolCall.status?.let { status ->
                    when (status) {
                        ToolCallStatus.PENDING, ToolCallStatus.IN_PROGRESS -> ContainedLoadingIndicator(
                            polygons = pickLoadingPolygons(toolCall.toolCallId ?: toolCall.title),
                            containerShape = MaterialTheme.shapes.medium,
                            containerColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp),
                        )

                        ToolCallStatus.COMPLETED -> Surface(
                            modifier = Modifier.size(32.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = toolKindIcon(toolCall.kind),
                                    contentDescription = stringResource(R.string.chat_completed_desc),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }

                        ToolCallStatus.FAILED -> Surface(
                            modifier = Modifier.size(32.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.error,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = Icons.Rounded.Error,
                                    contentDescription = stringResource(R.string.chat_error_desc),
                                    tint = MaterialTheme.colorScheme.onError,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                val defaultToolCallTitle = stringResource(R.string.chat_tool_call)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.title.ifBlank { defaultToolCallTitle },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    toolCall.kind?.let { kind ->
                        Text(
                            text = toolKindLabel(kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                DiffSummaryRow(toolCall.content)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(R.string.chat_tool_call_details_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!toolCall.permissionOptions.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.chat_awaiting_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@ExperimentalMaterial3ExpressiveApi
@Composable
private fun ImageAttachments(
    images: List<ChatImageData>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        images.forEach { image ->
            key(image.base64) {
                ImageAttachmentItem(image)
            }
        }
    }
}

@Composable
private fun ImageAttachmentItem(image: ChatImageData) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.base64) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(image.base64, Base64.DEFAULT)
                val boundsOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@withContext null

                val sampleSize = ImageAttachmentHelper.computeSampleSize(
                    outWidth = boundsOpts.outWidth,
                    outHeight = boundsOpts.outHeight,
                    maxDimension = ImageAttachmentHelper.MAX_IMAGE_DIMENSION,
                )

                val bitmap = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                ) ?: return@withContext null

                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = stringResource(R.string.chat_image_desc),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.FillWidth,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamingIndicator(
    streamKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spinnerVerb = remember(streamKey) {
        val verbs = context.resources.getStringArray(R.array.chat_spinner_verbs)
        verbs[Random.nextInt(verbs.size)]
    }
    val polygons = remember(streamKey) { pickLoadingPolygons(streamKey) }
    val baseColor = LocalContentColor.current.copy(alpha = 0.8f)
    val textBrush = rememberShimmerTextBrush(
        isActive = true,
        baseColor = baseColor,
        labelPrefix = "spinnerVerb",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(
            polygons = polygons,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_streaming_indicator, spinnerVerb),
            style = MaterialTheme.typography.bodySmall.copy(
                brush = textBrush,
            ),
        )
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val LOADING_SHAPES = listOf(
    MaterialShapes.Oval,
    MaterialShapes.ClamShell,
    MaterialShapes.Diamond,
    MaterialShapes.VerySunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.SoftBurst,
    MaterialShapes.SoftBoom,
    MaterialShapes.Flower,
    MaterialShapes.PuffyDiamond,
    MaterialShapes.Bun,
)

private fun pickLoadingPolygons(seedKey: String) = LOADING_SHAPES.shuffled(Random(seedKey.hashCode())).take(6)

private fun toolKindIcon(kind: ToolKind?): ImageVector = when (kind) {
    ToolKind.READ -> Icons.Rounded.Search
    ToolKind.EDIT -> Icons.Rounded.Edit
    ToolKind.DELETE -> Icons.Rounded.Delete
    ToolKind.MOVE -> Icons.AutoMirrored.Rounded.ArrowForward
    ToolKind.SEARCH -> Icons.Rounded.Search
    ToolKind.EXECUTE -> Icons.Rounded.PlayArrow
    ToolKind.THINK -> Icons.Rounded.Refresh
    ToolKind.FETCH -> Icons.Rounded.CloudDownload
    ToolKind.SWITCH_MODE -> Icons.Rounded.Settings
    ToolKind.OTHER -> Icons.Rounded.Build
    null -> Icons.AutoMirrored.Rounded.Help
}

@Composable
private fun rememberShimmerTextBrush(
    isActive: Boolean,
    baseColor: Color,
    labelPrefix: String,
): Brush {
    val shimmerTransition = rememberInfiniteTransition(label = "${labelPrefix}Shimmer")
    val shimmerOffset = if (isActive) {
        shimmerTransition.animateFloat(
            initialValue = -200f,
            targetValue = 600f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "${labelPrefix}ShimmerOffset",
        ).value
    } else {
        0f
    }

    return if (isActive) {
        Brush.linearGradient(
            colors = listOf(
                baseColor.copy(alpha = 0.45f),
                baseColor.copy(alpha = 0.95f),
                baseColor.copy(alpha = 0.45f),
            ),
            start = Offset(shimmerOffset - 200f, 0f),
            end = Offset(shimmerOffset, 0f),
        )
    } else {
        SolidColor(baseColor)
    }
}

@Preview
@Composable
private fun PlanBubblePreview() {
    Surface {
        PlanBubble(
            entries = listOf(
                PlanEntry(
                    content = "Analyze the existing codebase structure",
                    priority = PlanEntryPriority.HIGH,
                    status = PlanEntryStatus.COMPLETED,
                ),
                PlanEntry(
                    content = "Identify components that need refactoring",
                    priority = PlanEntryPriority.HIGH,
                    status = PlanEntryStatus.IN_PROGRESS,
                ),
                PlanEntry(
                    content = "Create unit tests for critical functions",
                    priority = PlanEntryPriority.MEDIUM,
                    status = PlanEntryStatus.PENDING,
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ToolCallCardPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "list_files (READ · IN_PROGRESS)",
                        kind = ToolKind.READ,
                        status = ToolCallStatus.IN_PROGRESS,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "search_code (READ · COMPLETED)",
                        kind = ToolKind.READ,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "search (SEARCH · COMPLETED)",
                        kind = ToolKind.SEARCH,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "edit_file (EDIT · COMPLETED)",
                        kind = ToolKind.EDIT,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "delete_file (DELETE · FAILED)",
                        kind = ToolKind.DELETE,
                        status = ToolCallStatus.FAILED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "move_file (MOVE · COMPLETED)",
                        kind = ToolKind.MOVE,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "run_tests (EXECUTE · COMPLETED)",
                        kind = ToolKind.EXECUTE,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "think (THINK · COMPLETED)",
                        kind = ToolKind.THINK,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "fetch_data (FETCH · FAILED)",
                        kind = ToolKind.FETCH,
                        status = ToolCallStatus.FAILED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "switch (SWITCH_MODE · COMPLETED)",
                        kind = ToolKind.SWITCH_MODE,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "other_action (OTHER · COMPLETED)",
                        kind = ToolKind.OTHER,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "unknown (null · COMPLETED)",
                        kind = null,
                        status = ToolCallStatus.COMPLETED,
                    ),
                    onClick = {},
                )
                ToolCallCard(
                    toolCall = ToolCallDisplay(
                        title = "delete_file (DELETE · PENDING · permissions)",
                        kind = ToolKind.DELETE,
                        status = ToolCallStatus.PENDING,
                        permissionOptions = listOf(
                            AcpPermissionOption(
                                id = "1",
                                label = "Allow",
                                kind = "allow_once",
                            ),
                        ),
                    ),
                    onClick = {},
                )
            }
        }
    }
}
