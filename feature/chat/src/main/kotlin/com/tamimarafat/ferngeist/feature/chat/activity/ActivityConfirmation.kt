package com.tamimarafat.ferngeist.feature.chat.activity

/** One decision per opening, including when the user cancels the prompt. */
internal class ActivityConfirmation(
    private val listedUpdatedAt: Long?,
) {
    private var checked = false

    fun needsConfirmation(
        lastPhoneTurn: Long?,
        now: Long,
    ): Boolean {
        if (checked) return false
        checked = true
        val listed = listedUpdatedAt?.takeIf { it >= 0 } ?: return false
        return listed <= now &&
            now - listed < RECENT_ACTIVITY_MS &&
            (lastPhoneTurn == null || listed > lastPhoneTurn)
    }

    private companion object {
        const val RECENT_ACTIVITY_MS = 120_000L
    }
}
