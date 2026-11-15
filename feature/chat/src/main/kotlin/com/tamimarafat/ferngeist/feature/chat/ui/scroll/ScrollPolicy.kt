package com.tamimarafat.ferngeist.feature.chat.ui.scroll

internal data class ScrollPolicy(
    val following: Boolean = true,
    val gliding: Boolean = false,
    val readerScrolled: Boolean = false,
    val reachedEnd: Boolean = false,
    val pinnedToEnd: Boolean = false,
    val easing: Boolean = false,
    val restorePending: Boolean = false,
) {
    fun followsRail(activeRails: Int): Boolean = activeRails > 0 && !readerScrolled && !easing && !restorePending

    // A smoothed pin drains its remaining distance after completion; a reader release clears the pin.
    fun followsEnd(
        live: Boolean,
        endPending: Boolean = false,
    ): Boolean = pinnedToEnd && (live || endPending) && !easing && !restorePending && !gliding

    fun owned(
        live: Boolean,
        activeRails: Int,
        endPending: Boolean = false,
    ): Boolean = gliding || easing || restorePending || followsRail(activeRails) || followsEnd(live, endPending)

    fun on(event: ScrollEvent): ScrollPolicy =
        when (event) {
            ScrollEvent.Sent ->
                copy(readerScrolled = false, reachedEnd = false, pinnedToEnd = false, following = true, gliding = true)
            is ScrollEvent.Released ->
                copy(
                    readerScrolled = true,
                    pinnedToEnd = false,
                    following = false,
                    gliding = false,
                    reachedEnd = reachedEnd && event.towardEnd,
                )
            ScrollEvent.ReachedEnd -> copy(reachedEnd = true)
            ScrollEvent.GlideArrived -> copy(gliding = false)
            is ScrollEvent.JumpedToBottom -> copy(following = true, pinnedToEnd = event.live)
            is ScrollEvent.Rested -> rested(event)
            is ScrollEvent.FollowEnded -> copy(pinnedToEnd = pinnedToEnd && event.live)
            is ScrollEvent.Easing -> copy(easing = event.active)
            is ScrollEvent.RestorePending -> copy(restorePending = event.pending)
            is ScrollEvent.Restored -> copy(following = event.isFollowing, restorePending = false)
        }

    private fun rested(event: ScrollEvent.Rested): ScrollPolicy {
        // Streaming may move the end away after the reader touched it but before their scroll rests.
        val pin = event.live && readerScrolled && reachedEnd
        return copy(
            following = event.atEnd || pin,
            pinnedToEnd = pinnedToEnd || pin,
            reachedEnd = reachedEnd && event.atEnd,
        )
    }
}

internal val ScrollPolicy.inCharge: Boolean get() = following && !easing && !restorePending
internal val ScrollPolicy.allowsJumpButton: Boolean get() = !gliding && !easing

/** The sent prompt's glide runs. */
internal val ScrollPolicy.glides: Boolean get() = inCharge && gliding

/** The end is held in its own measure: the keyboard or composer moving under it, or a new message. */
internal val ScrollPolicy.holdsEnd: Boolean get() = inCharge && !gliding

/**
 * A new message is followed once nothing streams. Opening a chat is too, even mid-reply: initial
 * history opens at its end in its own measure.
 */
internal fun ScrollPolicy.followsAppend(
    opening: Boolean,
    live: Boolean,
): Boolean = holdsEnd && (opening || !live)

internal sealed interface ScrollEvent {
    data object Sent : ScrollEvent

    data class Released(
        val towardEnd: Boolean,
    ) : ScrollEvent

    data object ReachedEnd : ScrollEvent

    data object GlideArrived : ScrollEvent

    data class JumpedToBottom(
        val live: Boolean,
    ) : ScrollEvent

    data class Rested(
        val atEnd: Boolean,
        val live: Boolean,
    ) : ScrollEvent

    data class FollowEnded(
        val live: Boolean,
    ) : ScrollEvent

    data class Easing(
        val active: Boolean,
    ) : ScrollEvent

    data class RestorePending(
        val pending: Boolean,
    ) : ScrollEvent

    data class Restored(
        val isFollowing: Boolean,
    ) : ScrollEvent
}
