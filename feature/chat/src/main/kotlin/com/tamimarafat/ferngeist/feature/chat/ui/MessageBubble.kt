package com.tamimarafat.ferngeist.feature.chat.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    expandedToolCalls: Set<String>,
    onToolCallToggle: (String) -> Unit,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (isUser) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = contentColor
                ),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 8.dp
                ),
                modifier = Modifier.widthIn(max = 420.dp)
            ) {
                UserMessageContent(
                    message = message,
                    textColor = contentColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            AssistantMessageContent(
                message = message,
                markdownStates = markdownStates,
                showStreamingIndicator = showStreamingIndicator,
                expandedToolCalls = expandedToolCalls,
                onToolCallToggle = onToolCallToggle,
                onPermissionGrant = onPermissionGrant,
                onPermissionDeny = onPermissionDeny,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageContent(
    message: ChatMessage,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Text content
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor
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
    expandedToolCalls: Set<String>,
    onToolCallToggle: (String) -> Unit,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    modifier: Modifier = Modifier
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
                        ThoughtBubble(segment.text)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    AssistantSegment.Kind.TOOL_CALL -> {
                        segment.toolCall?.let { toolCall ->
                            ToolCallCard(
                                toolCall = toolCall,
                                isExpanded = expandedToolCalls.contains(toolCall.toolCallId),
                                onToggle = { onToolCallToggle(toolCall.toolCallId ?: "") },
                                onPermissionGrant = onPermissionGrant,
                                onPermissionDeny = onPermissionDeny
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
                modifier = Modifier.padding(vertical = 4.dp)
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
        text = MaterialTheme.typography.bodyLarge,
        paragraph = MaterialTheme.typography.bodyLarge,
        list = MaterialTheme.typography.bodyLarge,
        bullet = MaterialTheme.typography.bodyLarge,
        ordered = MaterialTheme.typography.bodyLarge
    )
    if (state != null) {
        SelectionContainer {
            Markdown(
                state = state,
                typography = compactTypography,
                animations = markdownAnimations(
                    animateTextSize = { this }
                ),
                dimens = markdownDimens(),
                modifier = modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThoughtBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }


    Surface(
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Show reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PlanBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                contentDescription = "Plan",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCallCard(
    toolCall: ToolCallDisplay,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = AbsoluteRoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status badge
                toolCall.status?.let { status ->
                    when (status.lowercase()) {
                        "pending", "in_progress" -> ContainedLoadingIndicator(
                            polygons = pickLoadingPolygons(toolCall.toolCallId ?: toolCall.title),
                            containerShape = MaterialTheme.shapes.medium,
                            containerColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                        "completed" -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                        )
                        "failed" -> Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                        )

                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolCall.title.ifBlank { "Tool Call" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    toolCall.kind?.let { kind ->
                        Text(
                            text = kind,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    
                    // Output
                    (toolCall.output ?: toolCall.rawOutput)?.let { output ->
                        SelectionContainer() {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = output,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Permission options
                    val permissionOptions = toolCall.permissionOptions
                    if (!permissionOptions.isNullOrEmpty()) {
                        val toolCallId = toolCall.toolCallId
                        Text(
                            text = "Permission Required:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        permissionOptions.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row {
                                    if (toolCallId != null) {
                                        TextButton(
                                            onClick = { onPermissionDeny(toolCallId) }
                                        ) {
                                            Text("Deny")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { onPermissionGrant(toolCallId, option.id) }
                                        ) {
                                            Text("Grant")
                                        }
                                    } else {
                                        Text(
                                            text = "Unavailable",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@ExperimentalMaterial3ExpressiveApi
@Composable
private fun ImageAttachments(
    images: List<ChatImageData>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        images.forEach { image ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Image",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Image (${image.mimeType})",
                        style = MaterialTheme.typography.bodySmall
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
    modifier: Modifier = Modifier
) {
    val spinnerVerb = remember(streamKey) {
        STREAMING_VERBS[Random.nextInt(STREAMING_VERBS.size)]
    }
    val polygons = remember(streamKey) { pickLoadingPolygons(streamKey) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(
            polygons = polygons,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$spinnerVerb...",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.8f)
        )
    }
}

// Yoinked from Claude Code. Ref: https://github.com/levindixon/tengu_spinner_words
private val STREAMING_VERBS = listOf(
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

private fun pickLoadingPolygons(seedKey: String) =
    LOADING_SHAPES.shuffled(Random(seedKey.hashCode())).take(6)
