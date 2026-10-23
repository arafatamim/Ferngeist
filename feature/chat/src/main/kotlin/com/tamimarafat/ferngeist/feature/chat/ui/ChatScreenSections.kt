@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.common.ui.ConnectionDiagnosticsDialog
import com.tamimarafat.ferngeist.core.common.ui.ErrorStateCard
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.model.ChatCommand
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConnectionDiagnostics
import com.tamimarafat.ferngeist.core.model.ChatConnectionState
import com.tamimarafat.ferngeist.core.model.ChatElicitationRequest
import com.tamimarafat.ferngeist.core.model.ChatElicitationValue
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import com.tamimarafat.ferngeist.core.model.UsageState
import com.tamimarafat.ferngeist.feature.chat.ChatState
import com.tamimarafat.ferngeist.feature.chat.FileAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.ImageAttachmentHelper
import com.tamimarafat.ferngeist.feature.chat.IncrementalMarkdown
import com.tamimarafat.ferngeist.feature.chat.MarkdownRenderedDocument
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.RecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.displayBlocks
import com.tamimarafat.ferngeist.feature.chat.localizeChatError
import com.tamimarafat.ferngeist.feature.chat.thoughtRuns
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val SKELETON_SHIMMER_BAND_PX = 200f
private const val SKELETON_SHIMMER_MILLIS = 800

/** Ceiling on pattern repeats: unbounded constraints would otherwise loop forever. */
private const val SKELETON_MAX_PASSES = 16
private val SKELETON_LINE_HEIGHT = 12.dp
private val SKELETON_LINE_GAP = 8.dp

/** Gap between turns. Deliberately wider than [SKELETON_LINE_GAP] so the pattern reads as separate messages. */
private val SKELETON_GROUP_GAP = 20.dp
private val SKELETON_USER_LINE = listOf(1f)

// Assistant blocks stay between 4 and 7 lines: long enough to read as a response.
private val SKELETON_ASSISTANT_LINES_PRIMARY = listOf(1f, 0.92f, 0.78f, 0.6f)
private val SKELETON_ASSISTANT_LINES_EXTENDED = listOf(1f, 0.95f, 0.88f, 0.97f, 0.9f, 0.86f, 0.5f)

/** One placeholder message: right-aligned and short for the user, full-width for the assistant. */
private data class SkeletonMessageSpec(
    val alignment: Alignment.Horizontal,
    val widthFraction: Float,
    val lineFractions: List<Float>,
)

private val SKELETON_PATTERN =
    listOf(
        SkeletonMessageSpec(Alignment.End, 0.5f, SKELETON_USER_LINE),
        SkeletonMessageSpec(Alignment.Start, 1f, SKELETON_ASSISTANT_LINES_PRIMARY),
        SkeletonMessageSpec(Alignment.End, 0.38f, SKELETON_USER_LINE),
        SkeletonMessageSpec(Alignment.Start, 1f, SKELETON_ASSISTANT_LINES_EXTENDED),
        SkeletonMessageSpec(Alignment.End, 0.44f, SKELETON_USER_LINE),
        SkeletonMessageSpec(Alignment.Start, 1f, SKELETON_ASSISTANT_LINES_PRIMARY),
    )

/** Height of one full pass of [SKELETON_PATTERN], including the gap that follows it. */
private val SKELETON_PATTERN_HEIGHT: Dp =
    SKELETON_PATTERN.fold(SKELETON_GROUP_GAP * SKELETON_PATTERN.size) { total, message ->
        total +
            SKELETON_LINE_HEIGHT * message.lineFractions.size +
            SKELETON_LINE_GAP * (message.lineFractions.size - 1)
    }

/**
 * Processes picked URIs into images and file attachments, returning the combined
 * lists (capped at max) and an optional feedback message for the user.
 * Extracted from [ChatScreen] to reduce its cyclomatic complexity.
 */
