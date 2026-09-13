package com.tamimarafat.ferngeist.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollSnapshotWidthTest {
    private fun snapshot(
        width: Int,
        offset: Int,
    ) = ChatScrollSnapshot(
        anchorMessageId = "m42",
        firstVisibleItemIndex = 10,
        firstVisibleItemScrollOffset = offset,
        isFollowing = false,
        savedAt = 1L,
        containerWidthDp = width,
    )

    @Test
    fun offsetSurvives_whenWidthIsUnchanged() {
        val restored = snapshot(width = 400, offset = 120).offsetForRestore(currentWidthDp = 400)
        assertEquals(120, restored)
    }

    @Test
    fun offsetIsDropped_whenWidthChanged() {
        // Item heights changed with the width, so a pixel offset measured at 400dp is
        // meaningless at 900dp. Anchor to the message top instead of a stale pixel value.
        val restored = snapshot(width = 400, offset = 120).offsetForRestore(currentWidthDp = 900)
        assertEquals(0, restored)
    }
}
