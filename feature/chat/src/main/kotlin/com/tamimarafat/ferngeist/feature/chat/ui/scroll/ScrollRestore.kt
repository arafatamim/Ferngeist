package com.tamimarafat.ferngeist.feature.chat.ui.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.feature.chat.ChatScrollSnapshot
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

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
    LaunchedEffect(handle) { handle.watchEnd() }
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
        observePersistence(handle, viewportWidthDp, onScrollSnapshotChanged)
    }
    LaunchedEffect(handle, restoreReady, restoreKey) {
        if (!restoreReady || !handle.restorePending || renderedMessages.isEmpty()) return@LaunchedEffect
        val snapshot = restoredScrollSnapshot ?: return@LaunchedEffect
        val rows = snapshotFlow { handle.rows }.first { it != null } ?: return@LaunchedEffect
        val restoredIndex =
            snapshot.anchorMessageId
                ?.let(rows::rowOfMessage)
                ?: snapshot.firstVisibleItemIndex.coerceIn(0, rows.endIndex)
        handle.restore(
            index = restoredIndex,
            offset = { snapshot.offsetForRestore(viewportWidthDp()).coerceAtLeast(0) },
            isFollowing = snapshot.isFollowing,
        )
    }
    return handle
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
private suspend fun observePersistence(
    handle: ChatScrollHandle,
    viewportWidthDp: () -> Int,
    onScrollSnapshotChanged: (ChatScrollSnapshot) -> Unit,
) {
    val listState = handle.listState
    snapshotFlow {
        val rows = handle.rows ?: return@snapshotFlow null
        if (rows.turns.isEmpty() || handle.restorePending) return@snapshotFlow null
        val idx = listState.firstVisibleItemIndex
        ChatScrollSnapshot(
            anchorMessageId = rows.turnAt(idx)?.first?.id,
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
