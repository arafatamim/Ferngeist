package com.tamimarafat.ferngeist.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
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
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SegmentBlock
import com.tamimarafat.ferngeist.feature.chat.displayBlocks
import com.tamimarafat.ferngeist.feature.chat.markdown.MarkdownBlocks
import com.tamimarafat.ferngeist.feature.chat.markdown.MarkdownTypography
import com.tamimarafat.ferngeist.feature.chat.markdown.RunReveal
import com.tamimarafat.ferngeist.feature.chat.markdown.rememberReducedMotion
import com.tamimarafat.ferngeist.feature.chat.markdown.revealLength
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun MessageBubble(
    message: ChatMessage,
    markdownDocuments: ImmutableMap<String, MarkdownRenderedDocument>,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLastMessage: Boolean = false,
    onStreamLayoutSettled: () -> Unit = {},
    onRetryMessage: ((String) -> Unit)? = null,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val reveals = remember(message.id) { BubbleReveals() }
    // Re-derived per message change so runs added by the latest chunk are tracked. Text can still
    // be revealing after the stream ends, and the bubble keeps growing until it settles.
    val revealing by remember(message) { derivedStateOf { reveals.isRevealing } }
    val growing = message.isStreaming || revealing
    // Appended while this screen watched (a reply streaming in, a message just sent), as opposed
    // to history. Such a bubble expands in, and keeps animating its height and following the
    // bottom for as long as it stays composed: dropping both the moment the stream and reveal
    // ended cut the last line's height spring short, and the bubble popped to full height
    // unfollowed. History never moves on its own, so a restored scroll position is never pulled.
    val bornLive = remember { message.isStreaming || message.status != MessageDeliveryStatus.SENT }
    val live = bornLive || growing
    val entrance = remember { MutableTransitionState(!bornLive).apply { targetState = true } }
    val contentColor =
        if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    var fullscreenImage by remember { mutableStateOf<ChatImageData?>(null) }

    AnimatedVisibility(
        visibleState = entrance,
        modifier =
            modifier.fillMaxWidth().then(
                if (isLastMessage && live) Modifier.onSizeChanged { onStreamLayoutSettled() } else Modifier,
            ),
        enter =
            fadeIn(tween(ENTRANCE_FADE_MS)) +
                expandVertically(spring(stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Top),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (isUser) {
                UserMessageBubble(
                    message = message,
                    contentColor = contentColor,
                    onRetryMessage = onRetryMessage,
                    onImageClick = { fullscreenImage = it },
                )
            } else {
                AssistantMessageContent(
                    message = message,
                    markdownDocuments = markdownDocuments,
                    reveals = reveals,
                    showStreamingIndicator = showStreamingIndicator,
                    onThoughtClick = onThoughtClick,
                    onToolCallClick = onToolCallClick,
                    // Height springs to each new line instead of jumping by a line at a time. Only on a
                    // live bubble: on history it would animate unrelated size changes (rotation).
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                // Paused while a tool group folds: chasing the fold's expressive spring
                                // with this softer one made live folds feel unlike history's.
                                if (live && reveals.foldsInFlight.intValue == 0) {
                                    Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                                } else {
                                    Modifier
                                },
                            ),
                )
            }
        }
    }

    fullscreenImage?.let { image ->
        ImageFullscreenViewer(image = image, onDismiss = { fullscreenImage = null })
    }
}

private const val ENTRANCE_FADE_MS = 220

/** Shared with [PinnedPrompt], whose chip must read as the same bubble. */
internal val UserBubbleShape =
    RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 8.dp,
    )
