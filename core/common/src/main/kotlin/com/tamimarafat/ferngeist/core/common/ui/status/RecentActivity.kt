package com.tamimarafat.ferngeist.core.common.ui.status

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val RECENT_ACTIVITY_WINDOW_MS = 60_000L

/** A single local expiry; collecting again on resume checks the clock afresh. */
internal fun recentActivity(
    updatedAt: Long?,
    now: () -> Long,
): Flow<Boolean> =
    flow {
        val current = now()
        val age = updatedAt?.takeIf { it > 0 && it <= current }?.let { current - it }
        val remaining = age?.let { RECENT_ACTIVITY_WINDOW_MS - it } ?: 0
        emit(remaining > 0)
        if (remaining > 0) {
            delay(remaining)
            emit(false)
        }
    }
