package com.tamimarafat.ferngeist.push

/**
 * Pure, Firebase-independent notification id selection for pushes. Shared across build
 * flavours so the id-allocation policy has a single source of truth and stays unit-testable
 * without Play Services.
 *
 * `progress` pushes coalesce per session — the same session reuses a stable id so each new
 * push replaces the previous "Agent working" notification instead of stacking. Everything
 * else (including progress without a session id) falls back to [next].
 */
internal fun notificationIdFor(
    category: String?,
    sessionId: String?,
    next: () -> Int,
): Int =
    if (category == PUSH_CATEGORY_PROGRESS && sessionId != null) {
        PROGRESS_NOTIFICATION_ID_BASE + (sessionId.hashCode() and Int.MAX_VALUE) % PROGRESS_NOTIFICATION_ID_RANGE
    } else {
        next()
    }

// Progress pushes coalesce per session into a fixed id range so they replace (not stack)
// the previous "Agent working" notification for that session.
private const val PROGRESS_NOTIFICATION_ID_BASE = 5000
private const val PROGRESS_NOTIFICATION_ID_RANGE = 1000
