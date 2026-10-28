package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SegmentBlock
import com.tamimarafat.ferngeist.feature.chat.displayBlocks
import com.tamimarafat.ferngeist.feature.chat.markdown.InlineChips
import com.tamimarafat.ferngeist.feature.chat.markdown.MarkdownBlocks
import com.tamimarafat.ferngeist.feature.chat.markdown.RunReveal
import com.tamimarafat.ferngeist.feature.chat.markdown.documentMarkdownTypography
import com.tamimarafat.ferngeist.feature.chat.markdown.localInlineChips
import com.tamimarafat.ferngeist.feature.chat.markdown.rememberReducedMotion
import com.tamimarafat.ferngeist.feature.chat.markdown.revealLength
import com.tamimarafat.ferngeist.feature.chat.ui.message.FileAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.message.ImageAttachments
import com.tamimarafat.ferngeist.feature.chat.ui.message.PlanBubble
import com.tamimarafat.ferngeist.feature.chat.ui.message.StreamingIndicator
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.delay

@Composable
fun MessageBubble(
    message: ChatMessage,
    markdownDocuments: ImmutableMap<String, MarkdownRenderedDocument>,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            modifier.fillMaxWidth(),
        enter =
            fadeIn(tween(ENTRANCE_FADE_MS)) +
                expandVertically(spring(stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Top),
    ) {
        BubbleContent(
            message = message,
            isUser = isUser,
            contentColor = contentColor,
            markdownDocuments = markdownDocuments,
            reveals = reveals,
            live = live,
            showStreamingIndicator = showStreamingIndicator,
            onThoughtClick = onThoughtClick,
            onToolCallClick = onToolCallClick,
            onRetryMessage = onRetryMessage,
            onImageClick = { fullscreenImage = it },
        )
    }

    fullscreenImage?.let { image ->
        ImageFullscreenViewer(image = image, onDismiss = { fullscreenImage = null })
    }
}

/** The bubble's row: a user prompt at the end, or the assistant's content at the start. */
@Composable
private fun BubbleContent(
    message: ChatMessage,
    isUser: Boolean,
    contentColor: Color,
    markdownDocuments: ImmutableMap<String, MarkdownRenderedDocument>,
    reveals: BubbleReveals,
    live: Boolean,
    showStreamingIndicator: Boolean,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    onImageClick: (ChatImageData) -> Unit,
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
                onImageClick = onImageClick,
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

private const val ENTRANCE_FADE_MS = 220

/** Shared with [PinnedPrompt], whose chip must read as the same bubble. */
internal val userBubbleShape =
    RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 8.dp,
    )