internal suspend fun processPickedUris(
    uris: List<android.net.Uri>,
    context: android.content.Context,
    canSendImages: Boolean,
    supportsEmbeddedContext: Boolean,
    existingImages: List<com.tamimarafat.ferngeist.core.model.ChatImageData>,
    existingFiles: List<com.tamimarafat.ferngeist.core.model.ChatFileData>,
    resources: Resources,
): Triple<
    List<com.tamimarafat.ferngeist.core.model.ChatImageData>,
    List<com.tamimarafat.ferngeist.core.model.ChatFileData>,
    String?,
> {
    val newImages = mutableListOf<com.tamimarafat.ferngeist.core.model.ChatImageData>()
    val fileResults = mutableListOf<FileAttachmentHelper.Result>()
    var imagesDropped = 0
    var unsupportedCount = 0
    for (uri in uris) {
        val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
        when {
            isImage && canSendImages -> {
                val image = ImageAttachmentHelper.uriToChatImageData(context.contentResolver, uri)
                if (image != null) newImages += image else imagesDropped++
            }
            supportsEmbeddedContext ->
                fileResults += FileAttachmentHelper.uriToChatFileData(context.contentResolver, uri)
            else -> unsupportedCount++
        }
    }
    val newFiles = fileResults.filterIsInstance<FileAttachmentHelper.Result.Success>().map { it.file }
    val tooLargeCount = fileResults.count { it is FileAttachmentHelper.Result.TooLarge }

    val combinedImages = (existingImages + newImages).take(ImageAttachmentHelper.MAX_IMAGES)
    val imagesCapped = (existingImages.size + newImages.size) - combinedImages.size
    val combinedFiles = (existingFiles + newFiles).take(FileAttachmentHelper.MAX_FILES)
    val filesCapped = (existingFiles.size + newFiles.size) - combinedFiles.size

    val feedback =
        buildPickFeedbackMessage(
            resources = resources,
            unsupportedCount = unsupportedCount,
            tooLargeCount = tooLargeCount,
            imagesDropped = imagesDropped,
            imagesCapped = imagesCapped,
            filesCapped = filesCapped,
        )
    return Triple(combinedImages, combinedFiles, feedback)
}

private fun buildPickFeedbackMessage(
    resources: Resources,
    unsupportedCount: Int,
    tooLargeCount: Int,
    imagesDropped: Int,
    imagesCapped: Int,
    filesCapped: Int,
): String? =
    when {
        unsupportedCount > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_attachments_unsupported,
                unsupportedCount,
                unsupportedCount,
            )
        tooLargeCount > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_files_too_large,
                tooLargeCount,
                tooLargeCount,
            )
        imagesDropped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_images_dropped,
                imagesDropped,
                imagesDropped,
            )
        imagesCapped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_images_capped,
                ImageAttachmentHelper.MAX_IMAGES,
                ImageAttachmentHelper.MAX_IMAGES,
            )
        filesCapped > 0 ->
            resources.getQuantityString(
                com.tamimarafat.ferngeist.feature.chat.R.plurals.chat_files_capped,
                FileAttachmentHelper.MAX_FILES,
                FileAttachmentHelper.MAX_FILES,
            )
        else -> null
    }

/**
 * Collects one-shot [ChatEffect]s from the view model and renders them as
 * snackbars or navigates back. Extracted from [ChatScreen] to reduce its
 * cyclomatic complexity.
 */
@Composable
internal fun CollectChatEffects(
    viewModel: com.tamimarafat.ferngeist.feature.chat.ChatViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowError -> {
                    android.util.Log.e("ChatScreen", "Chat effect error: ${effect.message}")
                    snackbarHostState.showSnackbar(localizeChatError(context, effect.message))
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(localizeChatError(context, effect.message))
                }

                is com.tamimarafat.ferngeist.feature.chat.ChatEffect.NavigateBack ->
                    onNavigateBack()
            }
        }
    }
}

/**
 * Hosts the dialog/overlay surfaces that sit above the main chat UI.
 */
