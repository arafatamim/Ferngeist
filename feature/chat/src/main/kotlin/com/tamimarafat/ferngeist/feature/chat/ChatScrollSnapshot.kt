package com.tamimarafat.ferngeist.feature.chat

data class ChatScrollSnapshot(
    val anchorMessageId: String?,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isFollowing: Boolean,
    val savedAt: Long,
    /**
     * Viewport width this snapshot was measured at. A pixel scroll offset is only meaningful
     * at the width it was taken; a fold or rotate changes item heights, so the offset is
     * re-anchored to the message top instead of being applied verbatim.
     */
    val containerWidthDp: Int = 0,
) {
    /** Scroll offset to apply when restoring into a viewport [currentWidthDp] wide. */
    fun offsetForRestore(currentWidthDp: Int): Int =
        if (containerWidthDp == 0 || containerWidthDp == currentWidthDp) {
            firstVisibleItemScrollOffset
        } else {
            0
        }
}
