package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** Duration of the shortest jump-button scroll; longer ones scale up to [EASE_MAX_MS]. */
internal const val EASE_MIN_MS = 650

/** Duration of a jump that travels the full [EASE_MAX_VIEWPORTS]. */
private const val EASE_MAX_MS = 1000

/**
 * Farthest a jump eases, in viewports. A farther target is first placed this far away in one
 * frame, so the ease never has to compose the whole transcript.
 */
private const val EASE_MAX_VIEWPORTS = 2.5f

/** Duration of a short settle onto the end, for a jump chasing a stream. */
private const val SETTLE_MS = 220

/** Most chase eases a jump to the bottom runs after its main ease. */
private const val CHASE_PASSES = 3

/** Within this of the end counts as at the end: following, and no jump button. */
private val relatchDistance = 48.dp

/** Share of the remaining distance a glide covers per 60 fps frame. */
private const val GLIDE_PER_FRAME = 0.15f

private const val PIN_EPSILON_PX = 0.5f
private const val NANOS_PER_SECOND = 1e9f
private const val MAX_FRAME_SECONDS = 1f / 30f
private const val FRAMES_PER_SECOND = 60f

/**
 * The transcript's scroll model. Streaming text never moves the list: a top-anchored list holds
 * still while content grows, and a reply that runs past the viewport goes on below it, behind the
 * jump button. The list moves only to open a chat at its end, to glide a sent prompt up, to keep
 * the end in view while the keyboard or composer moves (if the reader is at the end), for the jump
 * buttons, and for a live tool rail: while one is open the end is followed, and once the last one
 * closes the list springs back to where the follow began. A reader's scroll ends that follow
 * without the spring back. "Following" just means sitting at the end.
 *
 * The runway: a sent prompt glides to the top of the viewport, and the rest of the viewport below
 * it is reserved, so the reply streams into still space. The reserve is a minimum height on the
 * reply's row (on the bottom spacer until the reply has a row), so it is resolved in the same
 * measure as the reply's growth. The glide stops with the prompt at the top however far the reply
 * has grown. The space stays as scroll room until the next send.
 */
