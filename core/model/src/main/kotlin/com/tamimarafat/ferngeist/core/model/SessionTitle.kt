package com.tamimarafat.ferngeist.core.model

/**
 * The name to show for a session, or null when it has none.
 *
 * An agent that has not named a session does not say so: it hands back a synthesis of the
 * first user message instead — pi-acp's `session/list` returns that message cut to 80
 * characters. A blank title and a prompt-shaped one therefore mean the same thing, and
 * deciding that here keeps every screen that shows a session title from re-deriving the
 * rule for itself.
 *
 * [firstUserMessage] is what separates a real name from the synthesis, and only a caller
 * holding the transcript has it, so it is optional: without it the blank rule still
 * applies. The comparison is deliberately over-inclusive — a name that happens to be a
 * prefix of the prompt is refused along with it — because showing the prompt is the worse
 * failure of the two.
 */
fun sessionTitleOrNull(
    raw: String?,
    firstUserMessage: String? = null,
): String? {
    if (raw.isNullOrBlank()) return null
    val prompt = firstUserMessage?.trim().orEmpty()
    if (prompt.isEmpty()) return raw
    return raw.takeUnless { raw == prompt || prompt.startsWith(raw) }
}
