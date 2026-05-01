package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import kotlin.random.Random
import com.mikepenz.markdown.model.State as MarkdownRenderState

@Composable
fun MessageBubble(
    message: ChatMessage,
    markdownStates: Map<String, MarkdownRenderState>,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (isUser) {
            ElevatedCard(
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = contentColor,
                    ),
                shape =
                    RoundedCornerShape(
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Text content
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
        }

        // Images
        if (message.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            ImageAttachments(message.images)
        }
    }
}

@Composable
private fun AssistantMessageContent(
    message: ChatMessage,
    markdownStates: Map<String, MarkdownRenderState>,
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
                        PlanBubble(segment.text)
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
    val compactTypography =
        markdownTypography(
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            h4 = MaterialTheme.typography.bodyLarge,
            h5 = MaterialTheme.typography.bodyMedium,
            h6 = MaterialTheme.typography.bodySmall,
            text = MaterialTheme.typography.bodyLarge,
            paragraph = MaterialTheme.typography.bodyLarge,
            list = MaterialTheme.typography.bodyLarge,
            bullet = MaterialTheme.typography.bodyLarge,
            ordered = MaterialTheme.typography.bodyLarge,
        )
    if (state != null) {
        SelectionContainer {
            Markdown(
                state = state,
                typography = compactTypography,
                animations =
                    markdownAnimations(
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
    val textBrush =
        rememberShimmerTextBrush(
            isActive = isStreaming,
            baseColor = baseColor,
            labelPrefix = "reasoning",
        )

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isStreaming) "Reasoning" else "Show Reasoning",
            style =
                MaterialTheme.typography.bodySmall.copy(
                    brush = textBrush,
                ),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "Show reasoning",
            tint = baseColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PlanBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                contentDescription = "Plan",
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
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
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            ),
        shape = CardDefaults.shape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick() }
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status badge
                toolCall.status?.let { status ->
                    when (status.lowercase()) {
                        "pending", "in_progress" ->
                            ContainedLoadingIndicator(
                                polygons = pickLoadingPolygons(toolCall.toolCallId ?: toolCall.title),
                                containerShape = MaterialTheme.shapes.medium,
                                containerColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        "completed" ->
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completed",
                            )
                        "failed" ->
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error",
                            )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.title.ifBlank { "Tool Call" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    toolCall.kind?.let { kind ->
                        Text(
                            text = kind,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Show tool call details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!toolCall.permissionOptions.isNullOrEmpty()) {
                Text(
                    text = "Awaiting permission response",
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Image",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Image (${image.mimeType})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamingIndicator(
    streamKey: String,
    modifier: Modifier = Modifier,
) {
    val spinnerVerb =
        remember(streamKey) {
            STREAMING_VERBS[Random.nextInt(STREAMING_VERBS.size)]
        }
    val polygons = remember(streamKey) { pickLoadingPolygons(streamKey) }
    val baseColor = LocalContentColor.current.copy(alpha = 0.8f)
    val textBrush =
        rememberShimmerTextBrush(
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
            text = "$spinnerVerb...",
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    brush = textBrush,
                ),
        )
    }
}

// Yoinked from Claude Code. Ref: https://github.com/levindixon/tengu_spinner_words
private val STREAMING_VERBS =
    listOf(
        "Accomplishing",
        "Actioning",
        "Actualizing",
        "Baking",
        "Brewing",
        "Calculating",
        "Cerebrating",
        "Churning",
        "Coalescing",
        "Cogitating",
        "Computing",
        "Conjuring",
        "Considering",
        "Cooking",
        "Crafting",
        "Creating",
        "Crunching",
        "Deliberating",
        "Determining",
        "Doing",
        "Effecting",
        "Finagling",
        "Forging",
        "Forming",
        "Generating",
        "Hatching",
        "Herding",
        "Honking",
        "Hustling",
        "Ideating",
        "Inferring",
        "Manifesting",
        "Marinating",
        "Moseying",
        "Mulling",
        "Mustering",
        "Musing",
        "Noodling",
        "Percolating",
        "Pondering",
        "Processing",
        "Puttering",
        "Reticulating",
        "Ruminating",
        "Schlepping",
        "Shucking",
        "Simmering",
        "Smooshing",
        "Spinning",
        "Stewing",
        "Synthesizing",
        "Thinking",
        "Transmuting",
        "Vibing",
        "Working",
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val LOADING_SHAPES =
    listOf(
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

@Composable
private fun rememberShimmerTextBrush(
    isActive: Boolean,
    baseColor: Color,
    labelPrefix: String,
): Brush {
    val shimmerTransition = rememberInfiniteTransition(label = "${labelPrefix}Shimmer")
    val shimmerOffset =
        if (isActive) {
            shimmerTransition
                .animateFloat(
                    initialValue = -200f,
                    targetValue = 600f,
                    animationSpec =
                        infiniteRepeatable(
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
            colors =
                listOf(
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
