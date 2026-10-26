package com.tamimarafat.ferngeist.feature.chat.title

import com.tamimarafat.ferngeist.core.model.ChatLoadState
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ChatSessionSnapshot
import com.tamimarafat.ferngeist.core.model.SessionSummary
import com.tamimarafat.ferngeist.core.model.sessionTitleOrNull
import com.tamimarafat.ferngeist.feature.chat.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Decides which name the app bar shows for a chat, from every path that learns one: the nav arg,
 * the session store, the agent's push, and the session list's own read. The next authoritative listing
 * supplies names to other screens; this controller only updates the open chat.
 */
internal class ChatTitleController(
    private val scope: CoroutineScope,
    private val state: StateFlow<ChatState>,
    private val update: (ChatState.() -> ChatState) -> Unit,
    private val durableSessionId: () -> String?,
    private val fetchTitle: suspend () -> String?,
) {
    /**
     * True once the agent pushed a title over `session_info_update`. A pushed title is
     * the agent naming the session, so it is canonical: nothing replaces it afterwards.
     */
    private var titlePushedByAgent = false

    /**
     * True once this chat has asked the agent's session list for a title. Reset when a
     * new turn starts, so a name the agent assigns at any point still reaches the bar.
     */
    private var titleFetchDone = false

    /** Adopts the nav arg's title, which seeds the first frame before the store answers. */
    fun seed(navTitle: String) {
        adoptTitle(navTitle)
    }

    /** Adopts this chat's own row from a listing of the store, when the chat has an id yet. */
    fun onSessionsListed(sessions: List<SessionSummary>) {
        val currentId = durableSessionId() ?: return
        adoptTitle(sessions.firstOrNull { it.id == currentId }?.title)
    }

    fun onSnapshot(snapshot: ChatSessionSnapshot) {
        dropTitleThatIsThePrompt()
        applyServerTitle(snapshot.title)
        fetchGeneratedTitleIfNeeded(snapshot)
    }

    /**
     * A title stored before this chat knew its first message can be the prompt
     * itself, since that is what an agent synthesises for a session it has not
     * named. Now that the transcript can tell, drop it and let a real name land.
     */
    private fun dropTitleThatIsThePrompt() {
        val storedTitle = state.value.title
        val storedTitleIsThePrompt =
            storedTitle != null && sessionTitleOrNull(storedTitle, firstMessage()) == null
        if (!titlePushedByAgent && storedTitleIsThePrompt) {
            update { copy(title = null) }
        }
    }

    /**
     * Adopts [candidate] as the app-bar title, from any path that learns one. Refused when the
     * agent already pushed a title — that is the agent's own name for the session, and
     * nothing replaces it — and when [sessionTitleOrNull] says the candidate is not a
     * name at all. Returns whether it was adopted.
     */
    private fun adoptTitle(candidate: String?): Boolean {
        if (titlePushedByAgent) return false
        val name = sessionTitleOrNull(candidate, firstMessage()) ?: return false
        if (state.value.title != name) {
            update { copy(title = name) }
        }
        return true
    }

    /**
     * Applies the title the agent pushed over `session_info_update`. Pushing a name is
     * the agent telling the client what to call the session, so it wins over anything
     * the session list reported and closes the app bar to further changes. The snapshot remains the agent listing, rather than a locally rewritten record.
     */
    private fun applyServerTitle(serverTitle: String?) {
        if (serverTitle.isNullOrBlank()) return
        if (titlePushedByAgent && state.value.title == serverTitle) return
        titlePushedByAgent = true
        update { copy(title = serverTitle) }
    }

    /**
     * Asks the agent's session list for this session's title. An agent that names a
     * session server-side does not necessarily push it — pi-acp pushes a name only for
     * an explicit `/name` — so the list is the only way to learn it. Re-armed for every
     * turn, and once on open for a session with history, so a name an agent assigns
     * later still reaches the app bar.
     */
    private fun fetchGeneratedTitleIfNeeded(snapshot: ChatSessionSnapshot) {
        if (snapshot.isStreaming) {
            // A turn is running; whether the agent has named the session is only
            // answerable once it settles, so re-arm the read for that snapshot.
            titleFetchDone = false
            return
        }
        if (!shouldAskForGeneratedTitle(snapshot)) return
        titleFetchDone = true
        scope.launch {
            for (delayMs in titleFetchRetryDelaysMs) {
                delay(delayMs)
                if (titlePushedByAgent || !state.value.title.isNullOrBlank()) return@launch
                val title = fetchTitle()
                if (title.isNullOrBlank() || !adoptTitle(title)) continue
                // Other screens adopt the name from their next authoritative listing.
                return@launch
            }
        }
    }

    /**
     * Whether this settled snapshot is a moment to ask the agent's session list for a
     * title: the chat is loaded with a response in it, still has no title, and has not
     * already asked since the last turn began.
     */
    private fun shouldAskForGeneratedTitle(snapshot: ChatSessionSnapshot): Boolean =
        !titleFetchDone &&
            !titlePushedByAgent &&
            snapshot.loadState == ChatLoadState.READY &&
            state.value.title.isNullOrBlank() &&
            snapshot.messages.any { it.role == ChatMessage.Role.ASSISTANT } &&
            durableSessionId() != null

    /**
     * The first thing the user said, which is what an agent synthesises a session
     * title from when it has not named the session itself.
     */
    private fun firstMessage(): String? =
        state.value.messages
            .firstOrNull { it.role == ChatMessage.Role.USER }
            ?.content

    private companion object {
        /**
         * Delay before each `session/list` title attempt, relative to the end of the
         * first response. The first is immediate; the rest give a slow agent time to
         * name the session — it often does so just after the turn settles.
         */
        val titleFetchRetryDelaysMs = listOf(0L, 3_000L, 8_000L)
    }
}