internal class ChatScrollHandle(
    val listState: LazyListState,
) {
    var following by mutableStateOf(true)
        private set

    /** The prompt holding the runway, by message id. */
    var runwayMessageId by mutableStateOf<String?>(null)
        private set

    /** Easing a sent prompt up to the top. */
    private var gliding by mutableStateOf(false)

    /** Open, live tool rails; see [localActiveRails]. */
    val activeRails = mutableIntStateOf(0)

    /** The reader scrolled since the last send; the turn's rails are then theirs to watch. */
    private var readerScrolled by mutableStateOf(false)

    /** The runway prompt's list key, set from composition; see [rememberRunway]. */
    internal var runwayPromptKey: Any? = null

    /** A jump button's ease owns the list; nothing else scrolls meanwhile. */
    private var easing by mutableStateOf(false)
    internal var restorePending by mutableStateOf(false)

    /**
     * A restore applies once: every snapshot after it is this handle's own, mirrored back through
     * the view model, and restoring one would yank the reader to where they were a second ago.
     */
    internal var restoreConsumed = false

    /** A reply streaming, or text still revealing: its new rows are not followed either. */
    internal var live by mutableStateOf(false)
    internal var relatchPx by mutableFloatStateOf(0f)

    /** At or past the last index; the measure clamps it onto the bottom spacer. */
    private var endIndexHint = 0
    private var lastUserId: String? = null
    private var userIdAtSend: String? = null
    private var awaitingTurn = false
    private var bottomPaddingPx = -1

    /** Following, and nothing else owns the scroll. */
    private val inCharge: Boolean get() = following && !easing && !restorePending

    val userScrollDetector: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Programmatic scrolls never come through here, so any user input is the reader's.
                if (source == NestedScrollSource.UserInput && abs(available.y) > PIN_EPSILON_PX) release()
                return Offset.Zero
            }
        }

    val showJumpToBottom: State<Boolean> =
        derivedStateOf {
            !gliding &&
                !easing &&
                listState.distanceToBottom() > relatchPx
        }

    /** Called from composition, ahead of the measure that lays [messages] out. */
    fun onMessages(messages: List<ChatMessage>) {
        val grew = messages.size + 2 > endIndexHint
        // Rows above the transcript and the bottom spacer add at most three past the messages.
        endIndexHint = messages.size + 2
        lastUserId = messages.lastOrNull { it.role == ChatMessage.Role.USER }?.id
        if (awaitingTurn && lastUserId != userIdAtSend) {
            awaitingTurn = false
            runwayMessageId = lastUserId
        }
        // The first messages: pinned in the same frame they are measured, or the list shows its top
        // for a frame first. A streaming reply's rows are left to grow below.
        val settled = !gliding && !live
        if (grew && inCharge && settled) pin()
    }

    /**
     * Called from composition with the list's bottom padding. The keyboard and the composer move it
     * frame by frame; at the end, each move is pinned in its own measure so the last line stays above
     * them.
     */
    fun onBottomPadding(px: Int) {
        if (px != bottomPaddingPx && inCharge && !gliding) pin()
        bottomPaddingPx = px
    }

    /**
     * The prompt the runway sits under, from the frame it appears: [runwayMessageId] is only set after
     * that frame, and on a chat's first send it is still null then, so the glide would find nothing to
     * do and the reserve would land a frame later, below the viewport.
     */
    fun runwayPrompt(messages: List<ChatMessage>): ChatMessage? {
        val latest = messages.lastOrNull { it.role == ChatMessage.Role.USER } ?: return null
        return latest.takeIf { runwayMessageId != null || (awaitingTurn && it.id != userIdAtSend) }
    }

    fun onSendMessage() {
        userIdAtSend = lastUserId
        awaitingTurn = true
        readerScrolled = false
        following = true
        gliding = true
    }

    suspend fun jumpToTop() {
        release()
        ease { listState.easeScrollToTop() }
    }

    suspend fun jumpToBottom() {
        ease { listState.easeScrollToBottom() }
        following = true
    }

    suspend fun jumpToItem(index: Int) {
        release()
        ease { listState.animateScrollToItem(index, 0) }
    }

    /** Runs the glide after each send, and follows live tool rails. */
    suspend fun follow() {
        while (true) {
            snapshotFlow { inCharge && gliding || followsRail }.first { it }
            if (gliding) glideUp() else followRail()
        }
    }

    private val followsRail: Boolean
        get() = activeRails.intValue > 0 && !readerScrolled && !easing && !restorePending

    /** Follows the end while a rail runs, then springs back to where it started once the last one closes. */
    private suspend fun followRail() {
        val startIndex = listState.firstVisibleItemIndex
        val startOffset = listState.firstVisibleItemScrollOffset
        var last = withFrameNanos { it }
        while (followsRail && !gliding) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            val distance = listState.distanceToBottom()
            // A row landing at once eases in; ordinary growth is pinned in its own measure.
            when {
                distance > relatchPx -> listState.glideFrame(distance, dt, mayJump = false, endIndexHint = endIndexHint)
                distance > PIN_EPSILON_PX -> pin()
            }
        }
        if (activeRails.intValue == 0 && !readerScrolled && !gliding) {
            ease { listState.animateScrollToItem(startIndex, startOffset) }
        }
    }

    /** Keeps [following] true exactly while the reader rests at the end, so content growing away ends it. */
    suspend fun relatch() {
        snapshotFlow {
            val ours = gliding || easing || restorePending || followsRail
            if (ours || listState.isScrollInProgress) {
                null
            } else {
                listState.distanceToBottom() <= relatchPx
            }
        }.filterNotNull().collect { following = it }
    }

    private suspend fun glideUp() {
        var jumped = false
        var last = withFrameNanos { it }
        while (inCharge && gliding) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            val distance = glideDistance(listState, runwayPromptKey, awaitingTurn)
            jumped = listState.glideFrame(distance, dt, mayJump = !jumped, endIndexHint = endIndexHint) || jumped
            if (!awaitingTurn && distance <= PIN_EPSILON_PX) gliding = false
        }
    }

    /**
     * A position request, not a scroll delta: it is applied in this frame's measure, after the
     * content grew, so the end never trails a frame behind.
     */
    private fun pin() = listState.requestScrollToItem(endIndexHint)

    /** One glide step; true when it jumped instead of easing. */

    private suspend fun ease(animation: suspend () -> Unit) {
        easing = true
        try {
            animation()
        } finally {
            easing = false
        }
    }

    private fun release() {
        readerScrolled = true
        following = false
        gliding = false
    }

    internal fun markRestored(isFollowing: Boolean) {
        following = isFollowing
        restorePending = false
    }
}

