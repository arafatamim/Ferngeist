package com.tamimarafat.ferngeist.feature.chat.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tamimarafat.ferngeist.core.common.ui.dismissSheet
import com.tamimarafat.ferngeist.core.common.ui.handCursor
import com.tamimarafat.ferngeist.core.common.ui.motion.WorkingEllipsis
import com.tamimarafat.ferngeist.core.common.ui.onEscapeKey
import com.tamimarafat.ferngeist.core.common.ui.rememberEscapeFocusAnchor
import com.tamimarafat.ferngeist.core.common.ui.status.WorkingSubtitle
import com.tamimarafat.ferngeist.core.common.ui.worktree.WorktreeBadge
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.chat.R
import com.tamimarafat.ferngeist.feature.chat.SwitcherGroup
import com.tamimarafat.ferngeist.feature.chat.SwitcherSession
import com.tamimarafat.ferngeist.feature.chat.SwitcherUiState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Optical inset so the bubble reads the same height as the pill: an equal
 * geometric size looks heavier next to a long capsule, so the circle sits
 * slightly under the pill height (centered by the host row).
 */
private val switcherBubbleOpticalInset = 4.dp

/** How long a dismissed row waits for the model to drop it before springing back. */
private const val DISMISS_REMOVE_GRACE_MS = 600L

/** Spring the switcher rows and containers share when a row leaves the list. */
private fun <T> switcherShiftSpring() =
    spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

/** Count box: landscape rounded rectangle one digit sits in; wider counts grow it. */
private val switcherCountBoxMinWidth = 24.dp
private val switcherCountBoxMinHeight = 20.dp
private val switcherCountBoxCorner = 5.dp

/** First-run hint waits out the entrance wipe so the bubble pops onto a settled button. */
private const val SWITCHER_HINT_DELAY_MS = 600L

/** First-run hint lifetime: dismissed at this timeout, or sooner by a tap/drag. */
private const val SWITCHER_HINT_TIMEOUT_MS = 7_000L

/** First-run hint exit fade: the overlay stays alive this long so it can play out. */
private const val SWITCHER_HINT_EXIT_MS = 200L

/** Coach-mark bubble: chunkier than a PlainTooltip — bigger corners, roomier padding. */
private val switcherHintCorner = 12.dp

/** Coach-mark arrow: a wide downward triangle pointing at the button's center. */
private val switcherHintArrowWidth = 16.dp
private val switcherHintArrowHeight = 8.dp

/** Gap between the arrow tip and the top of the button. */
private val switcherHintGap = 8.dp

/**
 * Reports a horizontal drag for the Chrome-style switch: deltas while moving,
 * total plus fling velocity on release, cancellation when the gesture dies.
 * Taps pass through untouched — the detector only fires past touch slop.
 */
internal fun Modifier.switcherDrag(
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStopped: (totalPx: Float, velocityPxPerSec: Float) -> Unit,
    onDragCancel: () -> Unit,
): Modifier =
    pointerInput(onDragStart, onDragDelta, onDragStopped, onDragCancel) {
        val tracker = VelocityTracker()
        var totalX = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                totalX = 0f
                tracker.resetTracking()
                onDragStart()
            },
            onDragCancel = onDragCancel,
            onDragEnd = { onDragStopped(totalX, tracker.calculateVelocity().x) },
            onHorizontalDrag = { change, dragAmount ->
                tracker.addPosition(change.uptimeMillis, change.position)
                totalX += dragAmount
                onDragDelta(totalX)
            },
        )
    }

/**
 * Pins the first-run hint's right edge to the button's right edge, floating
 * above it by [gapPx]. Right-aligned (not centered) on purpose: the button
 * rides the screen's right edge, so a centered bubble would hang off-screen
 * and any on-screen clamp would drag the arrow off the button's center.
 * Extending left over the composer pill always stays on-screen instead.
 */
private class SwitcherHintPositionProvider(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
        val y = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(x, y)
    }
}