@Composable
internal fun ChatScreenDialogs(
    selectedConfigPickerOption: ChatConfigOption.Select?,
    onConfigOptionSelected: (String, String) -> Unit,
    onDismissConfigPicker: () -> Unit,
    selectedThought: String?,
    onDismissThought: () -> Unit,
    selectedToolCall: ToolCallDisplay?,
    onDismissToolCall: () -> Unit,
    activePermissionRequest: PendingPermissionRequest?,
    onPermissionGrant: (String, String) -> Unit,
    onPermissionDeny: (String) -> Unit,
    activeElicitationRequest: ChatElicitationRequest?,
    onElicitationSubmit: (String, Map<String, ChatElicitationValue>) -> Unit,
    onElicitationDecline: (String) -> Unit,
    onElicitationCancel: (String) -> Unit,
    onElicitationOpenUrl: (ChatElicitationRequest.Url) -> Unit,
    showConnectionStatusDialog: Boolean,
    connectionState: ChatConnectionState,
    diagnostics: ChatConnectionDiagnostics,
    usage: UsageState?,
    onDismissConnectionStatus: () -> Unit,
    showCommandsDialog: Boolean,
    commands: List<ChatCommand>,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onDismissCommands: () -> Unit,
    onCommandClick: (String) -> Unit,
) {
    if (selectedConfigPickerOption != null) {
        SelectConfigOptionSheet(
            option = selectedConfigPickerOption,
            serverId = serverId,
            recentSelectionStore = recentSelectionStore,
            onOptionSelected = { value ->
                onConfigOptionSelected(selectedConfigPickerOption.id, value)
            },
            onDismiss = onDismissConfigPicker,
        )
    }

    selectedThought?.let { thought ->
        ThoughtDetailsSheet(
            thought = thought,
            onDismiss = onDismissThought,
        )
    }

    selectedToolCall?.let { toolCall ->
        ToolCallDetailsSheet(
            toolCall = toolCall,
            onDismiss = onDismissToolCall,
        )
    }

    activePermissionRequest?.let { request ->
        PermissionRequestSheet(
            request = request,
            onGrantPermission = onPermissionGrant,
            onDenyPermission = onPermissionDeny,
        )
    }

    activeElicitationRequest?.let { request ->
        ElicitationSheet(
            request = request,
            onSubmit = onElicitationSubmit,
            onDecline = onElicitationDecline,
            onCancel = onElicitationCancel,
            onOpenUrl = onElicitationOpenUrl,
        )
    }

    if (showConnectionStatusDialog) {
        ConnectionDiagnosticsDialog(
            connectionState = connectionState,
            diagnostics = diagnostics,
            totalTokens = usage?.totalTokens,
            contextWindowTokens = usage?.contextWindowTokens,
            costAmount = usage?.costAmount,
            costCurrency = usage?.costCurrency,
            onDismiss = onDismissConnectionStatus,
        )
    }

    if (showCommandsDialog) {
        CommandsSheet(
            commands = commands,
            serverId = serverId,
            recentSelectionStore = recentSelectionStore,
            onDismiss = onDismissCommands,
            onCommandClick = onCommandClick,
        )
    }
}

/**
 * Renders the main chat content area, switching between loading, error, and list states.
 */
@Composable
internal fun ChatScreenBody(
    state: ChatState,
    scroll: ChatScrollHandle,
    appBarScrollConnection: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onRetryLoad: () -> Unit,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)? = null,
) {
    when {
        state.isLoading && state.messages.isEmpty() -> {
            ChatLoadingSkeleton(listTopPadding = listTopPadding)
        }

        state.error != null && state.messages.isEmpty() -> {
            ChatLoadError(
                message = state.error,
                onRetry = onRetryLoad,
            )
        }

        else -> {
            ChatMessageList(
                state = state,
                scroll = scroll,
                appBarScrollConnection = appBarScrollConnection,
                renderedLastMessageId = renderedLastMessageId,
                listTopPadding = listTopPadding,
                listBottomPadding = listBottomPadding,
                onThoughtClick = onThoughtClick,
                onToolCallClick = onToolCallClick,
                onRetryMessage = onRetryMessage,
            )
        }
    }
}

/**
 * Shimmer skeleton shown while the first snapshot loads with an empty cache.
 * Mirrors the message list (user bubbles right, assistant bubbles left) so the
 * layout doesn't jump when real content paints. Only reachable on a true
 * cold open — cached transcripts render immediately instead.
 *
 * The pattern repeats until it covers the viewport and dissolves toward the
 * bottom, so the screen reads as "transcript on the way" rather than four
 * bubbles floating above empty space.
 */