private const val SHIMMER_START_OFFSET = -200f
private const val SHIMMER_END_OFFSET = 600f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    contentColor: Color,
    onRetryMessage: ((String) -> Unit)?,
    onImageClick: (ChatImageData) -> Unit,
) {
    val onRetry =
        if (onRetryMessage != null && message.status == MessageDeliveryStatus.FAILED) {
            { onRetryMessage(message.clientId ?: message.id) }
        } else {
            null
        }
    ElevatedCard(
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = contentColor,
            ),
        shape = UserBubbleShape,
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        UserMessageContent(
            message = message,
            textColor = contentColor,
            onRetry = onRetry,
            onImageClick = { onImageClick(it) },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageContent(
    message: ChatMessage,
    textColor: Color,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onImageClick: ((ChatImageData) -> Unit)? = null,
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
            ImageAttachments(message.images, onImageClick = onImageClick)
        }

        // Files
        if (message.files.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FileAttachments(message.files)
        }

        // Status badge for non-SENT delivery states. Shrinks out rather than vanishing: on delivery
        // the bubble losing the badge's height in one frame dropped the transcript by as much.
        AnimatedVisibility(
            visible = message.role == ChatMessage.Role.USER && message.status != MessageDeliveryStatus.SENT,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                DeliveryStatusBadge(
                    status = message.status,
                    onRetry = onRetry,
                )
            }
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
    val springSpec =
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (
                fadeIn(springSpec) +
                    scaleIn(
                        initialScale = 0.6f,
                        animationSpec = springSpec,
                    )
            ) togetherWith (
                fadeOut(springSpec) +
                    scaleOut(
                        targetScale = 0.6f,
                        animationSpec = springSpec,
                    )
            )
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
                FailedStatusContent(onRetry)
            }
            MessageDeliveryStatus.SENT -> { /* never rendered */ }
        }
    }
}