/**
 * Round live-session count button riding immediately to the right of the
 * composer pill: same primary color and elevation, and a near-pill-height
 * circle, so the two read as one control. Content is the plain live count.
 * Tap opens the sheet; a horizontal drag moves the whole chat with the
 * finger (Chrome-style) and release commits the switch or springs back.
 * Hidden while the composer is expanded or the session is solo. Before
 * [hintSeen], a one-shot Chrome-style coach mark — its own dark bubble with
 * an arrow, not the stock tooltip — hints "swipe to switch" above it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionSwitcherBubble(
    count: Int,
    visible: Boolean,
    hintSeen: Boolean,
    onHintSeen: () -> Unit,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStopped: (totalPx: Float, velocityPxPerSec: Float) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        // Size-only transitions, deliberately no fade/scale: those run content
        // through a compositing buffer sized to the composable bounds, which
        // omits the out-of-bounds elevation shadow on every animated frame
        // (graphicsLayer KDoc). A pure wipe reveals/hides circle and shadow
        // together with nothing to omit. `clip = false` stays as belt and
        // braces against the bounds clip (EnterExitTransition.kt:1012).
        enter =
            expandHorizontally(
                expandFrom = Alignment.End,
                clip = false,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
            ),
        exit =
            shrinkHorizontally(
                shrinkTowards = Alignment.End,
                clip = false,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
            ),
        modifier = modifier,
    ) {
        val tooltipState = rememberTooltipState()
        // One-shot Chrome-style coach mark: auto-shows once the entrance wipe
        // settles, then hides at the timeout and marks itself seen. Any earlier
        // tap/drag hides it instantly via consumeHint and marks it seen; the
        // effect restarts on the [hintSeen] flip into its hide arm, which keeps
        // the overlay alive just long enough to play the exit fade. Hiding the
        // bubble takes the hide arm without marking anything seen.
        var hintAlive by remember { mutableStateOf(false) }
        var hintShown by remember { mutableStateOf(false) }
        LaunchedEffect(visible, hintSeen) {
            if (visible && !hintSeen) {
                delay(SWITCHER_HINT_DELAY_MS)
                hintAlive = true
                // One frame so the Popup composes with the hint hidden; the
                // flip to shown below then plays the enter transition. Writing
                // both states back-to-back would first-compose already-visible
                // and skip enter entirely (exit worked because it flips while
                // composed).
                withFrameNanos { }
                hintShown = true
                delay(SWITCHER_HINT_TIMEOUT_MS)
                hintShown = false
                delay(SWITCHER_HINT_EXIT_MS)
                hintAlive = false
                onHintSeen()
            } else {
                hintShown = false
                delay(SWITCHER_HINT_EXIT_MS)
                hintAlive = false
            }
        }
        val consumeHint: () -> Unit = {
            if (!hintSeen) {
                hintShown = false
                onHintSeen()
            }
        }
        val density = LocalDensity.current
        val hintPositionProvider =
            remember(density) {
                SwitcherHintPositionProvider(
                    gapPx = with(density) { switcherHintGap.toPx().toInt() },
                )
            }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip {
                    Text(
                        text = stringResource(R.string.switcher_content_desc),
                    )
                }
            },
            state = tooltipState,
        ) {
            Surface(
                onClick = {
                    consumeHint()
                    onClick()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
                modifier =
                    Modifier
                        .size(collapsedComposerHeight - switcherBubbleOpticalInset)
                        .switcherDrag(
                            onDragStart = {
                                consumeHint()
                                onDragStart()
                            },
                            onDragDelta = onDragDelta,
                            onDragStopped = onDragStopped,
                            onDragCancel = onDragCancel,
                        ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Chrome tab-switcher look: the count inside a rounded-square
                    // outline. Width floors at the height so one digit is square
                    // and two digits widen the box instead of squeezing.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .sizeIn(
                                    minWidth = switcherCountBoxMinWidth,
                                    minHeight = switcherCountBoxMinHeight,
                                ).border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    shape = RoundedCornerShape(switcherCountBoxCorner),
                                ).padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                    // The overlay anchors to this Box (the button bounds) and
                    // floats free of the row layout, so the wide bubble never
                    // shoves the composer pill aside.
                    if (hintAlive) {
                        Popup(
                            popupPositionProvider = hintPositionProvider,
                            properties =
                                PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = false,
                                    dismissOnClickOutside = false,
                                ),
                        ) {
                            // Flat surface on purpose (no elevation): a fade runs
                            // content through a compositing buffer clipped to the
                            // composable bounds, which would omit an out-of-bounds
                            // shadow on every frame — same trap as the bubble wipe.
                            AnimatedVisibility(
                                visible = hintShown,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                            ) {
                                SwitcherHintBubble()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-shot coach mark floating above the switcher button: a dark bubble with
 * a downward arrow pinned to the button's center, deliberately chunkier than
 * the stock tooltip (bigger corners, roomier padding, wider arrow).
 */