/**
 * Height for a row at [rowOffset] that, with [reservedPx] below it, ends the list exactly at
 * [viewportEndOffset] while the prompt at [promptOffset] sits at the viewport's top.
 */
internal fun runwayPx(
    promptOffset: Int,
    rowOffset: Int,
    viewportEndOffset: Int,
    reservedPx: Int,
): Int = maxOf(0, viewportEndOffset - (rowOffset - promptOffset) - reservedPx)

/** [ChatScrollHandle.runwayPx], re-read only when the layout above the row moves. */
@Composable
internal fun ChatScrollHandle.rememberRunwayPx(
    promptKey: Any?,
    rowKey: Any?,
    reservedPx: Int,
): Int {
    val px by remember(promptKey, rowKey, reservedPx) { derivedStateOf { runwayPx(promptKey, rowKey, reservedPx) } }
    return px
}

/**
 * Creates the transcript's [ChatScrollHandle] and runs its follow loop, restore and persistence.
 *
 * @param restoredScrollSnapshot snapshot to restore from (null = fresh session)
 * @param restoreReady true when messages are loaded and ready for scroll restoration
 */
@Composable
internal fun rememberChatScrollState(
    sessionId: String,
    renderedMessages: List<ChatMessage>,
    activelyStreaming: Boolean,
    restoredScrollSnapshot: ChatScrollSnapshot?,
    restoreReady: Boolean,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
): ChatScrollHandle {
    val handle = remember(sessionId) { ChatScrollHandle(LazyListState()) }
    val listState = handle.listState
    val density = LocalDensity.current
    val restoreKey = restoredScrollSnapshot?.savedAt
    // Applied before the SideEffect below, so the first onMessages already sees a pending restore.
    DisposableEffect(sessionId, restoreKey) {
        handle.restorePending = restoredScrollSnapshot != null && !handle.restoreConsumed
        onDispose {}
    }
    SideEffect {
        handle.onMessages(renderedMessages)
        handle.live = activelyStreaming
        handle.relatchPx = with(density) { relatchDistance.toPx() }
    }
    LaunchedEffect(handle) { handle.follow() }
    LaunchedEffect(handle) { handle.relatch() }
    // The viewport is measured in pixels; snapshots record it in dp so the value stays
    // comparable across a fold or rotate (density can change with the display).
    val viewportWidthDp = {
        with(density) {
            listState.layoutInfo.viewportSize.width
                .toDp()
                .value
                .toInt()
        }
    }
    LaunchedEffect(handle, renderedMessages) {
        observePersistence(handle, renderedMessages, viewportWidthDp, onScrollSnapshotChanged)
    }
    LaunchedEffect(handle, restoreReady, restoreKey) {
        if (!restoreReady || !handle.restorePending || renderedMessages.isEmpty()) return@LaunchedEffect
        val snapshot = restoredScrollSnapshot ?: return@LaunchedEffect
        val restoredIndex =
            snapshot.anchorMessageId
                ?.let { anchorId -> renderedMessages.indexOfFirst { it.id == anchorId } }
                ?.takeIf { it >= 0 }
                ?: snapshot.firstVisibleItemIndex.coerceIn(0, renderedMessages.lastIndex)
        // Two-pass: the first scrollToItem positions the index, but layout may not have settled
        // yet. The offset is re-anchored to the message top when the viewport width changed (fold,
        // rotate, multi-window), because item heights re-flowed.
        try {
            repeat(2) {
                withFrameNanos { }
                listState.scrollToItem(restoredIndex, snapshot.offsetForRestore(viewportWidthDp()).coerceAtLeast(0))
            }
            handle.markRestored(snapshot.isFollowing)
        } finally {
            // A drag can cancel the restore's scroll; left pending, following could never resume.
            handle.restorePending = false
            handle.restoreConsumed = true
        }
    }
    return handle
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
private suspend fun observePersistence(
    handle: ChatScrollHandle,
    renderedMessages: List<ChatMessage>,
    viewportWidthDp: () -> Int,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
) {
    val listState = handle.listState
    snapshotFlow {
        if (renderedMessages.isEmpty() || handle.restorePending) return@snapshotFlow null
        val idx = listState.firstVisibleItemIndex
        ChatScrollSnapshot(
            anchorMessageId = renderedMessages.getOrNull(idx)?.id,
            firstVisibleItemIndex = idx,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            isFollowing = handle.following,
            savedAt = 0L,
            containerWidthDp = viewportWidthDp(),
        )
    }.distinctUntilChanged()
        .debounce(SCROLL_DEBOUNCE_MS)
        .collect { observation ->
            observation ?: return@collect
            handle.restoreConsumed = true
            onScrollSnapshotChanged(observation.copy(savedAt = System.currentTimeMillis()))
        }
}

private const val SCROLL_DEBOUNCE_MS = 1000L

/**
 * Eases to the top of item 0 along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToTop() {
    val anchor = layoutInfo.visibleItemsInfo.firstOrNull() ?: return
    val estimate = -distanceToTop()
    scrollToItem(0)
    // Where the item that was at the top now sits gives the exact distance travelled; only when
    // it has scrolled out of view is the distance an estimate, and then the start jumps anyway.
    val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
    val distance = landed?.let { (it.offset - anchor.offset).toFloat() } ?: min(estimate, maxEasePx())
    glideOnto(-scrollBy(distance), exact = landed != null)
}

/**
 * Eases to the end of the content along an exact, measured distance. See [glideOnto].
 */
internal suspend fun LazyListState.easeScrollToBottom() {
    val anchor = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    scrollToItem(layoutInfo.totalItemsCount - 1)
    val travel = walkBackTo(anchor)
    glideOnto(travel, exact = layoutInfo.visibleItemsInfo.any { it.index == anchor.index })
    // A reply still streaming grows during the ease, past the distance it set out to cover. Chase
    // the new end in short eases; snapping onto it hopped by every line that streamed meanwhile.
    repeat(CHASE_PASSES) {
        val left = distanceToBottom()
        if (left < 1f) return
        animateScrollBy(left, tween(SETTLE_MS, easing = EaseOutCubic))
    }
}

/**
 * Called with the list stepped back [travel] pixels from its target: eases forward onto it.
 *
 * The step back happens in the same frame as the snap to the target, so when the distance was
 * measured [exact]ly the list visibly never moves before the ease starts — it simply glides from
 * where it was. Easing a known distance is what makes the curve a real curve: an ease that
 * re-estimates an off-screen target every frame keeps stretching as taller items come into view
 * and reads as a linear scroll with a hard stop.
 *
 * A far target cannot be measured without composing everything in between, so the start jumps to
 * [EASE_MAX_VIEWPORTS] away and eases out only — the jump reads as the fast start of the motion.
 */
private suspend fun LazyListState.glideOnto(
    travel: Float,
    exact: Boolean,
) {
    if (travel == 0f) return
    val fraction = (abs(travel) / maxEasePx()).coerceAtMost(1f)
    animateScrollBy(
        value = travel,
        animationSpec =
            tween(
                durationMillis = EASE_MIN_MS + ((EASE_MAX_MS - EASE_MIN_MS) * fraction).toInt(),
                easing = if (exact) EaseInOutCubic else EaseOutCubic,
            ),
    )
}

/**
 * From the end, steps back a viewport at a time until [anchor] is laid out again, then puts it
 * where it was, all in one frame; the pixels stepped are then exact. An average-row estimate of a
 * target one or two viewports up was off by whole rows, and the ease started with that jump. Gives
 * up [EASE_MAX_VIEWPORTS] back. Returns the pixels back from the end.
 */
private suspend fun LazyListState.walkBackTo(anchor: LazyListItemInfo): Float {
    val viewport = layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }.toFloat()
    var travelled = 0f
    while (true) {
        val landed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index }
        if (landed != null) return travelled - scrollBy((landed.offset - anchor.offset).toFloat())
        val step = min(viewport, maxEasePx() - travelled)
        val stepped = if (step > 0f) -scrollBy(-step) else 0f
        if (stepped == 0f) return travelled
        travelled += stepped
    }
}