@Composable
private fun ChatLoadingSkeleton(
    listTopPadding: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        // Sweep the full measured width: a fixed pixel range leaves wide
        // screens half-static.
        val sweepWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val lineBrush = rememberSkeletonShimmerBrush(sweepWidthPx)
        val contentTopPadding = listTopPadding + 24.dp
        val passes =
            ceil((maxHeight - contentTopPadding) / SKELETON_PATTERN_HEIGHT)
                .toInt()
                .coerceIn(1, SKELETON_MAX_PASSES)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = contentTopPadding, end = 16.dp)
                    // Offscreen so the erase below cuts only the skeleton, not the
                    // chat surface drawn behind it.
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Transparent, Color.Black),
                                ),
                            blendMode = BlendMode.DstOut,
                        )
                    },
            verticalArrangement = Arrangement.spacedBy(SKELETON_GROUP_GAP),
        ) {
            repeat(passes) {
                SKELETON_PATTERN.forEach { message ->
                    SkeletonBlock(
                        brush = lineBrush,
                        alignment = message.alignment,
                        widthFraction = message.widthFraction,
                        lineFractions = message.lineFractions,
                    )
                }
            }
        }
    }
}

/**
 * One skeleton message: bare shimmer text lines, right-aligned for user
 * messages and full-width for assistant ones.
 */
@Composable
private fun ColumnScope.SkeletonBlock(
    brush: Brush,
    alignment: Alignment.Horizontal,
    widthFraction: Float,
    lineFractions: List<Float>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(widthFraction)
                .align(alignment),
        verticalArrangement = Arrangement.spacedBy(SKELETON_LINE_GAP),
    ) {
        lineFractions.forEach { fraction ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(SKELETON_LINE_HEIGHT)
                        .background(brush, CircleShape),
            )
        }
    }
}

/**
 * Highlight band sweeping across the skeleton's full width, matching the
 * streaming-text shimmer's 1400ms rhythm so the skeleton hands off to
 * content seamlessly.
 */
@Composable
private fun rememberSkeletonShimmerBrush(sweepWidthPx: Float): Brush {
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val shimmer = rememberInfiniteTransition(label = "ChatSkeletonShimmer")
    val offset by shimmer.animateFloat(
        initialValue = -sweepWidthPx,
        targetValue = sweepWidthPx * 2,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SKELETON_SHIMMER_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "ChatSkeletonShimmerOffset",
    )
    return Brush.linearGradient(
        colors =
            listOf(
                baseColor.copy(alpha = 0.55f),
                baseColor,
                baseColor.copy(alpha = 0.55f),
            ),
        start = Offset(offset - SKELETON_SHIMMER_BAND_PX, 0f),
        end = Offset(offset, 0f),
    )
}

@Preview
@Composable
private fun ChatLoadingSkeletonPreview() {
    Surface {
        ChatLoadingSkeleton(listTopPadding = 0.dp)
    }
}

/**
 * Full-screen error placeholder shown when the session fails before any messages load.
 */
@Composable
private fun ChatLoadError(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ErrorStateCard(
            headline = stringResource(R.string.chat_load_error_title),
            body = localizeChatError(LocalContext.current, message),
            icon = Icons.Rounded.CloudOff,
            medallionContainer = MaterialTheme.colorScheme.errorContainer,
            medallionContent = MaterialTheme.colorScheme.onErrorContainer,
            medallionShape = MaterialShapes.VerySunny.toShape(),
            ctaLabel = stringResource(R.string.chat_retry),
            onCta = onRetry,
        )
    }
}