@Composable
private fun SwitcherHintBubble(modifier: Modifier = Modifier) {
    val bubbleColor = MaterialTheme.colorScheme.inverseSurface
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(switcherHintCorner),
            color = bubbleColor,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(
                text = stringResource(R.string.switcher_first_run_hint),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        // Arrow center sits half a button width from the bubble's right edge,
        // which the position provider pins to the button's right edge — so the
        // tip lands on the button's center. Overlapped 1.dp into the bubble to
        // hide the antialiased seam.
        Canvas(
            modifier =
                Modifier
                    .padding(end = 21.dp)
                    .offset(y = (-1).dp)
                    .size(width = switcherHintArrowWidth, height = switcherHintArrowHeight),
        ) {
            val path =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
            drawPath(path = path, color = bubbleColor)
        }
    }
}

/** Switcher bottom sheet: live sessions grouped by server, newest first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionSwitcherSheet(
    uiState: SwitcherUiState,
    onSwitch: (SwitcherSession) -> Unit,
    onClose: (SwitcherSession) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val escapeFocus = rememberEscapeFocusAnchor()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            modifier
                // Key events reach the active focus target and its ancestors only, and the empty
                // list is text with nothing focusable in it.
                .focusRequester(escapeFocus)
                .focusTarget()
                .onEscapeKey { scope.dismissSheet(sheetState, onDismiss) },
    ) {
        Text(
            text = stringResource(R.string.switcher_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (uiState.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.switcher_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            // LookaheadScope lets every row FLIP into its new slot when a row above it
            // leaves. animateContentSize alone only tweens the container height, so
            // rows snap while the box glides.
            LookaheadScope {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = switcherShiftSpring()),
                ) {
                    uiState.groups.forEach { group ->
                        key(group.serverId) {
                            SwitcherServerGroup(
                                group = group,
                                lookaheadScope = this@LookaheadScope,
                                onSwitch = onSwitch,
                                onClose = onClose,
                                onCurrentClick = { scope.dismissSheet(sheetState, onDismiss) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitcherServerGroup(
    group: SwitcherGroup,
    lookaheadScope: LookaheadScope,
    onSwitch: (SwitcherSession) -> Unit,
    onClose: (SwitcherSession) -> Unit,
    onCurrentClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = switcherShiftSpring()),
    ) {
        Text(
            text = group.serverName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        group.sessions.forEach { session ->
            // Keys matter: without one, the row that slides into this slot inherits
            // the removed row's remembered dismiss state, renders off-screen, and
            // its settle effect closes that session too.
            key(session.sessionId) {
                SwitcherRow(
                    session = session,
                    lookaheadScope = lookaheadScope,
                    onSwitch = { onSwitch(session) },
                    onClose = { onClose(session) },
                    onCurrentClick = onCurrentClick,
                )
            }
        }
    }
}

@Composable
private fun SwitcherRow(
    session: SwitcherSession,
    lookaheadScope: LookaheadScope,
    onSwitch: () -> Unit,
    onClose: () -> Unit,
    onCurrentClick: () -> Unit,
) {
    val rowPadding = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    if (session.isCurrent) {
        // Tapping the session you are already reading only dismisses the sheet; closing
        // it happens from inside the chat.
        SwitcherRowContent(
            session = session,
            modifier =
                rowPadding
                    .clip(MaterialTheme.shapes.medium)
                    .handCursor()
                    .clickable(onClick = onCurrentClick),
        )
        return
    }
    // Swipe either way to release the session. Material3 leaves a dismissed row
    // off-screen (and gesture-disabled) until it is reset, so put it back here: the
    // list is driven by live hub state, and the row must disappear through the model
    // — which also means a refused close springs back into place instead of leaving
    // a blank slot. No background: the gesture is the affordance, and a label there
    // would be noise.
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.settledValue, onClose) {
        if (dismissState.settledValue != SwipeToDismissBoxValue.Settled) {
            onClose()
            // Give the model a beat to drop the row: a successful close removes it
            // from the list, which disposes this effect before the reset — the card
            // pops out while still off-screen, no bounce. Still here after the grace
            // (refused or slow close) means spring it back instead of leaving a blank
            // slot. Resetting immediately bounces the card home before the removal.
            delay(DISMISS_REMOVE_GRACE_MS)
            dismissState.reset()
        }
    }
    // animateBounds FLIPs this row into the slot its predecessor vacates. The padding
    // goes in as the intermediate modifier only — chaining it too would apply it
    // twice and inset the card 32dp on each side.
    val shiftBounds =
        remember {
            BoundsTransform { _, _ -> switcherShiftSpring<Rect>() }
        }
    // Fade by the box's own swipe offset: requireOffset() is the animated x-translation
    // SwipeToDismissBox applies to its content, so offset/width is the drag fraction in
    // either direction — 0 at rest, 1 at the dismiss edge, and it follows the spring back.
    // (`state.progress` can't do this: it reads 1f whenever the settled and target anchors
    // coincide, i.e. at rest, and flips denominator mid-drag when the target flips.)
    val rowWidth = remember { mutableIntStateOf(0) }
    SwipeToDismissBox(
        state = dismissState,
        modifier =
            Modifier
                .fillMaxWidth()
                .animateBounds(
                    lookaheadScope = lookaheadScope,
                    modifier = rowPadding,
                    boundsTransform = shiftBounds,
                ),
        backgroundContent = {},
    ) {
        SwitcherRowContent(
            session = session,
            // clip() on the card, not the swipe box: the Surface draws the rounded
            // medium shape, and without this the click ripple is a full-width rect
            // that spills past the corners.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .handCursor()
                    .clickable(onClick = onSwitch)
                    .onGloballyPositioned { rowWidth.intValue = it.size.width }
                    .graphicsLayer {
                        // requireOffset throws during semantics collection before
                        // first layout; until then the row is at rest, so alpha 1.
                        val offset =
                            try {
                                dismissState.requireOffset()
                            } catch (_: IllegalStateException) {
                                0f
                            }
                        val fraction = abs(offset) / rowWidth.intValue.coerceAtLeast(1)
                        alpha = 1f - fraction.coerceIn(0f, 1f)
                    },
        )
    }
}

@Composable
private fun SwitcherRowTitle(session: SwitcherSession) {
    Row {
        Text(
            text = sessionTitleOrNull(session.title) ?: stringResource(R.string.switcher_untitled),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
        )
        if (session.turnStatus == TurnStatus.WORKING && !session.isCurrent) {
            WorkingEllipsis(
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun SwitcherRowContent(
    session: SwitcherSession,
    modifier: Modifier,
) {
    Surface(
        color =
            if (session.isCurrent) {
                // Same treatment as a connected server's card: the activated role, not dimmed.
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // A shared subtitle keeps turn state separate from the readable session title.
                SwitcherRowTitle(session)
                val place = session.project
                val time = session.updatedAt?.let { formatSwitcherTime(it, LocalContext.current) }
                WorkingSubtitle(
                    status = session.turnStatus,
                    streamKey = "${session.serverId}/${session.sessionId}",
                ) {
                    if (place != null || time != null) {
                        Text(
                            text = listOfNotNull(place, time).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // The branch gets its own line, as on the session list: sharing one with the
                // project let a long branch squeeze the project down to a few characters.
                session.worktree?.let {
                    WorktreeBadge(
                        branch = it.branch,
                        modifier = Modifier.padding(top = 4.dp),
                        ahead = it.ahead,
                        dirty = it.dirty,
                    )
                }
            }
            // Centred against the whole card, not just the title line, so a worktree
            // row's extra branch line cannot pull it off-centre.
            if (session.isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.switcher_current_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Short relative stamp ("2m", "3h", "2d") — the row only needs to say how stale
 * a session is, and a full date/time costs a line of width for precision nobody
 * reads. Past a week the bucket stops being useful, so fall back to a date.
 */
private fun formatSwitcherTime(
    epochMs: Long,
    context: Context,
    nowMs: Long = System.currentTimeMillis(),
): String =
    when (val age = relativeAge(nowMs, epochMs)) {
        RelativeAge.Now -> context.getString(R.string.chat_git_date_now)
        is RelativeAge.Minutes -> context.getString(R.string.chat_git_date_minutes, age.count)
        is RelativeAge.Hours -> context.getString(R.string.chat_git_date_hours, age.count)
        is RelativeAge.Days -> context.getString(R.string.chat_git_date_days, age.count)
        RelativeAge.Older -> formatSwitcherDate(epochMs, context)
    }

/**
 * Locale-aware "Sep 20" style date: field order follows the system locale via a
 * best-pattern skeleton, unlike a hardcoded `SimpleDateFormat` pattern, which
 * bakes in US order on every device.
 */
private fun formatSwitcherDate(
    epochMs: Long,
    context: Context,
): String {
    val locales = context.resources.configuration.getLocales()
    val locale = locales.get(0)
    val datePattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
    return SimpleDateFormat(datePattern, locale).format(Date(epochMs))
}