private fun LazyListState.maxEasePx(): Float =
    layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset } * EASE_MAX_VIEWPORTS

/** Signed pixels from the current position to the top of item 0; negative scrolls up. */
private fun LazyListState.distanceToTop(): Float {
    val info = layoutInfo
    info.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { return it.offset.toFloat() }
    return -(firstVisibleItemIndex * info.averageItemSpan() + firstVisibleItemScrollOffset)
}

/** Signed pixels from the current position to the end of the content; positive scrolls down. */
private fun LazyListState.distanceToBottom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    val itemsBelow = info.totalItemsCount - 1 - last.index
    return itemsBelow * info.averageItemSpan() + (last.offset + last.size - info.viewportEndOffset)
}

private fun LazyListLayoutInfo.averageItemSpan(): Float {
    val items = visibleItemsInfo
    if (items.isEmpty()) return 0f
    return items.sumOf { it.size }.toFloat() / items.size + mainAxisItemSpacing
}

/**
 * Height [rowKey] needs, with [reservedPx] (and the item spacing, if a row follows) below it, to reach from the runway prompt at the top
 * to the viewport's end; 0 when either is not laid out. Only what sits above the row is read
 * from the last layout, and that holds still while the row grows. A row not laid out yet is
 * about to take the bottom spacer's place, so it is measured from there.
 */
