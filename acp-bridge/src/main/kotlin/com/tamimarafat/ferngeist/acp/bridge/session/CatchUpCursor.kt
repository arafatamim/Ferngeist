package com.tamimarafat.ferngeist.acp.bridge.session

/** Where a saved state left off in the gateway's update numbering for one agent process. */
data class CatchUpCursor(
    val epoch: String,
    val afterSeq: Long,
)