internal const val SHIMMER_START_OFFSET = -200f
internal const val SHIMMER_END_OFFSET = 600f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    contentColor: Color,
    onRetryMessage: ((String) -> Unit)?,
    onImageClick: (ChatImageData) -> Unit,
    maxLines: Int = Int.MAX_VALUE,
) {
    val onRetry =
        if (onRetryMessage != null && message.status == MessageDeliveryStatus.FAILED) {
            { onRetryMessage(message.clientId ?: message.id) }
        } else {
            null
        }
    // Flat: the bubble rests in the list. Only the pinned copy floats over content and casts a shadow.
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = contentColor,
            ),
        shape = userBubbleShape,
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        UserMessageContent(
            message = message,
            textColor = contentColor,
            onRetry = onRetry,
            onImageClick = { onImageClick(it) },
            maxLines = maxLines,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * Prompts the agent has not taken yet, pinned above the composer in send order. Each leaves
 * when its echo lands in the transcript. Swiping one sideways drops it; swiping one up
 * force-sends it. Neither works mid-send.
 */
@Composable
internal fun PendingPromptQueue(
    messages: List<ChatMessage>,
    onRetryMessage: (String) -> Unit,
    onForceSendMessage: (String) -> Unit,
    onRemoveMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .animateContentSize()
                .heightIn(max = pendingQueueMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        messages.forEach { message ->
            key(message.clientId ?: message.id) {
                SwipeToDismissBox(
                    state = rememberSwipeToDismissBoxState(),
                    backgroundContent = {},
                    modifier = Modifier.padding(bottom = 8.dp),
                    gesturesEnabled = message.status != MessageDeliveryStatus.SENDING,
                    onDismiss = { onRemoveMessage(message.clientId ?: message.id) },
                ) {
                    SwipeUpToSend(
                        enabled = message.status == MessageDeliveryStatus.QUEUED,
                        onSend = { onForceSendMessage(message.clientId ?: message.id) },
                    ) {
                        UserMessageBubble(
                            message = message,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onRetryMessage = onRetryMessage,
                            onImageClick = {},
                            maxLines = PENDING_PROMPT_MAX_LINES,
                        )
                    }
                }
            }
        }
    }
}

private val pendingQueueMaxHeight = 200.dp
private const val PENDING_PROMPT_MAX_LINES = 3

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserMessageContent(
    message: ChatMessage,
    textColor: Color,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onImageClick: ((ChatImageData) -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (message.images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ImageAttachments(message.images, onImageClick = onImageClick)
            }
            if (message.files.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FileAttachments(message.files)
            }
        }
        // Trailing status for non-SENT delivery states.
        AnimatedVisibility(
            visible = message.role == ChatMessage.Role.USER && message.status != MessageDeliveryStatus.SENT,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            DeliveryStatusBadge(
                status = message.status,
                onRetry = onRetry,
                modifier = Modifier.padding(start = 8.dp),
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
                        // The one way to recover an unsent prompt: a full-size, announced target.
                        Modifier
                            .minimumInteractiveComponentSize()
                            .handCursor()
                            .clickable(role = Role.Button, onClick = onRetry)
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
        val liveSegmentId =
            message.segments
                .lastOrNull()
                ?.id
                .takeIf { message.isStreaming }
        val blocks = message.segments.displayBlocks()
        val fileChips = rememberFileChips(message.segments, message.isStreaming)
        blocks.forEach { block ->
            if (!gateOpen) return@forEach
            key(block.key) {
                assistantBlock(
                    block = block,
                    isTail = block === blocks.last(),
                    message = message,
                    liveSegmentId = liveSegmentId,
                    markdownDocuments = markdownDocuments,
                    reveals = reveals,
                    fileChips = fileChips,
                    onToolCallClick = onToolCallClick,
                    onThoughtClick = onThoughtClick,
                )?.let { gateOpen = it }
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

/** A run of tool calls or reasoning, drawn on the rail, followed by the bubble's usual gap. */
@Composable
private fun AssistantToolGroup(
    items: List<SegmentBlock>,
    isStreaming: Boolean,
    isTail: Boolean,
    liveSegmentId: String?,
    startedAtMs: Long?,
    reveals: BubbleReveals,
    onToolCallClick: (String) -> Unit,
    onThoughtClick: (String) -> Unit,
) {
    ToolCallGroup(
        items = items,
        isStreaming = isStreaming,
        isTail = isTail,
        liveSegmentId = liveSegmentId,
        onToolCallClick = onToolCallClick,
        onThoughtClick = onThoughtClick,
        foldsInFlight = reveals.foldsInFlight,
        startedAtMs = startedAtMs,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * One block of an assistant message. Returns whether the blocks after it may open, or null when it
 * leaves the gate as it found it.
 */
@Composable
private fun assistantBlock(
    block: SegmentBlock,
    isTail: Boolean,
    message: ChatMessage,
    liveSegmentId: String?,
    markdownDocuments: ImmutableMap<String, MarkdownRenderedDocument>,
    reveals: BubbleReveals,
    fileChips: InlineChips?,
    onToolCallClick: (String) -> Unit,
    onThoughtClick: (String) -> Unit,
): Boolean? =
    when (block) {
        is SegmentBlock.Run ->
            when {
                // Reasoning with no adjacent call still gets the rail: the same live
                // single-line row while it streams, the same foldable group once settled.
                block.kind == AssistantSegment.Kind.THOUGHT -> {
                    AssistantToolGroup(
                        items = listOf(block),
                        isStreaming = message.isStreaming,
                        isTail = isTail,
                        liveSegmentId = liveSegmentId,
                        startedAtMs = block.firstSegment().receivedAtMs,
                        reveals = reveals,
                        onToolCallClick = onToolCallClick,
                        onThoughtClick = onThoughtClick,
                    )
                    null
                }
                block.text.isNotBlank() ->
                    assistantProseRun(
                        block = block,
                        document = markdownDocuments[block.key],
                        isStreaming = message.isStreaming,
                        reveals = reveals,
                        fileChips = fileChips,
                    )
                else -> null
            }

        is SegmentBlock.Group -> {
            AssistantToolGroup(
                items = block.items,
                isStreaming = message.isStreaming,
                isTail = isTail,
                liveSegmentId = liveSegmentId,
                startedAtMs = block.firstSegment().receivedAtMs,
                reveals = reveals,
                onToolCallClick = onToolCallClick,
                onThoughtClick = onThoughtClick,
            )
            null
        }

        is SegmentBlock.Single -> {
            if (block.segment.kind == AssistantSegment.Kind.PLAN) {
                PlanBubble(block.segment.planEntries.orEmpty())
                Spacer(modifier = Modifier.height(8.dp))
            }
            null
        }
    }

/**
 * Renders one prose run and reports whether its reveal has settled, which is what lets the
 * blocks after it open.
 */
@Composable
private fun assistantProseRun(
    block: SegmentBlock.Run,
    document: MarkdownRenderedDocument?,
    isStreaming: Boolean,
    reveals: BubbleReveals,
    fileChips: InlineChips?,
): Boolean {
    // Keyed by the run's first chunk: MarkdownStateStore caches the whole
    // run's parse under that id, and the key is stable as chunks arrive,
    // so the node survives and the bubble grows instead of resetting
    // every chunk.
    val total = remember(document) { document?.blocks?.sumOf { it.revealLength() } ?: 0 }
    val reveal = reveals.forRun(block.key, startHidden = isStreaming, total)
    SideEffect { reveal.target = total }
    // Prose only: reasoning rows render through ToolCallGroup, outside this.
    CompositionLocalProvider(localInlineChips provides fileChips) {
        RenderedMarkdown(text = block.text, document = document, reveal = reveal)
    }
    Spacer(modifier = Modifier.height(8.dp))
    val settled by remember(reveal) { derivedStateOf { reveal.isSettled } }
    return settled
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
            turn = message.turn,
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
internal fun RenderedMarkdown(
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
            typography = documentMarkdownTypography(),
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

internal const val TOOL_STATUS_SETTLE_MS = 180
private const val TURN_QUIET_MS = 600L
internal const val TOOL_STATUS_MIN_SCALE = 0.6f
internal const val PLAN_TICK_POP_MIN_SCALE = 0.4f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val loadingShapes =
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

internal const val LOADING_POLYGON_COUNT = 6

internal fun toolKindIcon(kind: ToolKind?): ImageVector =
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
