package com.tamimarafat.ferngeist.feature.chat.ui.message

// A user message is whatever the agent echoes back: a pasted log or an inlined blob is one line with
// no break opportunities, and laying it out costs memory in proportion to its length.
private const val MAX_DISPLAY_CHARS = 10_000

/** [this] cut to what a bubble can afford to lay out, ending in an ellipsis when it was cut. */
internal fun String.cappedForDisplay(): String =
    if (length <= MAX_DISPLAY_CHARS) {
        this
    } else {
        take(MAX_DISPLAY_CHARS) + "…"
    }