internal fun ChatScrollHandle.runwayPx(
    promptKey: Any?,
    rowKey: Any?,
    reservedPx: Int,
): Int {
    val info = listState.layoutInfo
    val items = info.visibleItemsInfo
    val prompt = promptKey?.let { key -> items.firstOrNull { it.key == key } } ?: return 0
    val row =
        items.firstOrNull { it.key == rowKey }
            ?: items.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
            ?: return 0
    val followed = row.key != rowKey || row.index < info.totalItemsCount - 1
    val below = reservedPx + if (followed) info.mainAxisItemSpacing else 0
    return runwayPx(prompt.offset, row.offset, info.viewportEndOffset, below)
}

/**
 * To the end, but no further than the runway prompt reaching the top: a reply already past the
 * viewport stays below it. Until the prompt is in the list the old one still holds the key.
 */
private fun glideDistance(
    listState: LazyListState,
    promptKey: Any?,
    awaitingTurn: Boolean,
): Float {
    val end = listState.distanceToBottom()
    val key = promptKey?.takeIf { !awaitingTurn } ?: return end
    val prompt = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return end
    return minOf(end, prompt.offset.toFloat())
}

private suspend fun LazyListState.glideFrame(
    distance: Float,
    dt: Float,
    mayJump: Boolean,
    endIndexHint: Int,
): Boolean {
    if (distance <= PIN_EPSILON_PX) return false
    try {
        // A far end would compose everything in between: land a few viewports short of it, once.
        // Its distance stays an estimate after landing, so jumping again would never arrive.
        if (mayJump && distance > this.maxEasePx()) {
            this.scrollToItem(endIndexHint)
            this.scrollBy(-this.maxEasePx())
            return true
        }
        // Frame-rate independent ease-out toward a target that may still be moving.
        this.scrollBy(distance * (1f - (1f - GLIDE_PER_FRAME).pow(dt * FRAMES_PER_SECOND)))
    } catch (e: CancellationException) {
        // A drag took the scroll; following has already stopped. Only our own cancellation ends us.
        if (!currentCoroutineContext().isActive) throw e
    }
    return false
}
