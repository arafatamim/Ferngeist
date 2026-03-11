package com.tamimarafat.ferngeist.feature.chat

data class ChatScrollSnapshot(
    val anchorMessageId: String?,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isFollowing: Boolean,
    val savedAt: Long,
)