@Composable
private fun ChatMessageList(
    state: ChatState,
    scroll: ChatScrollHandle,
    appBarScrollConnection: NestedScrollConnection,
    renderedLastMessageId: String?,
    listTopPadding: Dp,
    listBottomPadding: Dp,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)? = null,
) {
    val listState = scroll.listState
    val allMessages = state.messages
    val itemKey: (ChatMessage) -> String = { state.listKeys[it.id] ?: it.id }
    val contentTop = listTopPadding + 8.dp
    val window = rememberMessageWindow(state, allMessages, listState, itemKey)
    val windowed = window.messages
    // The rows above the transcript shift every message's index; the pinned prompt needs its own
    // index to find its slot and to scroll its turn back into view.
    val leadingRows = (if (state.resumedSession) 1 else 0) + (if (window.hasOlder) 1 else 0)
    val userRows =
        remember(windowed, leadingRows) {
            windowed.indices
                .filter { windowed[it].role == ChatMessage.Role.USER && windowed[it].content.isNotBlank() }
                .map { leadingRows + it }
        }
    val fadePx = with(LocalDensity.current) { pinnedPromptFadeDistance.roundToPx() }
    val runway = rememberRunway(scroll, windowed, itemKey, listBottomPadding)
    val messageRow: @Composable (ChatMessage) -> Unit = { message ->
        ChatMessageItem(
            message = message,
            state = state,
            renderedLastMessageId = renderedLastMessageId,
            onThoughtClick = onThoughtClick,
            onToolCallClick = onToolCallClick,
            onRetryMessage = onRetryMessage,
            modifier = Modifier.runwayRow(scroll, runway, itemKey(message)),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalActiveRails provides scroll.activeRails) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .nestedScroll(scroll.userScrollDetector)
                        // The app bar is driven by this list alone. Attaching the app bar's
                        // connection at the Scaffold instead would also capture the scroll of any
                        // other Scaffold descendant — notably the composer's text field, which
                        // floats over this list as a sibling — and collapse the bar instead of
                        // scrolling the text.
                        .nestedScroll(appBarScrollConnection),
                contentPadding = PaddingValues(start = 16.dp, top = contentTop, end = 16.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.resumedSession) {
                    item(key = "__resumed_session_notice") {
                        ResumedSessionNotice()
                    }
                }
                if (window.hasOlder) {
                    item(key = "__load_older") {
                        OutlinedButton(
                            onClick = window.loadOlder,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                        ) {
                            Text(text = stringResource(R.string.chat_load_earlier))
                        }
                    }
                }
                // Rows differ in structure (user, plain assistant, segmented assistant), so each
                // carries a contentType: without one Compose cannot tell a like-for-like slot from a
                // different one and re-does the work on every markdown emit. Coarse on purpose — the
                // value only has to match for rows that can share a slot.
                items(
                    items = windowed,
                    key = itemKey,
                    contentType = {
                        when {
                            it.role == ChatMessage.Role.USER -> 0
                            it.segments.isEmpty() -> 1
                            else -> 2
                        }
                    },
                ) { message ->
                    messageRow(message)
                }
                item(key = BOTTOM_SPACER_KEY) {
                    RunwaySpacer(scroll, runway)
                }
            }
        }
        // Keyed so the pinned chip, once displaced, carries on as the same node instead of being
        // rebuilt mid-push.
        PinnedPromptOverlays(userRows, leadingRows, windowed, scroll, contentTop, fadePx)
        AnimatedVisibility(
            visible = state.isNewChat && allMessages.isEmpty() && state.pendingMessages.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ChatEmptyHero(iconUrl = state.iconUrl)
        }
    }
}

/**
 * Perforated seam at the top of a transcript reattached without history replay
 * (`session/resume`). A rewind marker on a tear line reads as "this conversation
 * continues off-screen", so an empty transcript does not look like a fresh chat.
 */
