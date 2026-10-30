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
        // The listed time is the agent's, on the gateway host's clock and after its post-turn writes,
        // so this phone's own turn lists seconds later than the phone saw it end.
        return listed <= now &&
            now - listed < RECENT_ACTIVITY_MS &&
            (lastPhoneTurn == null || listed > lastPhoneTurn + OWN_TURN_GRACE_MS)
    }

    private companion object {
        const val RECENT_ACTIVITY_MS = 120_000L
        const val OWN_TURN_GRACE_MS = 30_000L
    }
}
