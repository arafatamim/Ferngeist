package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ui.localActiveRails
import com.tamimarafat.ferngeist.feature.chat.ui.transcript.TranscriptRows
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * The transcript's scroll model. Streaming text never moves the list: a top-anchored list holds
 * still while content grows, and a reply that runs past the viewport goes on below it, behind the
 * jump button. The list moves only to open a chat at its end, to glide a sent prompt up, to keep
 * the end in view while the keyboard or composer moves (if the reader is at the end), for the jump
 * buttons, and for a live tool rail: while one is open the end is followed, and once the last one
 * closes the list stays where following stopped. A reader's scroll ends that follow.
 * "Following" just means sitting at the end. The jump-to-bottom button
 * also pins the end through the live turn and any remaining follow ease, streaming text included, as does
 * the reader scrolling down to the end mid-turn; scrolling away unpins. See [ScrollPolicy] for the mode rules.
 *
 * The runway: a sent prompt glides to the top of the viewport, and the rest of the viewport below
 * it is reserved, so the reply streams into still space. The reserve is a strut in the turn's own
 * item, see [com.tamimarafat.ferngeist.feature.chat.ui.transcript.TurnItem], resolved in the same
 * measure as the turn's growth. The glide stops with the prompt at the top however far the reply
 * has grown. The space stays as scroll room until the next send.
 */