@Composable
private fun ResumedSessionNotice() {
    val seamColor = MaterialTheme.colorScheme.outlineVariant
    // Dashed rule drawn inline (a named helper would push this file past detekt's
    // 25-function budget); `RowScope` receiver so both sides share one weight.
    val seam: RowScope.() -> Modifier = {
        Modifier
            .weight(1f)
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = seamColor,
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = seam())
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.chat_resumed_session),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = seam())
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    state: ChatState,
    renderedLastMessageId: String?,
    onThoughtClick: (String) -> Unit,
    onToolCallClick: (String) -> Unit,
    onRetryMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Keyed by the same keys the renderer draws from (run keys, not segment ids), so the document
    // looked up here is the one [MessageBubble] will ask for.
    val messageMarkdown =
        remember(message, state.markdownDocuments) {
            if (message.role != ChatMessage.Role.ASSISTANT) {
                persistentMapOf<String, MarkdownRenderedDocument>()
            } else {
                buildMap {
                    message.segments.displayBlocks().forEach { block ->
                        state.markdownDocuments[block.key]?.let { put(block.key, it) }
                    }
                    state.markdownDocuments[message.id]?.let { put(message.id, it) }
                }.toPersistentMap()
            }
        }
    val isLast = message.id == renderedLastMessageId
    CompositionLocalProvider(
        LocalWorkingTreeChanges provides if (isLast) state.turnWorkingTree() else emptyList(),
    ) {
        MessageBubble(
            message = message,
            markdownDocuments = messageMarkdown,
            showStreamingIndicator = message.isStreaming && isLast,
            onThoughtClick = onThoughtClick,
            onToolCallClick = onToolCallClick,
            onRetryMessage = onRetryMessage,
            modifier = modifier,
        )
    }
}

private const val AGENT_TEXT_MAX_LINES = 3

internal fun List<ChatMessage>.toolCallForSegment(segmentId: String?): ToolCallDisplay? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        message.segments
            .asReversed()
            .firstOrNull { it.id == targetId }
            ?.toolCall
    }
}

internal fun List<ChatMessage>.thoughtForSegment(segmentId: String?): String? {
    val targetId = segmentId ?: return null
    return asReversed().firstNotNullOfOrNull { message ->
        // Resolved to the whole run, not the tapped chunk. A streamed thought arrives one segment
        // per chunk, so matching on the segment id alone returned a single fragment and the sheet
        // opened on what looked like broken content.
        message.segments
            .thoughtRuns()
            .firstOrNull { run -> run.segments.any { it.id == targetId } }
            ?.text
            ?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun ToolCallDetailsSheet(
    toolCall: ToolCallDisplay,
    onDismiss: () -> Unit,
) {
    // A diff can outrun the screen, and the git fallback that fills one lands after the sheet
    // opens, so the sheet's content grows under the reader. PartiallyExpanded is anchored at half
    // the screen, so a sheet that opens on a still-empty body settles there once the diff arrives:
    // leaving it out leaves only positions the growing content cannot drop the sheet to.
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
                    // Key events reach the active focus target and its ancestors only, and this
                    // sheet's content is text with nothing focusable in it.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToolCallDetailsHeader(toolCall = toolCall)
            ToolCallDetailsBody(toolCall = toolCall)
        }
    }
}