@Composable
private fun FailedStatusContent(onRetry: (() -> Unit)?) {
    Row(
        modifier =
            Modifier
                .then(
                    if (onRetry != null) {
                        Modifier.clickable { onRetry() }
                    } else {
                        Modifier
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.chat_status_failed),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
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

@Composable
private fun AssistantMessageContent(
    message: ChatMessage,
    markdownDocuments: ImmutableMap<String, MarkdownRenderedDocument>,
    reveals: BubbleReveals,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Blocks after a run that is still revealing wait for it, so a tool card never appears
        // below prose that is still being written.
        var gateOpen = true
        // One block per run, walked in segment order so a turn that interleaves reasoning, prose
        // and tool calls still reads the way the agent produced it. Grouping must not reorder:
        // collecting thoughts and prose in separate passes would hoist every reasoning bubble
        // above the prose that preceded it.
        val blocks = message.segments.displayBlocks()
        blocks.forEach { block ->
            if (!gateOpen) return@forEach
            key(block.key) {
                when (block) {
                    is SegmentBlock.Run ->
                        if (block.kind == AssistantSegment.Kind.THOUGHT) {
                            AssistantSegmentContent(
                                // The run's LAST chunk, not the first: AssistantSegmentContent
                                // decides the streaming shimmer by comparing against
                                // segments.lastOrNull(), so the shimmer follows the growing edge.
                                segment = block.segments.last(),
                                message = message,
                                onThoughtClick = onThoughtClick,
                            )
                        } else if (block.text.isNotBlank()) {
                            // Keyed by the run's first chunk: MarkdownStateStore caches the whole
                            // run's parse under that id, and the key is stable as chunks arrive,
                            // so the node survives and the bubble grows instead of resetting
                            // every chunk.
                            val document = markdownDocuments[block.key]
                            val total = remember(document) { document?.blocks?.sumOf { it.revealLength() } ?: 0 }
                            val reveal = reveals.forRun(block.key, startHidden = message.isStreaming, total)
                            SideEffect { reveal.target = total }
                            RenderedMarkdown(text = block.text, document = document, reveal = reveal)
                            Spacer(modifier = Modifier.height(8.dp))
                            val settled by remember(reveal) { derivedStateOf { reveal.isSettled } }
                            gateOpen = settled
                        }

                    is SegmentBlock.Group -> {
                        ToolCallGroup(
                            items = block.items,
                            isStreaming = message.isStreaming,
                            isTail = block === blocks.last(),
                            liveSegmentId =
                                message.segments
                                    .lastOrNull()
                                    ?.id
                                    .takeIf { message.isStreaming },
                            onToolCallClick = onToolCallClick,
                            onThoughtClick = onThoughtClick,
                            foldsInFlight = reveals.foldsInFlight,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    is SegmentBlock.Single ->
                        AssistantSegmentContent(
                            segment = block.segment,
                            message = message,
                            onThoughtClick = onThoughtClick,
                        )
                }
            }
        }

        if (message.segments.isEmpty() && message.content.isNotBlank()) {
            RenderedMarkdown(text = message.content, document = markdownDocuments[message.id])
        }

        TurnFooter(
            message = message,
            reveals = reveals,
            showStreamingIndicator = showStreamingIndicator,
            settled = gateOpen,
            onToolCallClick = onToolCallClick,
        )
    }
}

/** What closes a turn: the waiting indicator while it is open, its edits once it has ended. */
@Composable
private fun ColumnScope.TurnFooter(
    message: ChatMessage,
    reveals: BubbleReveals,
    showStreamingIndicator: Boolean,
    settled: Boolean,
    onToolCallClick: (String) -> Unit,
) {
    if (showStreamingIndicator) {
        val empty = message.segments.isEmpty() && message.content.isBlank()
        // Fade only: the live bubble's height spring already carries the size change.
        AnimatedVisibility(
            visible = empty || rememberTurnWaiting(message, reveals),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            StreamingIndicator(
                streamKey = message.id,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    // The turn's edits, once it has ended and everything above has finished revealing.
    AnimatedVisibility(visible = !message.isStreaming && settled, enter = fadeIn(), exit = fadeOut()) {
        TurnChanges(
            segments = message.segments,
            onToolCallClick = onToolCallClick,
            foldsInFlight = reveals.foldsInFlight,
        )
    }
}

/**
 * True while the turn is still open but nothing on screen shows it working: no text revealing, no
 * tool running (its own spinner would), the last item not a thought (it carries its own
 * indicator), and no update for [TURN_QUIET_MS]. Without it, a turn waiting on the model after a
 * tool call reads as finished.
 */
@Composable
private fun rememberTurnWaiting(
    message: ChatMessage,
    reveals: BubbleReveals,
): Boolean {
    val revealing by remember(message) { derivedStateOf { reveals.isRevealing } }
    var quiet by remember { mutableStateOf(false) }
    // Keyed on the message: every chunk and status change is a new instance, restarting the wait.
    LaunchedEffect(message) {
        quiet = false
        delay(TURN_QUIET_MS)
        quiet = true
    }
    val busy =
        message.segments.lastOrNull()?.kind == AssistantSegment.Kind.THOUGHT ||
            message.segments.any {
                it.toolCall?.status == ToolCallStatus.PENDING || it.toolCall?.status == ToolCallStatus.IN_PROGRESS
            }
    return quiet && !revealing && !busy
}

/**
 * The reveal cursors of one bubble's MESSAGE runs, keyed by run key. Plain map: it lives exactly
 * as long as the list item is composed, and a run first seen while its message is not streaming
 * starts fully shown, so history and scroll-back never animate.
 */
@Stable
internal class BubbleReveals {
    private val runs = HashMap<String, RunReveal>()

    fun forRun(
        key: String,
        startHidden: Boolean,
        target: Int,
    ): RunReveal = runs.getOrPut(key) { RunReveal(startHidden).also { it.target = target } }

    val isRevealing: Boolean
        get() = runs.values.any { !it.isSettled }

    /** Tool groups mid-fold. The bubble's height spring pauses so the fold's own spring is the only one. */
    val foldsInFlight = mutableIntStateOf(0)
}

/**
 * Renders one non-MESSAGE segment.
 *
 * MESSAGE never reaches here: a streamed reply arrives one segment per chunk, and rendering each
 * of those as its own markdown block puts one word per line until the reducer folds the run at
 * turn close. [AssistantMessageContent] renders MESSAGE through `displayBlocks()` instead.
 */
@Composable
private fun AssistantSegmentContent(
    segment: AssistantSegment,
    message: ChatMessage,
    onThoughtClick: (String) -> Unit,
) {
    when (segment.kind) {
        // Both render through displayBlocks(): MESSAGE as runs, TOOL_CALL as groups.
        AssistantSegment.Kind.MESSAGE, AssistantSegment.Kind.TOOL_CALL -> Unit

        AssistantSegment.Kind.THOUGHT -> {
            ThoughtBubble(
                // The message id, not the segment id: the segment id changes on every chunk, and
                // the polygon set and verb must stay put for the whole turn or the row flickers.
                streamKey = message.id,
                isStreaming = message.isStreaming && message.segments.lastOrNull()?.id == segment.id,
                onClick = { onThoughtClick(segment.id) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        AssistantSegment.Kind.PLAN -> {
            PlanBubble(segment.planEntries.orEmpty())
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Renders [text] as markdown, fading in each block the parser just created.
 *
 * While the parse is still in flight this falls back to the raw text at body typography. A
 * missing parse is not a reason to render nothing: an unparsed bubble with no fallback
 * measures zero height, so when the parse lands the bubble grows from nothing to full height
 * and shoves every message below it down the viewport.
 *
 * The fallback is plain text, not a fixed-height placeholder, because a guessed height is
 * still wrong: real markdown height varies with the content, so a placeholder that reserves
 * the wrong number of lines jitters just as much. Rendering the text itself means the height
 * is already correct when the parse swaps in, and the swap is then a formatting change within
 * an unchanged line count.
 */
@Composable
private fun RenderedMarkdown(
    text: String,
    document: MarkdownRenderedDocument?,
    modifier: Modifier = Modifier,
    reveal: RunReveal? = null,
) {
    if (reveal != null && reveal.animates) {
        val reducedMotion = rememberReducedMotion()
        LaunchedEffect(reveal) { reveal.run(reducedMotion) }
    }
    if (document != null && document.blocks.isNotEmpty()) {
        MarkdownBlocks(
            blocks = document.blocks,
            modifier = modifier.fillMaxWidth(),
            reveal = reveal?.takeIf { it.animates },
            typography =
                MarkdownTypography(
                    headlineLarge = MaterialTheme.typography.titleLarge,
                    headlineMedium = MaterialTheme.typography.titleMedium,
                    headlineSmall = MaterialTheme.typography.titleSmall,
                    titleLarge = MaterialTheme.typography.titleMedium,
                    titleMedium = MaterialTheme.typography.bodyMedium,
                    titleSmall = MaterialTheme.typography.bodySmall,
                    bodyLarge = MaterialTheme.typography.bodyMedium,
                    bodyMedium = MaterialTheme.typography.bodyMedium,
                    labelMedium = MaterialTheme.typography.labelSmall,
                ),
        )
    } else if (reveal == null || !reveal.animates) {
        // The parse has not landed yet. Render the text plainly so the bubble occupies its
        // real height now; when the parse arrives the formatting swaps in without the
        // surrounding list having to re-measure. A streaming run renders nothing instead:
        // raw text there would show everything at once, then collapse to the reveal.
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThoughtBubble(
    streamKey: String,
    isStreaming: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val reasoningDesc = stringResource(R.string.chat_reasoning_desc)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .semantics {
                    contentDescription = reasoningDesc
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStreaming) {
            // Same indicator a streamed reply gets. The chevron is deliberately withheld: it
            // marks the settled row that expands on tap, not the in-flight one.
            StreamingIndicator(
                streamKey = streamKey,
                baseColor = baseColor,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.chat_show_reasoning),
                style = MaterialTheme.typography.bodySmall.copy(color = baseColor),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = reasoningDesc,
                tint = baseColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PlanBubble(
    entries: List<PlanEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            entries.forEachIndexed { index, entry ->
                PlanEntryItem(entry)
                if (index < entries.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PlanEntryItem(entry: PlanEntry) {
    val isCompleted = entry.status == PlanEntryStatus.COMPLETED
    val isInProgress = entry.status == PlanEntryStatus.IN_PROGRESS
    val isPending = entry.status == PlanEntryStatus.PENDING
    val iconTint =
        if (isInProgress) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
        }
    val textColor by animateColorAsState(
        targetValue =
            if (isPending) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            } else if (isInProgress) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
            },
        label = "planTextColor",
    )

    // Pop the checkbox when it ticks, so a completed step is felt rather than read.
    val tick by animateFloatAsState(
        targetValue = if (isCompleted) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "planTick",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription =
                if (isCompleted) {
                    stringResource(R.string.chat_completed_desc)
                } else {
                    stringResource(R.string.chat_plan_desc)
                },
            tint = iconTint,
            modifier =
                Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        val pop =
                            if (isCompleted) {
                                PLAN_TICK_POP_MIN_SCALE + (1f - PLAN_TICK_POP_MIN_SCALE) * tick
                            } else {
                                1f
                            }
                        scaleX = pop
                        scaleY = pop
                    },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            fontWeight = if (isInProgress) FontWeight.Medium else null,
            textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val TOOL_STATUS_SETTLE_MS = 180
private const val TURN_QUIET_MS = 600L
private const val TOOL_STATUS_MIN_SCALE = 0.6f
private const val PLAN_TICK_POP_MIN_SCALE = 0.4f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ToolCallStatusIndicator(toolCall: ToolCallDisplay) {
    toolCall.status?.let { status ->
        val isTerminal = status == ToolCallStatus.COMPLETED || status == ToolCallStatus.FAILED
        val containerColor by animateColorAsState(
            targetValue =
                if (status == ToolCallStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            label = "toolStatusContainer",
        )

        // Scale+fade the terminal state in rather than swapping it in: the swap used to land on the
        // frame the spinner stopped, so a whole step of the agent's work passed with no signal.
        val settle by animateFloatAsState(
            targetValue = if (isTerminal) 1f else 0f,
            animationSpec = tween(if (isTerminal) TOOL_STATUS_SETTLE_MS else 0),
            label = "toolStatusSettle",
        )

        when (status) {
            ToolCallStatus.PENDING, ToolCallStatus.IN_PROGRESS ->
                ContainedLoadingIndicator(
                    polygons = pickLoadingPolygons(toolCall.toolCallId ?: toolCall.title),
                    containerShape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )

            ToolCallStatus.COMPLETED, ToolCallStatus.FAILED -> {
                val failed = status == ToolCallStatus.FAILED
                Surface(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = TOOL_STATUS_MIN_SCALE + (1f - TOOL_STATUS_MIN_SCALE) * settle
                                scaleY = scaleX
                                alpha = settle
                            },
                    shape = MaterialTheme.shapes.medium,
                    color = containerColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = if (failed) Icons.Rounded.Error else toolKindIcon(toolCall.kind),
                            contentDescription =
                                stringResource(
                                    if (failed) R.string.chat_error_desc else R.string.chat_completed_desc,
                                ),
                            tint =
                                if (failed) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                        )
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
    modifier: Modifier = Modifier,
    onImageClick: ((ChatImageData) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        images.forEach { image ->
            key(image.base64) {
                ImageAttachmentItem(image = image, onClick = onImageClick?.let { cb -> { cb(image) } })
            }
        }
    }
}

@Composable
private fun FileAttachments(
    files: List<ChatFileData>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        files.forEach { file ->
            key("${file.name}:${file.sizeBytes}") {
                FileAttachmentItem(file = file)
            }
        }
    }
}

@Composable
private fun FileAttachmentItem(file: ChatFileData) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
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
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = FileAttachmentHelper.formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun rememberImageBitmap(base64: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, base64) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
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
    }.value

@Composable
private fun ImageAttachmentItem(
    image: ChatImageData,
    onClick: (() -> Unit)? = null,
) {
    val bitmap = rememberImageBitmap(image.base64)

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamingIndicator(
    streamKey: String,
    modifier: Modifier = Modifier,
    baseColor: Color = LocalContentColor.current.copy(alpha = 0.8f),
) {
    val resources = LocalResources.current
    val spinnerVerb =
        remember(streamKey) {
            val verbs = resources.getStringArray(R.array.chat_spinner_verbs)
            verbs[Random.nextInt(verbs.size)]
        }
    val polygons = remember(streamKey) { pickLoadingPolygons(streamKey) }
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
            text = stringResource(R.string.chat_streaming_indicator, spinnerVerb),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    brush = textBrush,
                ),
        )
    }
}

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

private const val LOADING_POLYGON_COUNT = 6

private fun pickLoadingPolygons(seedKey: String) =
    LOADING_SHAPES.shuffled(Random(seedKey.hashCode())).take(LOADING_POLYGON_COUNT)

private fun toolKindIcon(kind: ToolKind?): ImageVector =
    when (kind) {
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
internal fun rememberShimmerTextBrush(
    isActive: Boolean,
    baseColor: Color,
    labelPrefix: String,
): Brush {
    val shimmerTransition = rememberInfiniteTransition(label = "${labelPrefix}Shimmer")
    val shimmerOffset =
        if (isActive) {
            shimmerTransition
                .animateFloat(
                    initialValue = SHIMMER_START_OFFSET,
                    targetValue = SHIMMER_END_OFFSET,
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
            start = Offset(shimmerOffset - SHIMMER_START_OFFSET, 0f),
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
            entries =
                listOf(
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