internal class ChatScrollHandle(
    val listState: LazyListState,
) {
    /**
     * Owns the sent prompt's glide, reader release, and mid-turn end pin. A reader's scroll
     * makes the turn's rails theirs to watch; a jump ease or pending restore owns the list meanwhile.
     */
    private var policy by mutableStateOf(ScrollPolicy())
    val following: Boolean by derivedStateOf { policy.following }

    /** The prompt holding the runway, by message id. */
    var runwayMessageId by mutableStateOf<String?>(null)
        private set

    /** Open, live tool rails; see [localActiveRails]. */
    val activeRails = mutableIntStateOf(0)

    /** The runway prompt's list key, set from composition; see [rememberRunway]. */
    internal var runwayPromptKey: Any? = null

    private var transcriptRows: TranscriptRows? by mutableStateOf(null)
    internal var rows: TranscriptRows?
        get() = transcriptRows
        set(value) {
            val opening = transcriptRows?.turns.isNullOrEmpty()
            // The newest message, not the newest turn: a reply can land in the last turn. Loading
            // older leaves it unchanged, so that keeps its anchor.
            val appended = value?.lastMessageId != transcriptRows?.lastMessageId
            transcriptRows = value
            if (appended && policy.followsAppend(opening, live)) listState.requestScrollToItem(endIndexHint)
        }

    internal var restorePending: Boolean
        get() = policy.restorePending
        set(pending) {
            policy = policy.on(ScrollEvent.RestorePending(pending))
        }

    /**
     * A restore applies once: every snapshot after it is this handle's own, mirrored back through
     * the view model, and restoring one would yank the reader to where they were a second ago.
     */
    internal var restoreConsumed = false

    /** A reply streaming, or text still revealing: its new rows are not followed either. */
    internal var live by mutableStateOf(false)
    internal var relatchPx by mutableFloatStateOf(0f)

    /**
     * At or past the last index; the measure clamps it onto the bottom spacer.
     * A position request, not a scroll delta: it is applied in this frame's measure, after the
     * content grew, so the end never trails a frame behind.
     */
    private val endIndexHint: Int get() = rows?.endIndex ?: 0
    private var lastUserId: String? = null
    private var userIdAtSend: String? = null
    private var awaitingTurn = false
    private var bottomPaddingPx = -1
    private val distanceToEnd: Float get() = listState.distanceToBottom(bottomPaddingPx.takeIf { it >= 0 })
    private val endPending: Boolean get() = distanceToEnd > PIN_EPSILON_PX

    val userScrollDetector: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Programmatic scrolls never come through here, so any user input is the reader's.
                if (source == NestedScrollSource.UserInput && abs(available.y) > PIN_EPSILON_PX) {
                    policy = policy.on(ScrollEvent.Released(towardEnd = available.y < 0))
                }
                return Offset.Zero
            }
        }

    val showJumpToBottom: State<Boolean> =
        derivedStateOf {
            policy.allowsJumpButton && distanceToEnd > relatchPx
        }

    /** Called from composition, ahead of the measure that lays [messages] out. */
    fun onMessages(messages: List<ChatMessage>) {
        lastUserId = messages.lastOrNull { it.role == ChatMessage.Role.USER }?.id
        if (awaitingTurn && lastUserId != userIdAtSend) {
            awaitingTurn = false
            runwayMessageId = lastUserId
        }
    }

    /**
     * Called from composition with the list's bottom padding. The keyboard and the composer move it
     * frame by frame; at the end, each move is pinned in its own measure so the last line stays above
     * them.
     */
    fun onBottomPadding(px: Int) {
        if (px != bottomPaddingPx && policy.holdsEnd) listState.requestScrollToItem(endIndexHint)
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
        policy = policy.on(ScrollEvent.Sent)
    }

    suspend fun jumpToTop() {
        policy = policy.on(ScrollEvent.Released(towardEnd = false))
        ease { listState.easeScrollToTop() }
    }

    suspend fun jumpToBottom() {
        ease { listState.easeScrollToBottom() }
        policy = policy.on(ScrollEvent.JumpedToBottom(live))
    }

    suspend fun jumpToItem(index: Int) {
        policy = policy.on(ScrollEvent.Released(towardEnd = false))
        ease { listState.animateScrollToItem(index, 0) }
    }

    /** Runs the glide after each send, and follows live tool rails. */
    suspend fun follow() {
        while (true) {
            snapshotFlow {
                policy.glides ||
                    policy.followsRail(activeRails.intValue) ||
                    policy.followsEnd(live, endPending)
            }.first { it }
            if (policy.gliding) glideUp() else followRail()
        }
    }

    /**
     * Follows the end while a rail runs or the end is pinned, leaving the viewport in place
     * once the last rail closes.
     */
    private suspend fun followRail() {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (
                (!policy.followsRail(activeRails.intValue) && !policy.followsEnd(live, endPending)) ||
                policy.gliding
            ) {
                break
            }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            // Ease both new rows and ordinary growth: pinning near the end steps by each revealed line.
            listState.glideFrame(distanceToEnd, dt, mayJump = false, endIndexHint = endIndexHint)
        }
        policy = policy.on(ScrollEvent.FollowEnded(live))
    }

    /** Keeps [following] true at rest near the end, unless a reader's bottom touch pins live growth. */
    suspend fun relatch() {
        snapshotFlow {
            val ours = policy.owned(live, activeRails.intValue, endPending)
            if (ours || listState.isScrollInProgress) {
                null
            } else {
                distanceToEnd <= relatchPx
            }
        }.filterNotNull().collect {
            // Only a reader's scroll pins: a sent prompt's runway also ends at the end, and must stay still.
            policy = policy.on(ScrollEvent.Rested(atEnd = it, live = live))
        }
    }

    suspend fun watchEnd() {
        snapshotFlow {
            listState.isScrollInProgress && distanceToEnd <= PIN_EPSILON_PX
        }.collect { atEnd ->
            if (atEnd) policy = policy.on(ScrollEvent.ReachedEnd)
        }
    }

    private suspend fun glideUp() {
        var jumped = false
        var last = withFrameNanos { it }
        while (policy.glides) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            val distance = glideDistance(listState, runwayPromptKey, awaitingTurn)
            jumped = listState.glideFrame(distance, dt, mayJump = !jumped, endIndexHint = endIndexHint) || jumped
            if (!awaitingTurn && distance <= PIN_EPSILON_PX) policy = policy.on(ScrollEvent.GlideArrived)
        }
    }

    private suspend fun ease(animation: suspend () -> Unit) {
        policy = policy.on(ScrollEvent.Easing(active = true))
        try {
            animation()
        } finally {
            policy = policy.on(ScrollEvent.Easing(active = false))
        }
    }

    /**
     * Two-pass: the first scrollToItem positions the index, but layout may not have settled yet.
     * [offset] is read per pass: it is re-anchored to the turn top when the viewport width changed
     * (fold, rotate, multi-window), because item heights re-flowed.
     */
    internal suspend fun restore(
        index: Int,
        offset: () -> Int,
        isFollowing: Boolean,
    ) {
        try {
            repeat(2) {
                withFrameNanos { }
                listState.scrollToItem(index, offset())
            }
            policy = policy.on(ScrollEvent.Restored(isFollowing))
        } finally {
            // A drag can cancel the restore's scroll; left pending, following could never resume.
            restorePending = false
            restoreConsumed = true
        }
    }

    /** Loading older retains the turn being read, rather than the load-earlier control above it. */
    internal fun holdWindowAnchor(
        rows: TranscriptRows,
        messageId: String,
        offset: Int,
    ) {
        rows.rowOfMessage(messageId)?.let { listState.requestScrollToItem(it, -offset) }
    }
}