@Composable
private fun ToolCallDetailsHeader(toolCall: ToolCallDisplay) {
    val defaultToolCallLabel = stringResource(R.string.chat_tool_call)
    val toolCallSheetTitle = toolCall.titleWithFile().ifBlank { defaultToolCallLabel }
    // An edit's diff and a read's slice both show only part of the file.
    val openablePath = toolCall.filePath()?.takeIf { toolCall.kind == ToolKind.EDIT || toolCall.kind == ToolKind.READ }
    val openFile = LocalFileOpener.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = toolCallSheetTitle,
                style = MaterialTheme.typography.bodyLarge,
                // Same reason as the permission sheet: an agent can dump an entire transcript
                // into the tool title, so cap it rather than let it push the body off-screen.
                maxLines = AGENT_TEXT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (openablePath != null && openFile != null) {
                val label = stringResource(R.string.chat_file_open_full)
                GitTooltip(label) {
                    IconButton(
                        onClick = { openFile(listOf(openablePath)) },
                        modifier = Modifier.size(32.dp).handCursor(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        toolCall.kind?.let { kind ->
            Text(
                text = toolKindLabel(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
        toolCall.status?.let { status ->
            Text(
                text = toolCallStatusLabel(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCallDetailsBody(toolCall: ToolCallDisplay) {
    if (!toolCall.permissionOptions.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.chat_awaiting_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (toolCall.kind == ToolKind.EXECUTE) {
        val rawInput = toolCall.rawInput
        val displayText = rawInput?.toString()
        if (!displayText.isNullOrBlank()) {
            ContentBlockRenderer(
                block = ContentBlock.Text(displayText),
            )
        }
    }

    val reported = toolCall.content.orEmpty()
    val gitDiffs = toolCall.gitFallbackDiffs()
    // No reported diff: git's, when gitFallbackDiffs has one, else the one rebuilt from the call's input.
    val content =
        if (reported.any { it is ToolCallContent.Diff }) reported else reported + gitDiffs.ifEmpty { toolCall.diffs() }
    if (content.isNotEmpty()) {
        content.forEach { tc ->
            when (tc) {
                is ToolCallContent.Content -> ContentBlockRenderer(tc.content)
                is ToolCallContent.Diff -> {
                    // Nested inside the sheet's vertical scroll column,
                    // so render rows eagerly without an inner lazy list.
                    DiffRenderer(tc, scrollable = false)
                }

                is ToolCallContent.Terminal -> TerminalRenderer(tc)
            }
        }
    } else {
        val rawOutput = toolCall.rawOutput
        if (rawOutput != null) {
            ContentBlockRenderer(
                block = ContentBlock.Text(rawOutput.toString()),
            )
        } else {
            Text(
                text = stringResource(R.string.chat_no_tool_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThoughtDetailsSheet(
    thought: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    // Its own engine, fed only the tail a live thought grows by, as the transcript's runs are.
    val markdown = remember { IncrementalMarkdown() }
    val document = remember(thought) { markdown.update(thought).let { markdown.document } }
    val scroll = rememberScrollState()
    // Growing while open means the thought is live, so the sheet follows it down; settled
    // reasoning never grows and stays at the top. Scrolling up stops the follow, and scrolling
    // back to the end resumes it.
    val openedLength = remember { thought.length }
    var follow by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(thought.length) { if (thought.length > openedLength && !touched) follow = true }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.drop(1).filter { !it }.collect {
            touched = true
            follow = scroll.value >= scroll.maxValue
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { if (follow) scroll.maxValue else null }.filterNotNull().collect { scroll.scrollTo(it) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .verticalScroll(scroll)
                    // Key events reach the active focus target and its ancestors only, and this
                    // sheet's content is text with nothing focusable in it.
                    .focusRequester(escapeFocus)
                    .focusTarget()
                    .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            RenderedMarkdown(text = thought, document = document)
        }
    }
}

/**
 * Summary/list body of the git status sheet: branch + ahead/behind header,
 * aggregate line-total card, and the per-file list. Scrolling is contained to
 * the file list so the sheet header stays pinned.
 */
@Composable
private fun CommandsSheet(
    commands: List<ChatCommand>,
    serverId: String,
    recentSelectionStore: RecentSelectionStore,
    onCommandClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Trailing colon is required by clearByPrefix (startsWith check).
    // Without it, serverId "abc" would also wipe recents for serverId "abcd".
    val storageKey = "commands:$serverId:"
    val enableRecents = commands.size >= 10
    val recentNames by recentSelectionStore
        .getRecentSelections(storageKey)
        .collectAsState(initial = emptyList())
    val recentItems =
        remember(recentNames, commands, enableRecents) {
            if (!enableRecents) {
                emptyList()
            } else {
                recentNames.mapNotNull { name ->
                    commands.find { it.name == name }?.let { cmd ->
                        PickerItem(
                            id = cmd.name,
                            label = cmd.name,
                            value = cmd.name,
                            description = cmd.description,
                        )
                    }
                }
            }
        }
    PickerSheet(
        title = stringResource(R.string.chat_commands_sheet_title),
        items =
            commands.map { cmd ->
                PickerItem(
                    id = cmd.name,
                    label = cmd.name,
                    value = cmd.name,
                    description = cmd.description,
                )
            },
        recentItems = recentItems,
        onItemClick = { value ->
            onCommandClick(value)
            if (enableRecents) {
                scope.launch { recentSelectionStore.addSelection(storageKey, value) }
            }
        },
        onDismiss = onDismiss,
        emptyText = stringResource(R.string.chat_commands_empty),
        noResultsText = stringResource(R.string.chat_commands_no_results),
        searchPlaceholder = stringResource(R.string.chat_commands_search_hint),
    )
}
